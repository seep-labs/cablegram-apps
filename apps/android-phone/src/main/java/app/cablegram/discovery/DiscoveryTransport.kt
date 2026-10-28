package app.cablegram.discovery

// Phone only (spec 008). The only TDLib calls discovery may make; TelegramApiSurfaceTest pins this list.

/** What discovery learns about one chat from TDLib: type, title, protection. No last message, no members. */
data class TransportChat(
    val id: Long,
    val type: TransportChatType,
    /** The other user of a one-to-one chat; 0 otherwise. */
    val userId: Long,
    val title: String,
    val protectedContent: Boolean,
    /** False once the owner left, was banned from or lost access to a channel or group. */
    val isMember: Boolean = true,
    /** Id of the chat's newest message from the local chat list; 0 when there is none. */
    val lastMessageId: Long = 0,
)

enum class TransportChatType { Channel, Group, Private, Secret }

/** One video, or one file that may be a video, found in a chosen chat. */
data class TransportVideo(
    val chatId: Long,
    val messageId: Long,
    /** Telegram's remote unique id; the library's `tgfile:` key is built from it. */
    val uniqueId: String,
    val name: String,
    val caption: String,
    val durationSeconds: Int,
    val sizeBytes: Long,
    val date: Int,
    val isFile: Boolean,
    val mimeType: String,
    val thumbnailFileId: Int?,
    val canBeSaved: Boolean,
)

data class TransportPage(val videos: List<TransportVideo>, val nextFromMessageId: Long)

/**
 * One page of a chat's newest messages, newest first: how many messages it held, which of them are videos or
 * documents, and where the next page starts (0 when the chat has no older message).
 */
data class RecentPage(val messages: Int, val candidates: List<TransportVideo>, val nextFromMessageId: Long)

sealed interface ForwardResult {
    data class Sent(val sourceMessageId: Long) : ForwardResult
    data class Failed(val sourceMessageId: Long, val reason: String) : ForwardResult
}

interface DiscoveryTransport {
    suspend fun myUserId(): Long
    /** Chat ids of the main list and the archive, loaded as far as Telegram has them. */
    suspend fun chatIds(): List<Long>
    suspend fun chat(chatId: Long): TransportChat?
    suspend fun isBot(userId: Long): Boolean
    /** The newest [limit] messages of a chat from [fromMessageId] (0 = newest), for the scan's count of recent videos. */
    suspend fun recent(chatId: Long, fromMessageId: Long, limit: Int): RecentPage
    suspend fun search(chatId: Long, files: Boolean, fromMessageId: Long, limit: Int): TransportPage
    suspend fun forward(toChatId: Long, fromChatId: Long, messageIds: LongArray): List<ForwardResult>
    /** Returns the local path of the downloaded thumbnail, or an empty string when Telegram has none. */
    suspend fun downloadThumbnail(fileId: Int): String
    suspend fun deleteFile(fileId: Int)
    suspend fun optimizeStorage(chatIds: LongArray)
}
