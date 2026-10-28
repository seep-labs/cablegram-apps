package app.cablegram.discovery

import app.cablegram.telegram.TgException
import dev.g000sha256.tdl.TdlClient
import dev.g000sha256.tdl.TdlResult
import dev.g000sha256.tdl.dto.ChatListArchive
import dev.g000sha256.tdl.dto.ChatListMain
import dev.g000sha256.tdl.dto.ChatTypeBasicGroup
import dev.g000sha256.tdl.dto.ChatTypePrivate
import dev.g000sha256.tdl.dto.ChatTypeSecret
import dev.g000sha256.tdl.dto.ChatTypeSupergroup
import dev.g000sha256.tdl.dto.FileType
import dev.g000sha256.tdl.dto.FileTypePhoto
import dev.g000sha256.tdl.dto.FileTypeThumbnail
import dev.g000sha256.tdl.dto.Message
import dev.g000sha256.tdl.dto.MessageDocument
import dev.g000sha256.tdl.dto.MessageVideo
import dev.g000sha256.tdl.dto.SearchMessagesFilter
import dev.g000sha256.tdl.dto.SearchMessagesFilterDocument
import dev.g000sha256.tdl.dto.SearchMessagesFilterVideo
import dev.g000sha256.tdl.dto.UserTypeBot
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/** [DiscoveryTransport] over the phone's one TDLib client (the same one [app.cablegram.telegram.TdlibTelegramApi] uses). */
class TdlibDiscoveryTransport(private val client: TdlClient) : DiscoveryTransport {
    override suspend fun myUserId(): Long = client.getMe().ok().id

    override suspend fun chatIds(): List<Long> {
        val ids = LinkedHashSet<Long>()
        for (list in listOf(ChatListMain(), ChatListArchive())) {
            // loadChats answers 404 once every chat of the list is loaded.
            while (true) {
                when (val r = client.loadChats(list, 100)) {
                    is TdlResult.Success -> Unit
                    is TdlResult.Failure -> if (r.code == 404) break else throw TgException(r.code, r.message)
                }
            }
            ids += client.getChats(list, 10_000).ok().chatIds.toList()
        }
        return ids.toList()
    }

    override suspend fun chat(chatId: Long): TransportChat? {
        val chat = (client.getChat(chatId) as? TdlResult.Success)?.result ?: return null
        val type = chat.type
        val kind = when (type) {
            is ChatTypeSupergroup -> if (type.isChannel) TransportChatType.Channel else TransportChatType.Group
            is ChatTypeBasicGroup -> TransportChatType.Group
            is ChatTypePrivate -> TransportChatType.Private
            is ChatTypeSecret -> TransportChatType.Secret
            else -> return null
        }
        val userId = when (type) {
            is ChatTypePrivate -> type.userId
            is ChatTypeSecret -> type.userId
            else -> 0L
        }
        return TransportChat(chat.id, kind, userId, chat.title, chat.hasProtectedContent, isMember(type), chat.lastMessage?.id ?: 0L)
    }

    /** Left, banned and restricted-out members can no longer read a chat as a member (spec 008 edge case). */
    private suspend fun isMember(type: dev.g000sha256.tdl.dto.ChatType): Boolean {
        val status = when (type) {
            is ChatTypeSupergroup -> (client.getSupergroup(type.supergroupId) as? TdlResult.Success)?.result?.status
            is ChatTypeBasicGroup -> (client.getBasicGroup(type.basicGroupId) as? TdlResult.Success)?.result?.status
            else -> return true
        } ?: return true
        return when (status) {
            is dev.g000sha256.tdl.dto.ChatMemberStatusLeft, is dev.g000sha256.tdl.dto.ChatMemberStatusBanned -> false
            is dev.g000sha256.tdl.dto.ChatMemberStatusRestricted -> status.isMember
            else -> true
        }
    }

    override suspend fun isBot(userId: Long): Boolean =
        (client.getUser(userId) as? TdlResult.Success)?.result?.type is UserTypeBot

    /** Calls and messages seen by [recent], so a debug scan can report how many requests a chat really costs. */
    val recentCalls = java.util.concurrent.atomic.AtomicInteger()
    val recentMessages = java.util.concurrent.atomic.AtomicInteger()

    override suspend fun recent(chatId: Long, fromMessageId: Long, limit: Int): RecentPage {
        val messages = client.getChatHistory(chatId, fromMessageId, 0, limit, false).ok().messages.filterNotNull()
        recentCalls.incrementAndGet(); recentMessages.addAndGet(messages.size)
        return RecentPage(messages.size, messages.mapNotNull { toVideo(it) }, messages.lastOrNull()?.id ?: 0L)
    }

    override suspend fun search(chatId: Long, files: Boolean, fromMessageId: Long, limit: Int): TransportPage {
        val found = client.searchChatMessages(chatId, null, "", null, fromMessageId, 0, limit, filter(files)).ok()
        val videos = found.messages.mapNotNull { toVideo(it) }
        return TransportPage(videos, found.nextFromMessageId)
    }

    override suspend fun forward(toChatId: Long, fromChatId: Long, messageIds: LongArray): List<ForwardResult> = coroutineScope {
        // Telegram wants ids in strictly increasing order; searches return newest first.
        val ordered = messageIds.sortedArray()
        // Updates are hot: listen before sending, so a fast result cannot be missed (as uploadVideo does).
        val outcomes = ConcurrentHashMap<Long, ForwardResult>()
        val pending = ConcurrentHashMap<Long, Long>()
        val done = CompletableDeferred<Unit>()
        fun finish() { if (pending.isNotEmpty() && outcomes.keys.containsAll(pending.keys)) done.complete(Unit) }
        val listeners = listOf(
            launch(start = CoroutineStart.UNDISPATCHED) {
                client.messageSendSucceededUpdates.collect { outcomes[it.oldMessageId] = ForwardResult.Sent(pending[it.oldMessageId] ?: 0); finish() }
            },
            launch(start = CoroutineStart.UNDISPATCHED) {
                client.messageSendFailedUpdates.collect {
                    outcomes[it.oldMessageId] = ForwardResult.Failed(pending[it.oldMessageId] ?: 0, it.error.message); finish()
                }
            },
        )
        try {
            val sent = client.forwardMessages(toChatId, null, fromChatId, ordered, null, false, false).ok().messages
            val results = mutableListOf<ForwardResult>()
            sent.forEachIndexed { i, m ->
                val source = ordered.getOrNull(i) ?: 0L
                if (m == null) results += ForwardResult.Failed(source, "not forwarded") else pending[m.id] = source
            }
            if (pending.isNotEmpty()) { finish(); done.await() }
            results + pending.map { (temporary, source) -> outcomes.getValue(temporary).withSource(source) }
        } finally {
            listeners.forEach { it.cancel() }
        }
    }

    override suspend fun downloadThumbnail(fileId: Int): String =
        client.downloadFile(fileId, 1, 0, 0, true).ok().local.let { if (it.isDownloadingCompleted) it.path else "" }

    override suspend fun deleteFile(fileId: Int) {
        client.deleteFile(fileId).ok()
    }

    override suspend fun optimizeStorage(chatIds: LongArray) {
        val types: Array<FileType> = arrayOf(FileTypeThumbnail(), FileTypePhoto())
        client.optimizeStorage(0, -1, -1, 0, types, chatIds, LongArray(0), false, 1).ok()
    }

    private fun filter(files: Boolean): SearchMessagesFilter = if (files) SearchMessagesFilterDocument() else SearchMessagesFilterVideo()

    private fun ForwardResult.withSource(source: Long) = when (this) {
        is ForwardResult.Sent -> copy(sourceMessageId = source)
        is ForwardResult.Failed -> copy(sourceMessageId = source)
    }

    private fun toVideo(m: Message): TransportVideo? = when (val c = m.content) {
        is MessageVideo -> TransportVideo(
            m.chatId, m.id, c.video.video.remote.uniqueId, c.video.fileName, c.caption.text, c.video.duration,
            c.video.video.size, m.date, false, c.video.mimeType, c.video.thumbnail?.file?.id, m.canBeSaved,
        )
        is MessageDocument -> TransportVideo(
            m.chatId, m.id, c.document.document.remote.uniqueId, c.document.fileName, c.caption.text, 0,
            c.document.document.size, m.date, true, c.document.mimeType, c.document.thumbnail?.file?.id, m.canBeSaved,
        )
        else -> null
    }

    private companion object {
        fun <T> TdlResult<T>.ok(): T = when (this) {
            is TdlResult.Success -> result
            is TdlResult.Failure -> throw TgException(code, message)
        }
    }
}
