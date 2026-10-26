package app.cablegram.phone

enum class LibraryTypeFilter { All, Movies, Shows }

/** Where a title can be played from. A title can be in several places at once. */
enum class LibraryLocation { OnPhone, Cloud, Telegram, Unavailable }

enum class WatchedFilter { Any, Unwatched, Started, Watched }

enum class LibrarySort { RecentlyAdded, LastWatched, TitleAZ, Year, SizeLargest }

/**
 * What the library grid shows. Type and watched state are single choice. Different filter groups combine with AND; several
 * values inside one group ([locations], [collectionIds]) combine with OR. An empty set means "no filter".
 */
data class LibraryQuery(
    val type: LibraryTypeFilter = LibraryTypeFilter.All,
    val locations: Set<LibraryLocation> = emptySet(),
    val watched: WatchedFilter = WatchedFilter.Any,
    val collectionIds: Set<String> = emptySet(),
    val search: String = "",
    val sort: LibrarySort = LibrarySort.RecentlyAdded,
) {
    /** Search text is not a filter chip, but "Clear filters" clears it too. */
    val hasFilters: Boolean
        get() = type != LibraryTypeFilter.All || locations.isNotEmpty() || watched != WatchedFilter.Any ||
            collectionIds.isNotEmpty() || search.isNotBlank()
}

/** Same thresholds as [continueWatching]: started after 8 seconds, finished at 92%. */
private const val STARTED_SECONDS = 8
private const val FINISHED_FRACTION = 0.92f

fun isWatched(item: LibraryItem): Boolean = watchFraction(item) >= FINISHED_FRACTION

fun isStarted(item: LibraryItem): Boolean = item.positionSeconds > STARTED_SECONDS && !isWatched(item)

fun itemLocations(item: LibraryItem): Set<LibraryLocation> = buildSet {
    if (phoneStillHoldsVideo(item)) add(LibraryLocation.OnPhone)
    if (item.ownCloudCopy || item.cloudObjectPresent) add(LibraryLocation.Cloud)
    if (item.sourceKind == "telegram" || item.telegramCopy) add(LibraryLocation.Telegram)
    if (storageBadge(item) == StorageBadge.Unavailable) add(LibraryLocation.Unavailable)
}

private fun matchesFilters(item: LibraryItem, query: LibraryQuery): Boolean {
    when (query.type) {
        LibraryTypeFilter.All -> {}
        LibraryTypeFilter.Movies -> if (item.mediaType == "tv") return false
        LibraryTypeFilter.Shows -> if (item.mediaType != "tv") return false
    }
    if (query.locations.isNotEmpty() && itemLocations(item).none { it in query.locations }) return false
    val watchedMatches = when (query.watched) {
        WatchedFilter.Any -> true
        WatchedFilter.Watched -> isWatched(item)
        WatchedFilter.Started -> isStarted(item)
        WatchedFilter.Unwatched -> !isWatched(item) && !isStarted(item)
    }
    if (!watchedMatches) return false
    if (query.collectionIds.isNotEmpty() && query.collectionIds.none { itemMatchesCollection(item, it) }) return false
    return true
}

private fun LibraryEntry.items(): List<LibraryItem> = when (this) {
    is LibraryEntry.Film -> listOf(item)
    is LibraryEntry.Series -> episodes
}

/**
 * Filter, group episodes into series, then sort. A series holds only the episodes that matched, so "Unwatched" shows a
 * show with its unwatched episodes. Ties fall back to title, then key, so the order never flickers.
 */
fun applyLibraryQuery(items: List<LibraryItem>, query: LibraryQuery): List<LibraryEntry> {
    val matching = searchLibrary(items.filter { matchesFilters(it, query) }, query.search)
    val tiebreak = compareBy<LibraryEntry>({ it.title.lowercase() }, { it.key })
    val order: Comparator<LibraryEntry> = when (query.sort) {
        // Newest first; a series counts as its newest episode.
        LibrarySort.RecentlyAdded -> compareByDescending<LibraryEntry> { entry -> entry.items().maxOf { it.importedAt } }
        // Most recently watched first; never-watched titles go last.
        LibrarySort.LastWatched -> compareByDescending<LibraryEntry> { entry -> entry.items().maxOf { it.lastWatchedAt.orEmpty() } }
        LibrarySort.TitleAZ -> compareBy<LibraryEntry> { it.title.lowercase() }
        // Newest year first, unknown last; a series counts as its premiere (earliest episode year).
        LibrarySort.Year -> compareByDescending<LibraryEntry> { entry -> entry.items().mapNotNull { it.year }.minOrNull() ?: Int.MIN_VALUE }
        // Largest first; a series counts as the total of its episodes.
        LibrarySort.SizeLargest -> compareByDescending<LibraryEntry> { entry -> entry.items().sumOf { it.fileSizeBytes ?: 0L } }
    }
    return groupIntoEntries(matching).sortedWith(order.then(tiebreak))
}
