import Foundation

public enum LibraryTypeFilter: Sendable, Equatable { case all, movies, shows }

/// Where a title can be played from. A title can be in several places at once.
public enum LibraryLocation: Sendable, Equatable, Hashable { case onPhone, cloud, telegram, unavailable }

public enum WatchedFilter: Sendable, Equatable { case any, unwatched, started, watched }

public enum LibrarySort: Sendable, Equatable { case recentlyAdded, lastWatched, titleAZ, year, sizeLargest }

/// What the library grid shows. Type and watched state are single choice. Different filter groups combine with AND; several
/// values inside one group (`locations`, `collectionIds`) combine with OR. An empty set means "no filter".
public struct LibraryQuery: Sendable, Equatable {
    public var type: LibraryTypeFilter
    public var locations: Set<LibraryLocation>
    public var watched: WatchedFilter
    public var collectionIds: Set<String>
    public var search: String
    public var sort: LibrarySort

    public init(
        type: LibraryTypeFilter = .all, locations: Set<LibraryLocation> = [], watched: WatchedFilter = .any,
        collectionIds: Set<String> = [], search: String = "", sort: LibrarySort = .recentlyAdded
    ) {
        self.type = type; self.locations = locations; self.watched = watched
        self.collectionIds = collectionIds; self.search = search; self.sort = sort
    }

    /// Search text is not a filter chip, but "Clear filters" clears it too.
    public var hasFilters: Bool {
        type != .all || !locations.isEmpty || watched != .any || !collectionIds.isEmpty
            || !search.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }
}

// Same thresholds as `continueWatching`: started after 8 seconds, finished at 92%.
public func isWatched(_ item: LibraryItem) -> Bool { watchFraction(item) >= 0.92 }
public func isStarted(_ item: LibraryItem) -> Bool { item.positionSeconds > 8 && !isWatched(item) }

public func itemLocations(_ item: LibraryItem) -> Set<LibraryLocation> {
    var found = Set<LibraryLocation>()
    if phoneStillHoldsVideo(item) { found.insert(.onPhone) }
    if item.ownCloudCopy || item.cloudObjectPresent { found.insert(.cloud) }
    if item.sourceKind == "telegram" || item.telegramCopy { found.insert(.telegram) }
    if storageBadge(item) == .unavailable { found.insert(.unavailable) }
    return found
}

private func matchesFilters(_ item: LibraryItem, _ query: LibraryQuery) -> Bool {
    switch query.type {
    case .all: break
    case .movies: if item.mediaType == "tv" { return false }
    case .shows: if item.mediaType != "tv" { return false }
    }
    if !query.locations.isEmpty && itemLocations(item).isDisjoint(with: query.locations) { return false }
    switch query.watched {
    case .any: break
    case .watched: if !isWatched(item) { return false }
    case .started: if !isStarted(item) { return false }
    case .unwatched: if isWatched(item) || isStarted(item) { return false }
    }
    if !query.collectionIds.isEmpty && !query.collectionIds.contains(where: { item.collectionIds.contains($0) }) { return false }
    return true
}

private extension LibraryEntry {
    var items: [LibraryItem] {
        switch self {
        case .film(let item): [item]
        case .series(_, let episodes): episodes
        }
    }
}

/// Filter, group episodes into series, then sort. A series holds only the episodes that matched, so "Unwatched" shows a
/// show with its unwatched episodes. Ties fall back to title, then key, so the order never flickers.
public func applyLibraryQuery(_ items: [LibraryItem], _ query: LibraryQuery) -> [LibraryEntry] {
    let matching = searchLibrary(items.filter { matchesFilters($0, query) }, query: query.search)
    let entries = groupIntoEntries(matching)

    // True when `a` goes before `b`; nil means a tie on the chosen sort.
    func before(_ a: LibraryEntry, _ b: LibraryEntry) -> Bool? {
        switch query.sort {
        case .recentlyAdded:
            // Newest first; a series counts as its newest episode.
            let x = a.items.map(\.importedAt).max()!, y = b.items.map(\.importedAt).max()!
            return x == y ? nil : x > y
        case .lastWatched:
            // Most recently watched first; never-watched titles go last.
            let x = a.items.map { $0.lastWatchedAt ?? "" }.max()!, y = b.items.map { $0.lastWatchedAt ?? "" }.max()!
            return x == y ? nil : x > y
        case .titleAZ:
            let x = a.title.lowercased(), y = b.title.lowercased()
            return x == y ? nil : x < y
        case .year:
            // Newest year first, unknown last; a series counts as its premiere (earliest episode year).
            let x = a.items.compactMap(\.year).min() ?? Int.min, y = b.items.compactMap(\.year).min() ?? Int.min
            return x == y ? nil : x > y
        case .sizeLargest:
            // Largest first; a series counts as the total of its episodes.
            let x = a.items.reduce(Int64(0)) { $0 + ($1.fileSizeBytes ?? 0) }, y = b.items.reduce(Int64(0)) { $0 + ($1.fileSizeBytes ?? 0) }
            return x == y ? nil : x > y
        }
    }
    return entries.sorted { a, b in
        if let order = before(a, b) { return order }
        let x = a.title.lowercased(), y = b.title.lowercased()
        return x == y ? a.key < b.key : x < y
    }
}
