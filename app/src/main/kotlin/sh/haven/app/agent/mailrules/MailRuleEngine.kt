package sh.haven.app.agent.mailrules

import android.util.Log
import sh.haven.core.data.db.entities.MailRule
import sh.haven.core.data.db.entities.MailRuleFiring
import sh.haven.core.data.db.entities.MailWatermark
import sh.haven.core.data.mailrule.MailRuleAction
import sh.haven.core.data.mailrule.MailRuleJson
import sh.haven.core.data.mailrule.MailRuleMatcher
import sh.haven.core.data.mailrule.MatchableMessage
import sh.haven.core.data.repository.MailRuleRepository

/** UID state of a watched folder, read at the start of each poll. */
data class FolderUidState(val uidValidity: Long, val uidNext: Long?, val maxUid: Long)

/** A new message found by the poller: its envelope (Tier-1) plus the encoded id + UID. */
data class PolledMessage(val messageId: String, val uid: Long, val envelope: MatchableMessage)

/** Parsed Tier-2 content, fetched only when a matching rule needs it. */
data class MessageContent(
    val bodyText: String?,
    val attachmentNames: List<String>,
    val attachmentMimes: List<String>,
    val headers: Map<String, List<String>>,
)

/** Per-action invocation context handed to the [MailRuleActionRunner]. */
data class ActionContext(
    val profileId: String,
    val folderId: String,
    val uid: Long,
    val messageId: String,
    val message: MatchableMessage,
    val ruleId: String,
    val ruleName: String,
)

/** Result of running one action. [queued] = deferred to foreground approval, not executed. */
data class ActionOutcome(val ok: Boolean, val summary: String, val queued: Boolean = false)

/**
 * The mail side the engine drives. The real impl ([RealMailRulePoller]) reconnects a
 * session on demand and reads via [sh.haven.core.mail.ImapMailClient]; tests fake it.
 * [folderState] returns null when the account can't be reached (poll skipped, no state change).
 */
interface MailRulePoller {
    suspend fun folderState(profileId: String, folderId: String): FolderUidState?
    suspend fun fetchNew(profileId: String, folderId: String, sinceUid: Long, max: Int): List<PolledMessage>
    suspend fun fetchContent(profileId: String, messageId: String): MessageContent?
}

/**
 * Runs one matched rule's action. The real impl routes through `McpServer` (reusing every
 * existing tool — the rule is the pre-authorization) and the IMAP filter ops, deciding
 * run-vs-queue from the action's destructive posture and whether the app is [foreground].
 */
interface MailRuleActionRunner {
    suspend fun run(action: MailRuleAction, ctx: ActionContext, foreground: Boolean): ActionOutcome
    suspend fun notifyRuleFired(ruleName: String, subject: String)
}

/**
 * The crash-safe heart of Mail Rules. [pollOnce] processes one `(profileId, folderId)`:
 * read the folder's UID state, advance/guard the watermark, fetch new messages, and for
 * each — in ascending UID order — evaluate enabled rules in order and run the matching
 * ones' actions, recording each firing durably.
 *
 * Correctness properties (covered by [MailRuleEngineTest]):
 * - **No replay on UIDVALIDITY change** — if the server renumbered the mailbox, reset the
 *   watermark to the current max and run nothing (replaying would re-fire destructive
 *   actions over the whole mailbox).
 * - **First-arm** seeds the watermark to the current max so existing mail never floods.
 * - **Exactly-once actions** — a per-`(rule, uid)` firing row carries an
 *   `actionsCompletedMask`; a crash mid-batch resumes without repeating a completed
 *   (non-idempotent) action. The watermark advances per-UID only after its firing is recorded.
 * - **Failure isolation** — one action's exception is caught + recorded and never aborts
 *   the rest.
 * - **Destructive-last** — IMAP move/delete run after a rule's other actions.
 */
class MailRuleEngine(
    private val repo: MailRuleRepository,
    private val poller: MailRulePoller,
    private val runner: MailRuleActionRunner,
    private val now: () -> Long = { System.currentTimeMillis() },
    private val foreground: () -> Boolean = { true },
) {
    suspend fun pollOnce(profileId: String, folderId: String) {
        val rules = repo.enabledForAccount(profileId).filter { it.folderId == folderId }
        if (rules.isEmpty()) return

        val state = poller.folderState(profileId, folderId)
        if (state == null) {
            recordSystem(profileId, folderId, MailRuleFiring.KIND_POLL_SKIPPED, 0)
            return
        }

        val wm = repo.getWatermark(profileId, folderId)
        if (wm == null) {
            // First-arm: rules apply only to mail arriving after this point.
            repo.saveWatermark(MailWatermark(profileId, folderId, state.uidValidity, state.maxUid, state.uidNext, now()))
            return
        }
        if (wm.uidValidity != state.uidValidity) {
            // Mailbox renumbered — every stored UID is meaningless. Skip, don't replay.
            recordSystem(profileId, folderId, MailRuleFiring.KIND_UIDVALIDITY_RESET, state.maxUid)
            repo.saveWatermark(
                wm.copy(uidValidity = state.uidValidity, lastSeenUid = state.maxUid, uidNext = state.uidNext, lastPolledAt = now()),
            )
            return
        }

        // Pre-parse criteria once; decide whether any rule needs Tier-2 content.
        val parsed = rules.map { it to MailRuleJson.criteriaFromJson(it.criteriaJson) }
        val anyNeedsContent = parsed.any { MailRuleMatcher.requiresContent(it.second) }

        val newMessages = poller.fetchNew(profileId, folderId, wm.lastSeenUid, MAX_PER_POLL).sortedBy { it.uid }
        var lastSeen = wm.lastSeenUid
        for (pm in newMessages) {
            runCatching { processMessage(profileId, folderId, pm, parsed, anyNeedsContent) }
                .onFailure { Log.w(TAG, "rule processing failed for uid=${pm.uid}: ${it.message}") }
            lastSeen = pm.uid
            // Advance the watermark per-UID: a crash resumes exactly after the last recorded UID.
            repo.saveWatermark(
                MailWatermark(profileId, folderId, state.uidValidity, lastSeen, state.uidNext, now()),
            )
        }
    }

    private suspend fun processMessage(
        profileId: String,
        folderId: String,
        pm: PolledMessage,
        parsed: List<Pair<MailRule, sh.haven.core.data.mailrule.MailCriteria>>,
        anyNeedsContent: Boolean,
    ) {
        var msg = pm.envelope
        if (anyNeedsContent) {
            poller.fetchContent(profileId, pm.messageId)?.let { c ->
                msg = msg.copy(
                    bodyText = c.bodyText,
                    attachmentNames = c.attachmentNames,
                    attachmentMimes = c.attachmentMimes,
                    headers = c.headers,
                )
            }
        }
        for ((rule, criteria) in parsed) {
            if (!MailRuleMatcher.matches(criteria, msg)) continue
            fireRule(rule, profileId, folderId, pm, msg)
            if (rule.stopOnMatch) break
        }
    }

    private suspend fun fireRule(
        rule: MailRule,
        profileId: String,
        folderId: String,
        pm: PolledMessage,
        msg: MatchableMessage,
    ) {
        val actions = orderActions(MailRuleJson.actionsFromJson(rule.actionsJson))
        if (actions.isEmpty()) return

        // Resume an existing firing (crash-replay) or start a new one.
        val existing = repo.getFiring(rule.id, profileId, folderId, pm.uid)
        var mask = existing?.actionsCompletedMask ?: 0L
        var firing = existing ?: MailRuleFiring(
            ruleId = rule.id, profileId = profileId, folderId = folderId, uid = pm.uid,
            messageSubject = msg.subject, firedAt = now(), kind = MailRuleFiring.KIND_FIRED,
        ).let { it.copy(id = repo.insertFiring(it)) }

        val foreground = foreground()
        val summaries = mutableListOf<String>()
        actions.forEachIndexed { idx, action ->
            val bit = 1L shl idx
            if (mask and bit != 0L) return@forEachIndexed // already handled on a prior run
            val ctx = ActionContext(profileId, folderId, pm.uid, pm.messageId, msg, rule.id, rule.name)
            val outcome = runCatching { runner.run(action, ctx, foreground) }
                .getOrElse { ActionOutcome(false, "error: ${it.message}") }
            summaries += outcome.summary
            // "handled" (mask set) when it ran OK or was queued for approval — either way,
            // it must not repeat on a crash-replay. A hard failure leaves the bit clear to retry.
            if (outcome.ok) mask = mask or bit
            firing = firing.copy(actionsCompletedMask = mask, outcomeSummary = summaries.joinToString("; ").take(900))
            repo.updateFiring(firing)
        }
        repo.touchLastFired(rule.id, now())
        if (rule.notifyOnFire) runCatching { runner.notifyRuleFired(rule.name, msg.subject) }
    }

    /** Force destructive IMAP ops (move/delete) to run after a rule's other actions (stable). */
    private fun orderActions(actions: List<MailRuleAction>): List<MailRuleAction> =
        actions.sortedBy { a ->
            if (a is MailRuleAction.ImapFilter &&
                (a.op == sh.haven.core.data.mailrule.ImapFilterOp.MOVE || a.op == sh.haven.core.data.mailrule.ImapFilterOp.DELETE)
            ) 1 else 0
        }

    private suspend fun recordSystem(profileId: String, folderId: String, kind: String, uid: Long) {
        runCatching {
            repo.insertFiring(
                MailRuleFiring(ruleId = null, profileId = profileId, folderId = folderId, uid = uid, firedAt = now(), kind = kind),
            )
        }
    }

    companion object {
        private const val TAG = "MailRuleEngine"
        /** Bound on messages processed per poll so a backlog drains in chunks, not one giant batch. */
        const val MAX_PER_POLL = 200
    }
}
