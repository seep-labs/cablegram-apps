package app.cablegram.discovery

// Phone only (spec 008). Never copy this package into apps/android-tv: scripts/check-telegram-sync.mjs and
// the TV's TelegramApiSurfaceTest both fail if it appears there.

/** A Telegram chat id other than the library channel. Its text form hides the number so it cannot reach a log. */
@JvmInline
value class DiscoveryChatId(val raw: Long) {
    override fun toString() = "chat#redacted"
}

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
