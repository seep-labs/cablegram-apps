package app.cablegram.discovery

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Switches from Telegram → Advanced settings (US5). Both are off until the owner turns them on. */
data class DiscoverySwitches(
    val includePersonalChats: Boolean = false,
    val includeSavedMessages: Boolean = false,
    /** Chats with bots (often download bots that send videos into the chat). Switched on from the scan results. */
    val includeBotChats: Boolean = false,
) {
    /** Whether a scan looks at anything beyond channels and groups. */
    val any get() = includePersonalChats || includeSavedMessages || includeBotChats
}

/**
 * Phone-only video discovery (spec 008). Every rule that keeps the promise lives here, not in the screens:
 * a live [ScanPermit] for any scan, only chosen chats for any read, and a throttle for every request.
 */
class TelegramDiscovery(
    private val transport: DiscoveryTransport,
    private val gate: DiscoveryGate,
    private val throttle: RequestThrottle,
    private val libraryChatId: () -> Long?,
    private val store: DiscoveryStore,
) {
    /** Messages [VideoPager] returned in this process; only these may be forwarded (FR-010). */
    private val served = java.util.concurrent.ConcurrentHashMap.newKeySet<Pair<Long, Long>>()

    /** Channels and groups (FR-005), plus personal chats and Saved Messages only if their switch is on. */
    fun discoveryChats(permit: ScanPermit, switches: DiscoverySwitches): Flow<DiscoveryChat> = flow {
        gate.requirePermit(permit)
        // getMe and getChat read TDLib's local database, so only the network calls are throttled (FR-007).
        val me = transport.myUserId()
        val ids = throttle.run { transport.chatIds() }
        for (id in ids) {
            gate.requirePermit(permit)
            if (id == libraryChatId()) continue
            val chat = transport.chat(id) ?: continue
            val kind = classify(chat, me, switches) ?: continue
            permit.listed += id
            emit(DiscoveryChat(DiscoveryChatId(id), kind, chat.title, chat.protectedContent, chat.lastMessageId))
        }
    }

    /**
     * The long videos among a chat's newest [RECENT_WINDOW] videos and files (FR-004, FR-006). Videos shorter than the owner's
     * minimum length are left out. A document has no length, so a video-type document counts when it is large
     * (about a film rather than a clip); files make the total "about". Each page is one throttled request (FR-007).
     */
    suspend fun chatVideoCount(permit: ScanPermit, chatId: DiscoveryChatId): VideoCount {
        gate.requirePermit(permit)
        if (chatId.raw !in permit.listed) throw DiscoveryRefused("Chat was not listed by this scan")
        val minSeconds = store.minVideoSeconds()
        var from = 0L
        var seen = 0
        var videos = 0
        var files = 0
        while (seen < RECENT_WINDOW) {
            gate.requirePermit(permit)
            val page = throttle.run { transport.recent(chatId.raw, from, RECENT_WINDOW - seen) }
            if (page.messages == 0) break
            seen += page.messages
            for (v in page.candidates) {
                if (!v.isFile) { if (v.durationSeconds >= minSeconds) videos++ }
                else if (v.looksLikeVideo() && v.sizeBytes >= documentMinBytes(minSeconds)) files++
            }
            if (page.nextFromMessageId == 0L || page.nextFromMessageId == from) break
            from = page.nextFromMessageId
        }
        return VideoCount(videos, files)
    }

    /** Whether a chosen chat can still be read: the owner is still a member of it. Looks at the chosen chat only. */
    suspend fun isReadable(chatId: Long): Boolean {
        gate.requireChosen(chatId)
        return transport.chat(chatId)?.isMember == true
    }

    /** A pager over the chosen chats only. Refuses any chat outside the stored choice before touching Telegram (FR-009). */
    fun videoPager(chatIds: Set<DiscoveryChatId>): VideoPager {
        chatIds.forEach { gate.requireChosen(it.raw) }
        return VideoPager(chatIds.map { it.raw }, store.minVideoSeconds())
    }

    inner class VideoPager internal constructor(chats: List<Long>, private val minSeconds: Int) {
        private val sources = chats.flatMap { listOf(Source(it, false), Source(it, true)) }
        private val protectedChats = mutableMapOf<Long, Boolean>()

        val exhausted get() = sources.all { it.done && it.buffer.isEmpty() }

        /** The next [limit] videos, newest first across all chosen chats. An empty page ends a source (a zero cursor is not reliable). */
        suspend fun next(limit: Int = 50): List<DiscoveryVideo> {
            for (source in sources) while (!source.done && source.buffer.size < limit) fill(source, limit)
            val out = ArrayList<DiscoveryVideo>(limit)
            while (out.size < limit) {
                val best = sources.filter { it.buffer.isNotEmpty() }.maxByOrNull { it.buffer.first().date } ?: break
                out += best.buffer.removeFirst()
            }
            out.forEach { served += it.chatId.raw to it.messageId }
            return out
        }

        private suspend fun fill(source: Source, limit: Int) {
            gate.requireChosen(source.chatId)
            val protectedChat = protectedChats.getOrPut(source.chatId) { throttle.run { transport.chat(source.chatId) }?.protectedContent ?: true }
            val page = throttle.run { transport.search(source.chatId, source.files, source.from, limit) }
            if (page.videos.isEmpty() || page.nextFromMessageId == source.from) source.done = true
            source.from = page.nextFromMessageId
            page.videos.filter { if (source.files) it.looksLikeVideo() && it.sizeBytes >= documentMinBytes(minSeconds) else it.durationSeconds >= minSeconds }.forEach {
                source.buffer += DiscoveryVideo(
                    DiscoveryChatId(it.chatId), it.messageId, it.uniqueId, it.caption.ifBlank { it.name },
                    it.durationSeconds, it.sizeBytes, it.date, it.thumbnailFileId, protectedChat || !it.canBeSaved,
                )
            }
        }
    }

    /**
     * Forwards the selected videos into the library channel and nowhere else (FR-010). There is no destination
     * parameter. Protected videos are refused here, not only in the screen.
     */
    suspend fun forwardToLibrary(selection: List<DiscoveryVideo>): List<ForwardOutcome> {
        val library = libraryChatId() ?: throw DiscoveryRefused("No library channel")
        selection.forEach {
            gate.requireChosen(it.chatId.raw)
            if ((it.chatId.raw to it.messageId) !in served) throw DiscoveryRefused("Message was not offered by the picker")
        }
        val outcomes = mutableListOf<ForwardOutcome>()
        val (blocked, allowed) = selection.partition { it.protectedContent }
        blocked.forEach { outcomes += ForwardOutcome.Failed(it, "This chat doesn't allow saving") }
        for ((chat, videos) in allowed.groupBy { it.chatId.raw }) {
            for (batch in videos.chunked(FORWARD_BATCH)) {
                val byMessage = batch.associateBy { it.messageId }
                val results = try {
                    throttle.run { transport.forward(library, chat, batch.map { it.messageId }.toLongArray()) }
                } catch (e: app.cablegram.telegram.TgException) {
                    batch.forEach { outcomes += ForwardOutcome.Failed(it, e.name) }
                    continue
                }
                for (r in results) when (r) {
                    is ForwardResult.Sent -> byMessage[r.sourceMessageId]?.let { outcomes += ForwardOutcome.Added(it) }
                    is ForwardResult.Failed -> byMessage[r.sourceMessageId]?.let { outcomes += ForwardOutcome.Failed(it, r.reason) }
                }
            }
        }
        return outcomes
    }

    /** Downloads a thumbnail and records it so [purgeChat] can delete exactly what was fetched (FR-008). */
    suspend fun thumbnail(video: DiscoveryVideo): String? {
        val file = video.thumbnailFileId ?: return null
        gate.requireChosen(video.chatId.raw)
        store.recordThumbnail(video.chatId.raw, file)
        return throttle.run { transport.downloadThumbnail(file) }.takeIf { it.isNotEmpty() }
    }

    /** Deletes every cached thumbnail of a chat now, then forgets the chat (untick, switch off, left chat). */
    suspend fun purgeChat(chatId: Long) {
        store.thumbnailsOf(chatId).forEach { runCatching { transport.deleteFile(it) } }
        runCatching { transport.optimizeStorage(longArrayOf(chatId)) }
        store.dropChat(chatId)
    }

    private fun TransportVideo.looksLikeVideo() =
        mimeType.startsWith("video/") || VIDEO_EXTENSIONS.any { name.lowercase().endsWith(".$it") }

    private suspend fun classify(chat: TransportChat, me: Long, switches: DiscoverySwitches): ChatKind? = when (chat.type) {
        TransportChatType.Channel -> ChatKind.Channel
        TransportChatType.Group -> ChatKind.Group
        TransportChatType.Secret -> null
        TransportChatType.Private -> when {
            chat.userId == me -> ChatKind.SavedMessages.takeIf { switches.includeSavedMessages }
            chat.userId == TELEGRAM_SERVICE_USER -> null
            // With both switches off a one-to-one chat is never looked at, not even to ask whether it is a bot.
            !switches.includePersonalChats && !switches.includeBotChats -> null
            // getUser reads TDLib's local database (an offline method), so it needs no throttle.
            transport.isBot(chat.userId) -> ChatKind.Bot.takeIf { switches.includeBotChats }
            else -> ChatKind.Personal.takeIf { switches.includePersonalChats }
        }
    }

    private companion object {
        const val TELEGRAM_SERVICE_USER = 777000L
        const val FORWARD_BATCH = 100
        /** The scan looks at this many of a chat's newest videos and files. */
        const val RECENT_WINDOW = 100
        /** A video-type document at least this big counts as a long video; with "any length" every one counts. */
        const val DOCUMENT_MIN_BYTES = 100L * 1024 * 1024
        fun documentMinBytes(minSeconds: Int): Long = if (minSeconds <= 0) 0L else DOCUMENT_MIN_BYTES
        val VIDEO_EXTENSIONS = listOf("mp4", "mkv", "mov", "avi", "webm", "m4v", "ts", "m2ts", "wmv", "flv", "3gp")
    }
}

private class Source(val chatId: Long, val files: Boolean, var from: Long = 0, var done: Boolean = false, val buffer: ArrayDeque<DiscoveryVideo> = ArrayDeque())
