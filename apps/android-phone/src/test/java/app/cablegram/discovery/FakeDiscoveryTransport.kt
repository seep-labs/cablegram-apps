package app.cablegram.discovery

/** Records every call so tests can prove what discovery did and did not touch. */
class FakeDiscoveryTransport(
    private val chats: List<TransportChat> = emptyList(),
    private val me: Long = 1L,
    private val bots: Set<Long> = emptySet(),
    private val counts: Map<Pair<Long, Boolean>, Int> = emptyMap(),
    private val videos: List<TransportVideo> = emptyList(),
    private val failForward: Set<Long> = emptySet(),
) : DiscoveryTransport {
    val calls = mutableListOf<String>()
    val forwards = mutableListOf<Triple<Long, Long, List<Long>>>()
    val deletedFiles = mutableListOf<Int>()
    val optimized = mutableListOf<List<Long>>()

    override suspend fun myUserId(): Long = me.also { calls += "me" }
    override suspend fun chatIds(): List<Long> = chats.map { it.id }.also { calls += "chatIds" }
    override suspend fun chat(chatId: Long): TransportChat? = chats.firstOrNull { it.id == chatId }.also { calls += "chat" }
    override suspend fun isBot(userId: Long): Boolean = (userId in bots).also { calls += "isBot" }
    override suspend fun messageCount(chatId: Long, files: Boolean): Int = (counts[chatId to files] ?: 0).also { calls += "count:$chatId:$files" }

    override suspend fun search(chatId: Long, files: Boolean, fromMessageId: Long, limit: Int): TransportPage {
        calls += "search:$chatId:$files"
        val all = videos.filter { it.chatId == chatId && it.isFile == files && (fromMessageId == 0L || it.messageId < fromMessageId) }
            .sortedByDescending { it.messageId }.take(limit)
        // Like Telegram, the cursor is not zero even on the last page.
        return TransportPage(all, all.lastOrNull()?.messageId ?: fromMessageId.coerceAtLeast(1))
    }

    override suspend fun forward(toChatId: Long, fromChatId: Long, messageIds: LongArray): List<ForwardResult> {
        calls += "forward"
        forwards += Triple(toChatId, fromChatId, messageIds.toList())
        return messageIds.map { if (it in failForward) ForwardResult.Failed(it, "boom") else ForwardResult.Sent(it) }
    }

    override suspend fun downloadThumbnail(fileId: Int): Long = 10L.also { calls += "thumb:$fileId" }
    override suspend fun deleteFile(fileId: Int) { deletedFiles += fileId }
    override suspend fun optimizeStorage(chatIds: LongArray) { optimized += chatIds.toList() }
}

class MemoryDiscoveryStorage(var text: String? = null) : DiscoveryStorage {
    override fun read() = text
    override fun write(json: String) { text = json }
    override fun clear() { text = null }
}
