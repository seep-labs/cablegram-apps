package app.cablegram.ui

import app.cablegram.data.CollectionRef
import app.cablegram.data.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryShelvesTest {
    @Test
    fun `orders continue watching recently added my list then genres`() {
        val videos = listOf(
            video("inception", "Inception", genres = listOf("Science Fiction", "Action"), resume = 40, added = "2026-09-01T12:00:00Z", inMyList = true),
            video("up", "Up", genres = listOf("Animation"), added = "2026-09-08T12:00:00Z"),
        )
        val shelves = summarizeLibraryShelves(videos)
        assertEquals(
            listOf("continue", "recent", "mylist", "genre:Action", "genre:Animation", "genre:Science Fiction"),
            shelves.map { it.id },
        )
        assertEquals(
            listOf("Continue Watching", "Recently Added", "My List", "Action", "Animation", "Science Fiction"),
            shelves.map { it.title },
        )
    }

    @Test
    fun `same poster can appear on several rows`() {
        val videos = listOf(
            video("inception", "Inception", genres = listOf("Action", "Drama"), resume = 12, added = "2026-09-01T12:00:00Z", inMyList = true),
        )
        val shelves = summarizeLibraryShelves(videos)
        val rowsWithInception = shelves.filter { "inception" in it.entryIds }.map { it.id }
        assertEquals(listOf("continue", "recent", "mylist", "genre:Action", "genre:Drama"), rowsWithInception)
    }

    @Test
    fun `continue watching only includes in-progress titles`() {
        val videos = listOf(
            video("done", "Finished", genres = listOf("Drama"), resume = 0),
            video("mid", "Halfway", genres = listOf("Drama"), resume = 80),
        )
        val continueRow = summarizeLibraryShelves(videos).single { it.id == "continue" }
        assertEquals(listOf("mid"), continueRow.entryIds)
        assertTrue(summarizeLibraryShelves(videos).none { it.id == "mylist" })
    }

    @Test
    fun `my list row only includes saved titles`() {
        val videos = listOf(
            video("kept", "Kept", genres = listOf("Drama"), inMyList = true),
            video("other", "Other", genres = listOf("Drama")),
        )
        val myList = summarizeLibraryShelves(videos).single { it.id == "mylist" }
        assertEquals(listOf("kept"), myList.entryIds)
    }

    @Test
    fun `my list destination excludes unsaved titles`() {
        val videos = listOf(
            video("kept", "Kept", genres = listOf("Drama"), inMyList = true),
            video("other", "Other", genres = listOf("Drama")),
        )

        val visibleIds = summarizeLibraryShelves(videos, selectedType = "mylist")
            .flatMap { it.entryIds }
            .distinct()

        assertEquals(listOf("kept"), visibleIds)
    }

    @Test
    fun `continue and my list destinations are single named shelves`() {
        val videos = listOf(
            video("kept", "Kept", genres = listOf("Drama"), resume = 30, inMyList = true),
            video("other", "Other", genres = listOf("Drama"), resume = 10),
        )

        assertEquals(listOf("mylist"), summarizeLibraryShelves(videos, selectedType = "mylist").map { it.id })
        val continueShelves = summarizeLibraryShelves(videos, selectedType = "watchlist")
        assertEquals(listOf("continue"), continueShelves.map { it.id })
        assertEquals(listOf("kept", "other"), continueShelves.single().entryIds)
    }

    @Test
    fun `each of the account's collections is a row after my list and before the genres`() {
        val kids = CollectionRef("c-kids", "Kids")
        val fav = CollectionRef("c-fav", "favourites")
        val videos = listOf(
            video("up", "Up", genres = listOf("Animation"), added = "2026-09-08T12:00:00Z", collections = listOf(kids)),
            video("cars", "Cars", genres = listOf("Animation"), added = "2026-09-09T12:00:00Z", collections = listOf(kids, fav)),
            video("heat", "Heat", genres = listOf("Action"), inMyList = true),
        )
        val shelves = summarizeLibraryShelves(videos)
        assertEquals(listOf("recent", "mylist", "collection:c-fav", "collection:c-kids", "genre:Action", "genre:Animation"), shelves.map { it.id })
        // Named as on the phone, ordered ignoring case, newest first inside.
        assertEquals(listOf("favourites", "Kids"), shelves.filter { it.id.startsWith("collection:") }.map { it.title })
        assertEquals(listOf("cars", "up"), shelves.single { it.id == "collection:c-kids" }.entryIds)
    }

    @Test
    fun `collection rows follow the type filter and are left out when a genre is chosen`() {
        val kids = CollectionRef("c-kids", "Kids")
        val videos = listOf(
            video("up", "Up", genres = listOf("Animation"), collections = listOf(kids)),
            video("bluey", "Bluey", genres = listOf("Animation"), type = "tv", collections = listOf(kids)),
        )
        assertEquals(listOf("up"), summarizeLibraryShelves(videos, selectedType = "movie").single { it.id == "collection:c-kids" }.entryIds)
        assertEquals(listOf("title:bluey"), summarizeLibraryShelves(videos, selectedType = "tv").single { it.id == "collection:c-kids" }.entryIds)
        assertTrue(summarizeLibraryShelves(videos, selectedGenre = "Animation").none { it.id.startsWith("collection:") })
        assertTrue(summarizeLibraryShelves(listOf(video("up", "Up", genres = emptyList())), selectedType = "all").none { it.id.startsWith("collection:") })
    }

    private val library = listOf(
        video("dune", "Dune", genres = emptyList(), added = "2026-09-03T00:00:00Z", year = 2021),
        video("alien", "alien", genres = emptyList(), added = "2026-09-01T00:00:00Z", year = 1979),
        video("zodiac", "Zodiac", genres = emptyList(), added = "2026-09-02T00:00:00Z", year = null),
        video("dark1", "Dark", genres = emptyList(), added = "2026-09-05T00:00:00Z", year = 2017, type = "tv", tmdb = 70523, season = 1, episode = 1),
        video("dark2", "Dark", genres = emptyList(), added = "2026-09-06T00:00:00Z", year = 2019, type = "tv", tmdb = 70523, season = 2, episode = 1),
    )

    @Test
    fun `all titles lists every title once, a show as one poster, newest first by default`() {
        assertEquals(listOf("tmdb:70523", "dune", "zodiac", "alien"), summarizeAllTitles(library))
    }

    @Test
    fun `all titles sorts by title ignoring case, and by year newest first with unknown years last`() {
        assertEquals(listOf("alien", "tmdb:70523", "dune", "zodiac"), summarizeAllTitles(library, sort = TitlesSort.TitleAZ))
        // A show counts as its earliest episode year (2017).
        assertEquals(listOf("dune", "tmdb:70523", "alien", "zodiac"), summarizeAllTitles(library, sort = TitlesSort.Year))
    }

    @Test
    fun `all titles narrows to movies or shows and is empty for an empty library`() {
        assertEquals(listOf("dune", "zodiac", "alien"), summarizeAllTitles(library, type = "movie"))
        assertEquals(listOf("tmdb:70523"), summarizeAllTitles(library, type = "tv"))
        assertTrue(summarizeAllTitles(emptyList()).isEmpty())
    }

    private fun video(
        id: String,
        title: String,
        genres: List<String>,
        resume: Int = 0,
        added: String = "2026-09-01T12:00:00Z",
        inMyList: Boolean = false,
        type: String = "movie",
        collections: List<CollectionRef> = emptyList(),
        year: Int? = null,
        tmdb: Int? = null,
        season: Int? = null,
        episode: Int? = null,
    ) = Video(
        id = id,
        title = title,
        addedAtTimestamp = added,
        resumePositionSeconds = resume,
        genres = genres,
        mediaType = type,
        inMyList = inMyList,
        collections = collections,
        releaseYear = year,
        tmdbId = tmdb,
        seasonNumber = season,
        episodeNumber = episode,
    )
}
