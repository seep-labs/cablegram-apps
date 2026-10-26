package app.cablegram.phone

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The filters and sort the library remembers on this phone (not synced). The search text is not kept: a new launch starts
 * with an empty search box. Stored as JSON, in the same shape as the iPhone app, so unknown or renamed values fall back to
 * the defaults instead of failing.
 */
@Serializable
private data class StoredLibraryQuery(
    val type: String = "",
    val locations: List<String> = emptyList(),
    val watched: String = "",
    val collections: List<String> = emptyList(),
    val sort: String = "",
)

private val storedJson = Json { ignoreUnknownKeys = true }

fun encodeLibraryQuery(query: LibraryQuery): String = storedJson.encodeToString(
    StoredLibraryQuery.serializer(),
    StoredLibraryQuery(
        type = query.type.name, locations = query.locations.map { it.name }.sorted(), watched = query.watched.name,
        collections = query.collectionIds.sorted(), sort = query.sort.name,
    ),
)

fun decodeLibraryQuery(text: String?): LibraryQuery {
    val stored = runCatching { storedJson.decodeFromString(StoredLibraryQuery.serializer(), text.orEmpty()) }.getOrNull() ?: return LibraryQuery()
    return LibraryQuery(
        type = LibraryTypeFilter.entries.firstOrNull { it.name == stored.type } ?: LibraryTypeFilter.All,
        locations = stored.locations.mapNotNull { name -> LibraryLocation.entries.firstOrNull { it.name == name } }.toSet(),
        watched = WatchedFilter.entries.firstOrNull { it.name == stored.watched } ?: WatchedFilter.Any,
        collectionIds = stored.collections.toSet(),
        sort = LibrarySort.entries.firstOrNull { it.name == stored.sort } ?: LibrarySort.RecentlyAdded,
    )
}

/** A collection deleted since the filter was saved must not leave an empty library behind. */
fun LibraryQuery.withoutUnknownCollections(known: Set<String>): LibraryQuery =
    if (collectionIds.all { it in known }) this else copy(collectionIds = collectionIds.filterTo(mutableSetOf()) { it in known })

/** How many active filters are hidden in the More sheet; "Unwatched" and "In progress" have chips of their own. */
val LibraryQuery.moreFilterCount: Int
    get() = locations.size + (if (watched == WatchedFilter.Watched) 1 else 0) + collectionIds.size

object LibraryQueryStore {
    private const val PREFS = "cablegram_library_query"
    private const val KEY = "query"

    fun load(context: Context): LibraryQuery =
        decodeLibraryQuery(runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) }.getOrNull())

    fun save(context: Context, query: LibraryQuery) {
        runCatching { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, encodeLibraryQuery(query)).apply() }
    }
}
