import Foundation
import Testing
@testable import CablegramCore

/// The same cases, with the same fixtures, as LibraryQueryTest.kt on Android.
private func film(
    _ id: String, year: Int?, added: String, size: Int64, position: Int = 0, watchedAt: String? = nil,
    copied: Bool = true, kind: String = "phone_local", cloud: Bool = false, available: Bool? = nil, collections: [String] = []
) -> LibraryItem {
    LibraryItem(
        id: id, title: id, filename: "\(id).mkv", durationSeconds: 9_000, fileSizeBytes: size, year: year, importedAt: added,
        sourceKind: kind, copied: copied, sourceAvailable: available, positionSeconds: position, lastWatchedAt: watchedAt,
        collectionIds: collections, cloudObjectPresent: cloud
    )
}

private func episode(_ id: String, _ number: Int, added: String, position: Int = 0, watchedAt: String? = nil) -> LibraryItem {
    LibraryItem(
        id: id, title: "Dark", filename: "\(id).mkv", durationSeconds: 3_000, fileSizeBytes: 10, year: 2017, mediaType: "tv",
        importedAt: added, positionSeconds: position, lastWatchedAt: watchedAt, tmdbId: 70523, seasonNumber: 1, episodeNumber: number
    )
}

private let library: [LibraryItem] = [
    film("Dune", year: 2021, added: "2026-01-05T00:00:00Z", size: 300, collections: ["kids"]),
    film("Heat", year: 1995, added: "2026-01-03T00:00:00Z", size: 200, position: 5_000, watchedAt: "2026-02-02T00:00:00Z", collections: ["fav"]),
    film("Alien", year: 1979, added: "2026-01-01T00:00:00Z", size: 100, position: 8_900, watchedAt: "2026-02-01T00:00:00Z"),
    film("Zodiac", year: 2007, added: "2026-01-04T00:00:00Z", size: 400, copied: false, kind: "telegram", collections: ["kids"]),
    film("Brazil", year: nil, added: "2026-01-06T00:00:00Z", size: 50, copied: false, cloud: true, available: false),
    film("Ghost", year: nil, added: "2026-01-08T00:00:00Z", size: 20, copied: false, available: false),
    episode("dark1", 1, added: "2026-01-02T00:00:00Z"),
    episode("dark2", 2, added: "2026-01-07T00:00:00Z", position: 600, watchedAt: "2026-03-01T00:00:00Z"),
    episode("dark3", 3, added: "2026-01-02T12:00:00Z", position: 2_900, watchedAt: "2026-01-15T00:00:00Z"),
]

private func titles(_ query: LibraryQuery) -> [String] { applyLibraryQuery(library, query).map(\.title) }

private func darkEpisodes(_ query: LibraryQuery) -> [String] {
    for entry in applyLibraryQuery(library, query) {
        if case .series(_, let episodes) = entry { return episodes.map(\.id) }
    }
    return []
}

@Suite struct LibraryQueryTests {
    @Test func defaultQueryListsEveryTitleOnceNewestFirstWithTheSeriesAsOnePoster() {
        #expect(titles(LibraryQuery()) == ["Ghost", "Dark", "Brazil", "Dune", "Zodiac", "Heat", "Alien"])
        #expect(darkEpisodes(LibraryQuery()) == ["dark1", "dark2", "dark3"])
    }

    @Test func typeFilter() {
        #expect(titles(LibraryQuery(type: .shows)) == ["Dark"])
        #expect(titles(LibraryQuery(type: .movies)) == ["Ghost", "Brazil", "Dune", "Zodiac", "Heat", "Alien"])
    }

    @Test func locationFiltersCombineWithOrInsideTheGroup() {
        #expect(titles(LibraryQuery(locations: [.onPhone])) == ["Dark", "Dune", "Heat", "Alien"])
        #expect(titles(LibraryQuery(locations: [.cloud])) == ["Brazil"])
        #expect(titles(LibraryQuery(locations: [.telegram])) == ["Zodiac"])
        #expect(titles(LibraryQuery(locations: [.unavailable])) == ["Ghost"])
        #expect(titles(LibraryQuery(locations: [.cloud, .telegram])) == ["Brazil", "Zodiac"])
    }

    @Test func watchedFiltersKeepOnlyTheMatchingEpisodesOfASeries() {
        #expect(titles(LibraryQuery(watched: .watched)) == ["Dark", "Alien"])
        #expect(darkEpisodes(LibraryQuery(watched: .watched)) == ["dark3"])
        #expect(titles(LibraryQuery(watched: .started)) == ["Dark", "Heat"])
        #expect(darkEpisodes(LibraryQuery(watched: .started)) == ["dark2"])
        #expect(titles(LibraryQuery(watched: .unwatched)) == ["Ghost", "Brazil", "Dune", "Zodiac", "Dark"])
        #expect(darkEpisodes(LibraryQuery(watched: .unwatched)) == ["dark1"])
    }

    @Test func collectionsCombineWithOrInsideTheGroupAndAndWithOtherFilters() {
        #expect(titles(LibraryQuery(collectionIds: ["kids"])) == ["Dune", "Zodiac"])
        #expect(titles(LibraryQuery(collectionIds: ["kids", "fav"])) == ["Dune", "Zodiac", "Heat"])
        #expect(titles(LibraryQuery(locations: [.telegram], collectionIds: ["kids", "fav"])) == ["Zodiac"])
        #expect(titles(LibraryQuery(type: .shows, collectionIds: ["kids"])).isEmpty)
    }

    @Test func eachSortASeriesCountsAsItsNewestLatestWatchedEarliestYearOrTotalSize() {
        #expect(titles(LibraryQuery(sort: .titleAZ)) == ["Alien", "Brazil", "Dark", "Dune", "Ghost", "Heat", "Zodiac"])
        #expect(titles(LibraryQuery(sort: .year)) == ["Dune", "Dark", "Zodiac", "Heat", "Alien", "Brazil", "Ghost"])
        #expect(titles(LibraryQuery(sort: .sizeLargest)) == ["Zodiac", "Dune", "Heat", "Alien", "Brazil", "Dark", "Ghost"])
        #expect(titles(LibraryQuery(sort: .lastWatched)) == ["Dark", "Heat", "Alien", "Brazil", "Dune", "Ghost", "Zodiac"])
    }

    @Test func searchAppliesOnTopOfFilters() {
        #expect(titles(LibraryQuery(watched: .started, search: "heat")) == ["Heat"])
        #expect(titles(LibraryQuery(search: " DARK ")) == ["Dark"])
        #expect(titles(LibraryQuery(type: .shows, search: "dune")).isEmpty)
    }

    @Test func emptyLibraryAndNoMatchGiveAnEmptyList() {
        #expect(applyLibraryQuery([], LibraryQuery()).isEmpty)
        #expect(titles(LibraryQuery(search: "nothing like this")).isEmpty)
    }

    @Test func hasFiltersCountsSearchButNotSort() {
        #expect(!LibraryQuery(sort: .titleAZ).hasFilters)
        #expect(LibraryQuery(search: "x").hasFilters)
        #expect(LibraryQuery(type: .movies).hasFilters)
        #expect(LibraryQuery(collectionIds: ["kids"]).hasFilters)
    }
}

@Suite struct LibraryQueryStoreTests {
    @Test func filtersAndSortSurviveARoundTripSearchIsNotKept() {
        let query = LibraryQuery(type: .shows, locations: [.telegram, .cloud], watched: .started, collectionIds: ["kids", "fav"], search: "dune", sort: .sizeLargest)
        var expected = query; expected.search = ""
        #expect(decodeLibraryQuery(encodeLibraryQuery(query)) == expected)
    }

    @Test func missingBrokenOrUnknownStoredValuesGiveTheDefaults() {
        #expect(decodeLibraryQuery(nil) == LibraryQuery())
        #expect(decodeLibraryQuery("not json") == LibraryQuery())
        #expect(decodeLibraryQuery(#"{"type":"Anime","locations":["Cloud","Mars"],"watched":"?","sort":"Year","future":1}"#)
                == LibraryQuery(locations: [.cloud], sort: .year))
    }

    @Test func readsWhatTheAndroidAppWritesAndWritesSortedKeys() {
        #expect(decodeLibraryQuery(#"{"type":"Movies","locations":["Cloud","OnPhone"],"watched":"Any","collections":["a","b"],"sort":"TitleAZ"}"#)
                == LibraryQuery(type: .movies, locations: [.cloud, .onPhone], collectionIds: ["a", "b"], sort: .titleAZ))
        #expect(encodeLibraryQuery(LibraryQuery(type: .movies, locations: [.cloud, .onPhone], collectionIds: ["b", "a"], sort: .titleAZ))
                == #"{"collections":["a","b"],"locations":["Cloud","OnPhone"],"sort":"TitleAZ","type":"Movies","watched":"Any"}"#)
    }

    @Test func collectionsThatNoLongerExistAreDropped() {
        let query = LibraryQuery(collectionIds: ["kids", "gone"])
        #expect(query.withoutUnknownCollections(["kids", "fav"]).collectionIds == ["kids"])
        #expect(query.withoutUnknownCollections([]).collectionIds.isEmpty)
    }

    @Test func theMoreChipCountsLocationWatchedAndCollectionFiltersButNotTypeSortOrChipFilters() {
        #expect(LibraryQuery(type: .movies, sort: .year).moreFilterCount == 0)
        #expect(LibraryQuery(watched: .unwatched).moreFilterCount == 0)
        #expect(LibraryQuery(watched: .started).moreFilterCount == 0)
        #expect(LibraryQuery(locations: [.cloud], watched: .watched, collectionIds: ["a", "b"]).moreFilterCount == 4)
    }
}

/// The same cases, with the same fixtures, as LibraryBulkTest.kt on Android.
private func bulkItem(_ id: String, size: Int64, copied: Bool = true, kind: String = "phone_local", telegramCopy: Bool = false, cloud: Bool = false) -> LibraryItem {
    LibraryItem(id: id, title: id, filename: "\(id).mkv", fileSizeBytes: size, importedAt: "2026-01-01T00:00:00Z", sourceKind: kind, copied: copied, telegramCopy: telegramCopy, ownCloudCopy: cloud)
}

@Suite struct LibraryBulkTests {
    @Test func togglingAPosterSelectsOrClearsAllOfItsTitles() {
        #expect(toggleSelection(["c"], ["a", "b"]) == ["a", "b", "c"])
        #expect(toggleSelection(["a", "b", "c"], ["a", "b"]) == ["c"])
        // A half-selected series is completed, not cleared.
        #expect(toggleSelection(["a"], ["a", "b"]) == ["a", "b"])
        #expect(!isSelected(["a"], ["a", "b"]))
        #expect(!isSelected(["a"], []))
        #expect(isSelected(["a", "b"], ["a", "b"]))
    }

    @Test func aRemovalCountsTelegramAndPhoneTitlesAndTheBytesKeptInsideTheApp() {
        let summary = summarizeRemoval([bulkItem("p1", size: 100), bulkItem("p2", size: 200, copied: false),
                                        bulkItem("t1", size: 400, copied: false, kind: "telegram"), bulkItem("p3", size: 50)])
        #expect(summary == RemovalSummary(total: 4, phoneItems: 3, telegramItems: 1, phoneBytes: 150))
        #expect(summarizeRemoval([]) == RemovalSummary(total: 0, phoneItems: 0, telegramItems: 0, phoneBytes: 0))
    }

    @Test func freeUpOnlyTakesTitlesWithAnotherCopyAndListsTheRestAsSkipped() {
        let summary = summarizeFreeUp([bulkItem("a", size: 100, telegramCopy: true), bulkItem("c", size: 500), bulkItem("b", size: 300, cloud: true),
                                       bulkItem("d", size: 700, copied: false, telegramCopy: true)])
        #expect(summary.eligible.map(\.id) == ["a", "b"])
        #expect(summary.skipped.map(\.id) == ["c", "d"])
        #expect(summary.bytes == 400)
    }
}
