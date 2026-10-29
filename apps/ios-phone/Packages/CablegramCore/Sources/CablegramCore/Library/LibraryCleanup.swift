import Foundation

/// Why a title cannot play from this phone right now. Drives the "!" on posters and the Clean up sheet.
public enum LibraryProblem: Sendable, Hashable, CaseIterable {
    /// The source is gone (the file was deleted, or the other phone no longer has it).
    case unavailable
    /// The file was never on this phone, or was freed from it, and nothing else holds it for this phone.
    case notOnPhone
    /// A Telegram title, but this phone has no Telegram session of its own.
    case telegramNotConnected
    /// The title lives only in the owner's cloud storage, and that storage is disconnected.
    case cloudNotConnected

    /// Red for a title that is gone from here, yellow for one that a connection would bring back.
    public var needsConnection: Bool { self == .telegramNotConnected || self == .cloudNotConnected }
}

/// What this phone is connected to: a Telegram title or a cloud-only title only plays from here while its connection is up.
public struct LibraryConnections: Sendable, Equatable {
    public var telegramNeedsConnection: Bool
    public var cloudNeedsConnection: Bool
    public init(telegramNeedsConnection: Bool = false, cloudNeedsConnection: Bool = false) {
        self.telegramNeedsConnection = telegramNeedsConnection; self.cloudNeedsConnection = cloudNeedsConnection
    }
}

public func libraryProblem(_ item: LibraryItem, _ connections: LibraryConnections) -> LibraryProblem? {
    switch storageBadge(item) {
    case .unavailable: return .unavailable
    case .notOnPhone: return .notOnPhone
    case .telegram: return connections.telegramNeedsConnection ? .telegramNotConnected : nil
    // Only the owner's own storage can be "disconnected"; a cloud-only title has no copy on the phone to fall back on.
    case .cloud: return connections.cloudNeedsConnection && (item.ownCloudCopy || item.ownCloudSourceId != nil) ? .cloudNotConnected : nil
    default: return nil
    }
}

/// The titles of one problem, for the Clean up sheet.
public struct CleanupGroup: Sendable, Equatable {
    public var problem: LibraryProblem
    public var items: [LibraryItem]
}

/// Titles that cannot play from this phone, grouped by why (empty groups left out), in the order the sheet lists them.
public func cleanupGroups(_ items: [LibraryItem], _ connections: LibraryConnections) -> [CleanupGroup] {
    var byProblem: [LibraryProblem: [LibraryItem]] = [:]
    for item in items { if let problem = libraryProblem(item, connections) { byProblem[problem, default: []].append(item) } }
    return LibraryProblem.allCases.compactMap { problem in byProblem[problem].map { CleanupGroup(problem: problem, items: $0) } }
}
