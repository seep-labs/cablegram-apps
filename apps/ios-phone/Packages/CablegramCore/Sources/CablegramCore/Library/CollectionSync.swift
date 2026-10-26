import Foundation

/// What one collections call answered; `notFound` or `conflict` is the server's decision, `failed` means try again later.
public enum CallOutcome: Sendable, Equatable { case ok, conflict, notFound, failed }

public struct CollectionCall<Value: Sendable>: Sendable {
    public var outcome: CallOutcome
    public var value: Value?
    public init(_ outcome: CallOutcome, _ value: Value? = nil) { self.outcome = outcome; self.value = value }
}

/// The account's collection routes (contracts/collections.md).
public protocol CollectionsAPI: Sendable {
    /// nil when the server could not be reached; the sync then changes nothing.
    func list() async -> [RemoteCollection]?
    func create(_ name: String) async -> CollectionCall<RemoteCollection>
    func rename(_ id: String, _ name: String) async -> CollectionCall<RemoteCollection>
    func delete(_ id: String) async -> CollectionCall<Void>
    func setMember(collectionId: String, itemId: String, present: Bool) async -> CollectionCall<Void>
}

/// A title as the collections sync sees it. `serverItemId` is nil until the title has been pushed to the catalog.
public struct MemberState: Sendable, Equatable {
    public var itemId: String
    public var serverItemId: String?
    public var collectionIds: [String]
    public var pending: Bool
    public init(itemId: String, serverItemId: String?, collectionIds: [String], pending: Bool) {
        self.itemId = itemId; self.serverItemId = serverItemId; self.collectionIds = collectionIds; self.pending = pending
    }
}

public struct CollectionsState: Sendable, Equatable {
    public var collections: [UserCollection]
    public var pendingDeletes: [String]
    public var members: [MemberState]
    public init(collections: [UserCollection], pendingDeletes: [String], members: [MemberState]) {
        self.collections = collections; self.pendingDeletes = pendingDeletes; self.members = members
    }
}

private func sameName(_ a: String, _ b: String) -> Bool {
    a.trimmingCharacters(in: .whitespacesAndNewlines).caseInsensitiveCompare(b.trimmingCharacters(in: .whitespacesAndNewlines)) == .orderedSame
}

/// Reconciles the phone's collections and each title's collection ids with the server, in the order the contract describes: deletes,
/// renames, what was deleted elsewhere, uploads (merging by name), what was made elsewhere, then membership. Nothing the phone has not
/// sent is dropped. `serverMembers` maps a catalog title id to the ids of the collections it is in. Returns nil when the server could
/// not be listed, and the new state otherwise (a call that failed leaves its change pending for the next sync).
public func syncCollections(api: CollectionsAPI, state: CollectionsState, serverMembers: [String: [String]]) async -> CollectionsState? {
    guard var server = await api.list() else { return nil }
    var collections = state.collections
    var members = state.members

    func markPending(_ ids: Set<String>) {
        members = members.map { m in
            var m = m
            if m.collectionIds.contains(where: ids.contains) { m.pending = true }
            return m
        }
    }

    // 1. Deletes made here. A 404 means it is already gone.
    var stillDeleting: [String] = []
    var seenDeletes = Set<String>()
    for id in state.pendingDeletes where seenDeletes.insert(id).inserted {
        let outcome = await api.delete(id).outcome
        if outcome == .ok || outcome == .notFound { server.removeAll { $0.id == id } } else { stillDeleting.append(id) }
    }

    // 2. Renames made here. A name taken meanwhile gives the server's name back; a collection deleted elsewhere is made again.
    var renamed: [UserCollection] = []
    for c in collections {
        guard c.synced, c.dirty else { renamed.append(c); continue }
        var c = c
        let result = await api.rename(c.id, c.name)
        switch result.outcome {
        case .ok: c.name = result.value?.name ?? c.name; c.dirty = false
        case .conflict: c.name = server.first { $0.id == c.id }?.name ?? c.name; c.dirty = false
        case .notFound: markPending([c.id]); c.synced = false; c.dirty = false
        case .failed: break
        }
        renamed.append(c)
    }
    collections = renamed

    // 3. Collections deleted elsewhere go, taken out of their titles; one this phone still has an unsent change for is made again.
    let serverIds = Set(server.map(\.id))
    collections = collections.compactMap { c in
        if !c.synced || serverIds.contains(c.id) { return c }
        if members.contains(where: { $0.pending && $0.collectionIds.contains(c.id) }) { var again = c; again.synced = false; again.dirty = false; return again }
        members = members.map { m in var m = m; m.collectionIds.removeAll { $0 == c.id }; return m }
        return nil
    }

    // 4. Collections made here: use the account's collection with the same name, or create one. Titles move to the server's id.
    var resolved: [UserCollection] = []
    for c in collections {
        if c.synced { if !resolved.contains(where: { $0.id == c.id }) { resolved.append(c) }; continue }
        var target = server.first { sameName($0.name, c.name) }
        if target == nil {
            let created = await api.create(c.name)
            switch created.outcome {
            case .ok: target = created.value
            // Someone created that name since the list: look again.
            case .conflict: if let fresh = await api.list() { server = fresh; target = fresh.first { sameName($0.name, c.name) } }
            default: break
            }
        }
        guard let target else { resolved.append(c); continue }
        if !server.contains(where: { $0.id == target.id }) { server.append(target) }
        members = members.map { m in
            guard m.collectionIds.contains(c.id) else { return m }
            var m = m
            var seen = Set<String>()
            m.collectionIds = m.collectionIds.map { $0 == c.id ? target.id : $0 }.filter { seen.insert($0).inserted }
            m.pending = true
            return m
        }
        if !resolved.contains(where: { $0.id == target.id }) { resolved.append(UserCollection(id: target.id, name: target.name, synced: true, dirty: false)) }
    }
    collections = resolved

    // 4b. Collections made elsewhere arrive, and renamed ones take the new name.
    let deleting = Set(stillDeleting)
    for s in server where !deleting.contains(s.id) {
        if let index = collections.firstIndex(where: { $0.id == s.id }) {
            if collections[index].synced && !collections[index].dirty && collections[index].name != s.name { collections[index].name = s.name }
        } else {
            collections.append(UserCollection(id: s.id, name: s.name, synced: true))
        }
    }

    // 5. Membership: a title edited here sends its difference; any other title takes the server's list.
    let synced = Set(collections.filter(\.synced).map(\.id))
    var updated: [MemberState] = []
    for var m in members {
        guard let serverItem = m.serverItemId, let onServer = serverMembers[serverItem] else { updated.append(m); continue }
        let localOnly = m.collectionIds.filter { !synced.contains($0) }
        if !m.pending {
            var seen = Set<String>()
            m.collectionIds = (onServer.filter { synced.contains($0) } + localOnly).filter { seen.insert($0).inserted }
            updated.append(m)
            continue
        }
        let wanted = m.collectionIds.filter { synced.contains($0) }
        let calls = wanted.filter { !onServer.contains($0) }.map { ($0, true) } + onServer.filter { synced.contains($0) && !wanted.contains($0) }.map { ($0, false) }
        var failed = false
        for (collection, present) in calls {
            let outcome = await api.setMember(collectionId: collection, itemId: serverItem, present: present).outcome
            if outcome != .ok && outcome != .notFound { failed = true }
        }
        if !failed && localOnly.isEmpty { m.collectionIds = wanted; m.pending = false }
        updated.append(m)
    }

    return CollectionsState(collections: collections, pendingDeletes: stillDeleting, members: updated)
}
