package app.cablegram.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same cases, with the same fixtures, as LibraryQueryTests.swift on iOS. */
class LibraryQueryTest {
    private fun film(
        id: String, year: Int?, added: String, size: Long, position: Int = 0, watchedAt: String? = null,
        copied: Boolean = true, kind: String = "phone_local", cloud: Boolean = false, available: Boolean? = null,
        collections: List<String> = emptyList(),
    ) = LibraryItem(
        id = id, title = id, filename = "$id.mkv", durationSeconds = 9_000, fileSizeBytes = size, year = year, importedAt = added,
        copied = copied, sourceKind = kind, cloudObjectPresent = cloud, sourceAvailable = available, positionSeconds = position,
        lastWatchedAt = watchedAt, collectionIds = collections,
    )

    private fun episode(id: String, episode: Int, added: String, position: Int = 0, watchedAt: String? = null) = LibraryItem(
        id = id, title = "Dark", filename = "$id.mkv", durationSeconds = 3_000, fileSizeBytes = 10, year = 2017, mediaType = "tv",
        importedAt = added, tmdbId = 70523, seasonNumber = 1, episodeNumber = episode, positionSeconds = position, lastWatchedAt = watchedAt,
    )

    private val library = listOf(
        film("Dune", 2021, "2026-01-05T00:00:00Z", 300, collections = listOf("kids")),
        film("Heat", 1995, "2026-01-03T00:00:00Z", 200, position = 5_000, watchedAt = "2026-02-02T00:00:00Z", collections = listOf("fav")),
        film("Alien", 1979, "2026-01-01T00:00:00Z", 100, position = 8_900, watchedAt = "2026-02-01T00:00:00Z"),
        film("Zodiac", 2007, "2026-01-04T00:00:00Z", 400, copied = false, kind = "telegram", collections = listOf("kids")),
        film("Brazil", null, "2026-01-06T00:00:00Z", 50, copied = false, cloud = true, available = false),
        film("Ghost", null, "2026-01-08T00:00:00Z", 20, copied = false, available = false),
        episode("dark1", 1, "2026-01-02T00:00:00Z"),
        episode("dark2", 2, "2026-01-07T00:00:00Z", position = 600, watchedAt = "2026-03-01T00:00:00Z"),
        episode("dark3", 3, "2026-01-02T12:00:00Z", position = 2_900, watchedAt = "2026-01-15T00:00:00Z"),
    )

    private fun keys(query: LibraryQuery) = applyLibraryQuery(library, query).map { it.title }

    private fun darkEpisodes(query: LibraryQuery) =
        (applyLibraryQuery(library, query).single { it.title == "Dark" } as LibraryEntry.Series).episodes.map { it.id }

    @Test
    fun `default query lists every title once, newest first, with the series as one poster`() {
        assertEquals(listOf("Ghost", "Dark", "Brazil", "Dune", "Zodiac", "Heat", "Alien"), keys(LibraryQuery()))
        assertEquals(listOf("dark1", "dark2", "dark3"), darkEpisodes(LibraryQuery()))
    }

    @Test
    fun `type filter`() {
        assertEquals(listOf("Dark"), keys(LibraryQuery(type = LibraryTypeFilter.Shows)))
        assertEquals(listOf("Ghost", "Brazil", "Dune", "Zodiac", "Heat", "Alien"), keys(LibraryQuery(type = LibraryTypeFilter.Movies)))
    }

    @Test
    fun `location filters, several locations combine with OR`() {
        assertEquals(listOf("Dark", "Dune", "Heat", "Alien"), keys(LibraryQuery(locations = setOf(LibraryLocation.OnPhone))))
        assertEquals(listOf("Brazil"), keys(LibraryQuery(locations = setOf(LibraryLocation.Cloud))))
        assertEquals(listOf("Zodiac"), keys(LibraryQuery(locations = setOf(LibraryLocation.Telegram))))
        assertEquals(listOf("Ghost"), keys(LibraryQuery(locations = setOf(LibraryLocation.Unavailable))))
        assertEquals(listOf("Brazil", "Zodiac"), keys(LibraryQuery(locations = setOf(LibraryLocation.Cloud, LibraryLocation.Telegram))))
    }

    @Test
    fun `watched filters keep only the matching episodes of a series`() {
        assertEquals(listOf("Dark", "Alien"), keys(LibraryQuery(watched = WatchedFilter.Watched)))
        assertEquals(listOf("dark3"), darkEpisodes(LibraryQuery(watched = WatchedFilter.Watched)))
        assertEquals(listOf("Dark", "Heat"), keys(LibraryQuery(watched = WatchedFilter.Started)))
        assertEquals(listOf("dark2"), darkEpisodes(LibraryQuery(watched = WatchedFilter.Started)))
        assertEquals(listOf("Ghost", "Brazil", "Dune", "Zodiac", "Dark"), keys(LibraryQuery(watched = WatchedFilter.Unwatched)))
        assertEquals(listOf("dark1"), darkEpisodes(LibraryQuery(watched = WatchedFilter.Unwatched)))
    }

    @Test
    fun `collections combine with OR inside the group and AND with other filters`() {
        assertEquals(listOf("Dune", "Zodiac"), keys(LibraryQuery(collectionIds = setOf("kids"))))
        assertEquals(listOf("Dune", "Zodiac", "Heat"), keys(LibraryQuery(collectionIds = setOf("kids", "fav"))))
        assertEquals(listOf("Zodiac"), keys(LibraryQuery(collectionIds = setOf("kids", "fav"), locations = setOf(LibraryLocation.Telegram))))
        assertEquals(emptyList<String>(), keys(LibraryQuery(collectionIds = setOf("kids"), type = LibraryTypeFilter.Shows)))
    }

    @Test
    fun `each sort, a series counts as its newest, latest watched, earliest year or total size`() {
        assertEquals(listOf("Alien", "Brazil", "Dark", "Dune", "Ghost", "Heat", "Zodiac"), keys(LibraryQuery(sort = LibrarySort.TitleAZ)))
        assertEquals(listOf("Dune", "Dark", "Zodiac", "Heat", "Alien", "Brazil", "Ghost"), keys(LibraryQuery(sort = LibrarySort.Year)))
        assertEquals(listOf("Zodiac", "Dune", "Heat", "Alien", "Brazil", "Dark", "Ghost"), keys(LibraryQuery(sort = LibrarySort.SizeLargest)))
        assertEquals(
            listOf("Dark", "Heat", "Alien", "Brazil", "Dune", "Ghost", "Zodiac"),
            keys(LibraryQuery(sort = LibrarySort.LastWatched)),
        )
    }

    @Test
    fun `search applies on top of filters`() {
        assertEquals(listOf("Heat"), keys(LibraryQuery(search = "heat", watched = WatchedFilter.Started)))
        assertEquals(listOf("Dark"), keys(LibraryQuery(search = " DARK ")))
        assertEquals(emptyList<String>(), keys(LibraryQuery(search = "dune", type = LibraryTypeFilter.Shows)))
    }

    @Test
    fun `empty library and no match give an empty list`() {
        assertEquals(emptyList<LibraryEntry>(), applyLibraryQuery(emptyList(), LibraryQuery()))
        assertEquals(emptyList<String>(), keys(LibraryQuery(search = "nothing like this")))
    }

    @Test
    fun `hasFilters counts search but not sort`() {
        assertFalse(LibraryQuery(sort = LibrarySort.TitleAZ).hasFilters)
        assertTrue(LibraryQuery(search = "x").hasFilters)
        assertTrue(LibraryQuery(type = LibraryTypeFilter.Movies).hasFilters)
        assertTrue(LibraryQuery(collectionIds = setOf("kids")).hasFilters)
    }
}
