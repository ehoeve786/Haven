package sh.haven.app.agent.mailrules

import sh.haven.core.data.mailrule.MatchableMessage
import sh.haven.core.mail.MailClient
import sh.haven.core.mail.MailMessage
import sh.haven.core.mail.MailSessionManager
import sh.haven.feature.mail.MimeParser
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The real [MailRulePoller]: reads new mail from a profile's **already-connected** IMAP
 * session via [MailSessionManager]. If the profile isn't connected (the session died on a
 * background process kill), [folderState] returns null and the engine records a skipped
 * poll — no state change. (Headless reconnect from credentials is a deferred follow-up; for
 * now Mail Rules run while the account is connected and the app/watch process is alive.)
 */
@Singleton
class RealMailRulePoller @Inject constructor(
    private val mailSessionManager: MailSessionManager,
) : MailRulePoller {

    private fun resolve(profileId: String): Pair<MailClient, String>? {
        val client = mailSessionManager.clientForProfile(profileId) ?: return null
        val sid = mailSessionManager.getSessionIdForProfile(profileId) ?: return null
        return client to sid
    }

    override suspend fun folderState(profileId: String, folderId: String): FolderUidState? {
        val (client, sid) = resolve(profileId) ?: return null
        return runCatching {
            client.folderUidState(sid, folderId).let { FolderUidState(it.uidValidity, it.uidNext, it.maxUid) }
        }.getOrNull()
    }

    override suspend fun fetchNew(profileId: String, folderId: String, sinceUid: Long, max: Int): List<PolledMessage> {
        val (client, sid) = resolve(profileId) ?: return emptyList()
        return runCatching {
            client.listSince(sid, folderId, sinceUid, max)
                .map { PolledMessage(it.message.id, it.uid, it.message.toMatchable()) }
        }.getOrDefault(emptyList())
    }

    override suspend fun fetchContent(profileId: String, messageId: String): MessageContent? {
        val (client, sid) = resolve(profileId) ?: return null
        return runCatching {
            val raw = client.getMessageRaw(sid, messageId)
            val parsed = MimeParser.parse(raw)
            MessageContent(
                bodyText = parsed.bodyText,
                attachmentNames = parsed.attachments.map { it.filename },
                attachmentMimes = parsed.attachments.map { it.mimeType },
                headers = MimeParser.parseHeaders(raw),
            )
        }.getOrNull()
    }

    private fun MailMessage.toMatchable() = MatchableMessage(
        fromAddress = from?.address ?: "",
        fromName = from?.name ?: "",
        toAddresses = to.map { it.address },
        subject = subject,
        unread = unread,
    )
}
