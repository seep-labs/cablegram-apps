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
data class DiscoveryChat(val id: DiscoveryChatId, val kind: ChatKind, val title: String, val protectedContent: Boolean)

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
