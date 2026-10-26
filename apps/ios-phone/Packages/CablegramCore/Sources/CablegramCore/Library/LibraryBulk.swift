import Foundation

/// Selecting or deselecting one poster: all of its titles together.
public func toggleSelection(_ selected: Set<String>, _ ids: [String]) -> Set<String> {
    isSelected(selected, ids) ? selected.subtracting(ids) : selected.union(ids)
}

public func isSelected(_ selected: Set<String>, _ ids: [String]) -> Bool { !ids.isEmpty && ids.allSatisfy(selected.contains) }

/// What a removal touches, counted by where each title lives. Cloud copies are never part of it.
public struct RemovalSummary: Sendable, Equatable {
    public var total: Int
    public var phoneItems: Int
    public var telegramItems: Int
    public var phoneBytes: Int64
}

/// A Telegram title is hidden or deleted through the control plane; every other title is a phone file. Only a file kept inside
/// the app (`copied`) counts towards the bytes "Also delete the files" frees.
public func summarizeRemoval(_ items: [LibraryItem]) -> RemovalSummary {
    let telegram = items.filter { $0.sourceKind == "telegram" }
    let phone = items.filter { $0.sourceKind != "telegram" }
    return RemovalSummary(total: items.count, phoneItems: phone.count, telegramItems: telegram.count,
                          phoneBytes: phone.filter(\.copied).reduce(0) { $0 + ($1.fileSizeBytes ?? 0) })
}

/// Free up space only acts on titles with a verified copy elsewhere (`canRemoveLocalCopy`); the rest are listed as skipped.
public struct FreeUpSummary: Sendable, Equatable {
    public var eligible: [LibraryItem]
    public var skipped: [LibraryItem]
    public var bytes: Int64
}

public func summarizeFreeUp(_ items: [LibraryItem]) -> FreeUpSummary {
    let eligible = items.filter(canRemoveLocalCopy), skipped = items.filter { !canRemoveLocalCopy($0) }
    return FreeUpSummary(eligible: eligible, skipped: skipped, bytes: eligible.reduce(0) { $0 + ($1.fileSizeBytes ?? 0) })
}

public enum RemovalMode: Sendable, Equatable { case removeFromLibrary, deleteFiles }
