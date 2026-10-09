package sh.haven.app.agent.mailrules

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import sh.haven.app.MainActivity
import sh.haven.core.data.db.entities.MailRulePendingAction
import sh.haven.core.data.repository.MailRuleRepository
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns Mail-Rules notifications. Replaces the old per-event `raise_notification`
 * posts, which stacked one notification per queued action (fresh id every call),
 * were tap-dead (no content intent), and landed on the agent-test channel — so
 * muting the spam would also have muted real agent notifications.
 *
 * The pending-approval queue gets ONE notification with a stable (tag, id) key,
 * mirrored from the Room table: posted/updated (alerting only on the first post
 * of a batch) while the queue is non-empty, cancelled when it drains. Because
 * the source of truth is the observed table, approving or rejecting from any
 * surface — the rules UI, a crash-replay, an MCP verb — clears or renumbers the
 * notification with no extra call sites. Tapping opens the Mail Rules pane
 * (approval queue) via [MainActivity] + `AgentUiCommand.OpenMailRules`.
 */
@Singleton
class MailRuleNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: MailRuleRepository,
) {
    private val started = AtomicBoolean(false)
    private val firedCounter = AtomicInteger(0)

    /**
     * Idempotent. Mirrors the pending-approval queue into the notification for
     * the process lifetime — deliberately independent of the master automation
     * switch, so a queue left over from an earlier session still surfaces.
     */
    fun start(scope: CoroutineScope) {
        if (!started.compareAndSet(false, true)) return
        scope.launch {
            repo.observePendingActions().collect { pending ->
                runCatching { syncPendingNotification(pending) }
            }
        }
    }

    /** Per-fire "rule fired" notification — opt-in per rule (`notifyOnFire`). */
    fun notifyRuleFired(ruleName: String, subject: String) {
        val mgr = NotificationManagerCompat.from(context)
        if (!mgr.areNotificationsEnabled()) return
        ensureChannel()
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle("Mail rule fired: $ruleName")
            .setContentText(subject)
            .setStyle(NotificationCompat.BigTextStyle().bigText(subject))
            .setContentIntent(openRulesIntent())
            .setGroup(GROUP_RULE_FIRED)
            .setAutoCancel(true)
            .build()
        try {
            mgr.notify(TAG_RULE_FIRED, firedCounter.getAndIncrement() % FIRED_ID_SPAN, n)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and notify; ignore.
        }
    }

    private fun syncPendingNotification(pending: List<MailRulePendingAction>) {
        val mgr = NotificationManagerCompat.from(context)
        if (pending.isEmpty()) {
            mgr.cancel(TAG_PENDING, PENDING_ID)
            return
        }
        if (!mgr.areNotificationsEnabled()) return
        ensureChannel()
        val title = if (pending.size == 1) {
            "1 mail action awaiting approval"
        } else {
            "${pending.size} mail actions awaiting approval"
        }
        val style = NotificationCompat.InboxStyle()
        pending.sortedByDescending { it.queuedAt }.take(INBOX_LINES).forEach {
            style.addLine(it.messageSubject.orEmpty().ifEmpty { "(no subject)" })
        }
        if (pending.size > INBOX_LINES) style.setSummaryText("+${pending.size - INBOX_LINES} more")
        val n = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(title)
            .setContentText("Tap to review in Mail rules")
            .setStyle(style)
            .setContentIntent(openRulesIntent())
            // A growing queue updates the one notification silently; only the
            // first post of a batch alerts.
            .setOnlyAlertOnce(true)
            // Not auto-cancel: the queue is still pending after a tap — the
            // table observer cancels it once the actions are approved/rejected.
            .setAutoCancel(false)
            .build()
        try {
            mgr.notify(TAG_PENDING, PENDING_ID, n)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS revoked between the check and notify; ignore.
        }
    }

    private fun openRulesIntent(): PendingIntent {
        val launch = Intent(context, MainActivity::class.java).apply {
            action = ACTION_OPEN_MAIL_RULES
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )
        }
        return PendingIntent.getActivity(
            context, ACTION_OPEN_MAIL_RULES.hashCode(), launch,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun ensureChannel() {
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Mail rules", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "Mail-rule activity: destructive actions queued for approval, and " +
                    "per-rule fired alerts for rules that opted in."
            },
        )
    }

    companion object {
        /** Content-intent action; [MainActivity] re-publishes it as `AgentUiCommand.OpenMailRules`. */
        const val ACTION_OPEN_MAIL_RULES = "sh.haven.app.action.OPEN_MAIL_RULES"

        private const val CHANNEL_ID = "mail.rules"
        private const val TAG_PENDING = "mail.rules.pending"
        private const val PENDING_ID = 0
        private const val TAG_RULE_FIRED = "mail.rules.fired"
        private const val GROUP_RULE_FIRED = "sh.haven.app.MAIL_RULE_FIRED"
        /** Ids recycle inside their own tag namespace; the shade never holds this many. */
        private const val FIRED_ID_SPAN = 200
        private const val INBOX_LINES = 5
    }
}
