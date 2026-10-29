package app.cablegram.phone

/** Why a title cannot play from this phone right now. Drives the "!" on posters and the Clean up dialog. */
enum class LibraryProblem(val needsConnection: Boolean, val identifier: String) {
    /** The source is gone (the file was deleted, or the other phone no longer has it). */
    Unavailable(false, "unavailable"),
    /** The file was never on this phone, or was freed from it, and nothing else holds it for this phone. */
    NotOnPhone(false, "not_on_phone"),
    /** A Telegram title, but this phone has no Telegram session of its own. */
    TelegramNotConnected(true, "telegram"),
    /** The title lives only in the owner's cloud storage, and that storage is disconnected. */
    CloudNotConnected(true, "cloud"),
}

/** What this phone is connected to: a Telegram title or a cloud-only title only plays from here while its connection is up. */
data class LibraryConnections(val telegramNeedsConnection: Boolean = false, val cloudNeedsConnection: Boolean = false)

fun libraryProblem(item: LibraryItem, connections: LibraryConnections): LibraryProblem? = when (storageBadge(item)) {
    StorageBadge.Unavailable -> LibraryProblem.Unavailable
    StorageBadge.NotOnPhone -> LibraryProblem.NotOnPhone
    StorageBadge.Telegram -> LibraryProblem.TelegramNotConnected.takeIf { connections.telegramNeedsConnection }
    // Only the owner's own storage can be "disconnected"; a cloud-only title has no copy on the phone to fall back on.
    StorageBadge.Cloud -> LibraryProblem.CloudNotConnected.takeIf {
        connections.cloudNeedsConnection && (item.ownCloudCopy || item.ownCloudSourceId != null)
    }
    else -> null
}

/** The titles of one problem, for the Clean up dialog. */
data class CleanupGroup(val problem: LibraryProblem, val items: List<LibraryItem>)

/** Titles that cannot play from this phone, grouped by why (empty groups left out), in the order the dialog lists them. */
fun cleanupGroups(items: List<LibraryItem>, connections: LibraryConnections): List<CleanupGroup> {
    val byProblem = items.mapNotNull { item -> libraryProblem(item, connections)?.let { it to item } }.groupBy({ it.first }, { it.second })
    return LibraryProblem.entries.mapNotNull { problem -> byProblem[problem]?.let { CleanupGroup(problem, it) } }
}

fun libraryProblemLabel(problem: LibraryProblem, resources: android.content.res.Resources): String = resources.getString(
    when (problem) {
        LibraryProblem.Unavailable -> R.string.ui_badge_unavailable
        LibraryProblem.NotOnPhone -> R.string.ui_badge_not_on_phone
        LibraryProblem.TelegramNotConnected -> R.string.library_problem_telegram
        LibraryProblem.CloudNotConnected -> R.string.library_problem_cloud
    },
)
