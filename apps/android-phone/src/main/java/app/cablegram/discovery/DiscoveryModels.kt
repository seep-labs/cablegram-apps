package app.cablegram.discovery

// Phone only (spec 008). Never copy this package into apps/android-tv: scripts/check-telegram-sync.mjs and
// the TV's TelegramApiSurfaceTest both fail if it appears there.

/** A Telegram chat id other than the library channel. Its text form hides the number so it cannot reach a log. */
@JvmInline
value class DiscoveryChatId(val raw: Long) {
    override fun toString() = "chat#redacted"
}

@kotlinx.serialization.Serializable
enum class ChatKind { Channel, Group, Personal, SavedMessages }

/** A chat the scan may list. Deliberately has no last message, photo bytes or members. */
data class DiscoveryChat(
    val id: DiscoveryChatId,
    val kind: ChatKind,
    val title: String,
    val protectedContent: Boolean,
    /** Id of the chat's newest message, read from the chat list (no request); 0 when unknown. Tells a rescan the chat is quiet. */
    val lastMessageId: Long = 0,
)

/** A rescan does not read a chat again when its newest message is the one seen last time (nothing new to count). */
fun isUnchangedSinceLastLook(row: StoredScanRow?, chat: DiscoveryChat): Boolean =
    row != null && row.lastMessageId != 0L && row.lastMessageId == chat.lastMessageId

/**
 * Requests per second the scan may send to Telegram, all chats together. A 302-chat account ran at 10 per second
 * without a single FLOOD_WAIT (2026-10-28); if Telegram does ask us to wait, the scan pauses and says so.
 */
const val SCAN_REQUESTS_PER_SECOND = 10

/**
 * How many chats a scan reads at once. Each chat's recent videos and files take a round trip or two, so one chat after the
 * other would be slow; the throttle still keeps every request within 5 per second (FR-007).
 */
const val SCAN_PARALLEL_CHATS = 16

/** The shortest video the scan and the picker look for, until the owner changes it in Advanced settings. */
const val DEFAULT_MIN_VIDEO_SECONDS = 600

/** Counts for one chat. [approximate] is true when files are included, because a file is only known to be a video once listed. */
data class VideoCount(val videos: Int, val files: Int) {
    val approximate get() = files > 0
    val total get() = videos + files
}

/** Raised when a call would break the promise: no permission, a chat outside the choice, or a protected chat. */
class DiscoveryRefused(message: String) : Exception(message)

/** One video in a chosen chat, ready for the picker. */
data class DiscoveryVideo(
    val chatId: DiscoveryChatId,
    val messageId: Long,
    val uniqueId: String,
    val title: String,
    val durationSeconds: Int,
    val sizeBytes: Long,
    val date: Int,
    val thumbnailFileId: Int?,
    /** True when the chat or the message forbids saving; such a video cannot be selected (FR-010). */
    val protectedContent: Boolean,
)

sealed interface ForwardOutcome {
    val video: DiscoveryVideo
    data class Added(override val video: DiscoveryVideo) : ForwardOutcome
    data class Failed(override val video: DiscoveryVideo, val reason: String) : ForwardOutcome
}
