package app.cablegram.discovery

/** Records every call so tests can prove what discovery did and did not touch. */
class FakeDiscoveryTransport(
    private val chats: List<TransportChat> = emptyList(),
    private val me: Long = 1L,
    private val bots: Set<Long> = emptySet(),
    private val counts: Map<Pair<Long, Boolean>, Int> = emptyMap(),
) : DiscoveryTransport {
    val calls = mutableListOf<String>()

    override suspend fun myUserId(): Long = me.also { calls += "me" }
    override suspend fun chatIds(): List<Long> = chats.map { it.id }.also { calls += "chatIds" }
    override suspend fun chat(chatId: Long): TransportChat? = chats.firstOrNull { it.id == chatId }.also { calls += "chat" }
    override suspend fun isBot(userId: Long): Boolean = (userId in bots).also { calls += "isBot" }
    override suspend fun messageCount(chatId: Long, files: Boolean): Int = (counts[chatId to files] ?: 0).also { calls += "count:$chatId:$files" }
    override suspend fun search(chatId: Long, files: Boolean, fromMessageId: Long, limit: Int): TransportPage = error("not yet")
    override suspend fun forward(toChatId: Long, fromChatId: Long, messageIds: LongArray): List<ForwardResult> = error("not yet")
    override suspend fun downloadThumbnail(fileId: Int): Long = 0
    override suspend fun deleteFile(fileId: Int) = Unit
    override suspend fun optimizeStorage(chatIds: LongArray) = Unit
}
