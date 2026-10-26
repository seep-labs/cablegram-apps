import Foundation
import Testing
@testable import CablegramCore

/// The same scenarios, against the same fake server, as CollectionSyncTest.kt on Android.
private actor FakeServer: CollectionsAPI {
    var names: [(id: String, name: String)]
    var members = Set<String>()   // "collection|item"
    var calls: [String] = []
    var reachable = true
    var failing = Set<String>()
    private var next = 0

    init(_ initial: [(String, String)] = []) { names = initial.map { (id: $0.0, name: $0.1) } }

    func setFailing(_ calls: Set<String>) { failing = calls }
    func setReachable(_ value: Bool) { reachable = value }
    func addMember(_ collection: String, _ item: String) { members.insert("\(collection)|\(item)") }
    func removeName(_ id: String) { names.removeAll { $0.id == id } }
    func callLog() -> [String] { calls }
    func clearCalls() { calls = [] }
    func nameOf(_ id: String) -> String? { names.first { $0.id == id }?.name }
    func ids() -> Set<String> { Set(names.map(\.id)) }
    func nameCount() -> Int { names.count }
    func allNames() -> [String] { names.map(\.name) }
    func membersOf(_ item: String) -> [String] { members.filter { $0.hasSuffix("|\(item)") }.map { String($0.split(separator: "|")[0]) }.sorted() }
    func memberItems() -> Set<String> { Set(members.map { String($0.split(separator: "|")[1]) }) }

    private func fails(_ call: String) -> Bool { !reachable || failing.contains(call) }

    func list() async -> [RemoteCollection]? { reachable ? names.map { RemoteCollection(id: $0.id, name: $0.name) } : nil }
    func create(_ name: String) async -> CollectionCall<RemoteCollection> {
        calls.append("create \(name)")
        if fails("create") { return CollectionCall(.failed) }
        if names.contains(where: { $0.name.caseInsensitiveCompare(name) == .orderedSame }) { return CollectionCall(.conflict) }
        next += 1
        let id = "N\(next)"
        names.append((id: id, name: name))
        return CollectionCall(.ok, RemoteCollection(id: id, name: name))
    }
    func rename(_ id: String, _ name: String) async -> CollectionCall<RemoteCollection> {
        calls.append("rename \(id) \(name)")
        if fails("rename") { return CollectionCall(.failed) }
        guard let index = names.firstIndex(where: { $0.id == id }) else { return CollectionCall(.notFound) }
        if names.contains(where: { $0.id != id && $0.name.caseInsensitiveCompare(name) == .orderedSame }) { return CollectionCall(.conflict) }
        names[index].name = name
        return CollectionCall(.ok, RemoteCollection(id: id, name: name))
    }
    func delete(_ id: String) async -> CollectionCall<Void> {
        calls.append("delete \(id)")
        if fails("delete") { return CollectionCall(.failed) }
        guard names.contains(where: { $0.id == id }) else { return CollectionCall(.notFound) }
        names.removeAll { $0.id == id }
        members = members.filter { !$0.hasPrefix("\(id)|") }
        return CollectionCall(.ok, ())
    }
    func setMember(collectionId: String, itemId: String, present: Bool) async -> CollectionCall<Void> {
        calls.append("member \(collectionId) \(itemId) \(present)")
        if fails("member") { return CollectionCall(.failed) }
        guard names.contains(where: { $0.id == collectionId }) else { return CollectionCall(.notFound) }
        if present { members.insert("\(collectionId)|\(itemId)") } else { members.remove("\(collectionId)|\(itemId)") }
        return CollectionCall(.ok, ())
    }
}

private func sync(_ server: FakeServer, _ state: CollectionsState) async -> CollectionsState? {
    var serverMembers: [String: [String]] = [:]
    for id in Set(state.members.compactMap(\.serverItemId)) { serverMembers[id] = await server.membersOf(id) }
    return await syncCollections(api: server, state: state, serverMembers: serverMembers)
}

private func local(_ id: String, _ name: String, synced: Bool = false, dirty: Bool = false) -> UserCollection { UserCollection(id: id, name: name, synced: synced, dirty: dirty) }
private func item(_ id: String, _ ids: [String], pending: Bool = false, server: String? = nil, noServer: Bool = false) -> MemberState {
    MemberState(itemId: id, serverItemId: noServer ? nil : (server ?? id), collectionIds: ids, pending: pending)
}

@Suite struct CollectionSyncTests {
    @Test func phoneOnlyCollectionsAreCreatedOnceAndTheirTitlesMoveToTheServersIds() async throws {
        let server = FakeServer()
        let start = CollectionsState(collections: [local("L1", "Kids")], pendingDeletes: [], members: [item("a", ["L1"]), item("b", [])])
        let first = try #require(await sync(server, start))
        #expect(first.collections == [UserCollection(id: "N1", name: "Kids", synced: true)])
        #expect(first.members.first { $0.itemId == "a" }?.collectionIds == ["N1"])
        #expect(first.members.first { $0.itemId == "a" }?.pending == false)
        #expect(await server.membersOf("a") == ["N1"])
        // Running it again changes nothing and calls nothing.
        await server.clearCalls()
        #expect(await sync(server, first) == first)
        #expect(await server.callLog().isEmpty)
    }

    @Test func aCollectionWithTheSameNameAlreadyOnTheAccountIsUsedIgnoringCase() async throws {
        let server = FakeServer([("S9", "kids")])
        let start = CollectionsState(collections: [local("L1", "Kids"), local("L2", "Favourites")], pendingDeletes: [], members: [item("a", ["L1", "L2"])])
        let result = try #require(await sync(server, start))
        #expect(await server.callLog().filter { $0.hasPrefix("create") } == ["create Favourites"])
        #expect(result.collections.map(\.id).sorted() == ["N1", "S9"])
        #expect(result.collections.first { $0.id == "S9" }?.name == "kids")
        #expect(Set(result.members[0].collectionIds) == ["S9", "N1"])
        #expect(Set(await server.membersOf("a")) == ["S9", "N1"])
    }

    @Test func twoLocalCollectionsWithOneNameBecomeOne() async throws {
        let server = FakeServer()
        let start = CollectionsState(collections: [local("L1", "Kids"), local("L2", "kids")], pendingDeletes: [], members: [item("a", ["L1"]), item("b", ["L2"])])
        let result = try #require(await sync(server, start))
        #expect(result.collections.count == 1)
        #expect(await server.nameCount() == 1)
        #expect(result.members.map(\.collectionIds) == [["N1"], ["N1"]])
    }

    @Test func aCollectionThatCouldNotBeCreatedStaysOnThePhoneAndIsTriedAgain() async throws {
        let server = FakeServer()
        await server.setFailing(["create"])
        let start = CollectionsState(collections: [local("L1", "Kids")], pendingDeletes: [], members: [item("a", ["L1"], pending: true)])
        let first = try #require(await sync(server, start))
        #expect(first == start)
        await server.setFailing([])
        let second = try #require(await sync(server, first))
        #expect(second.collections == [UserCollection(id: "N1", name: "Kids", synced: true)])
        #expect(await server.membersOf("a") == ["N1"])
    }

    @Test func anUnreachableServerChangesNothing() async {
        let server = FakeServer()
        await server.setReachable(false)
        #expect(await sync(server, CollectionsState(collections: [local("L1", "Kids")], pendingDeletes: ["S5"], members: [])) == nil)
    }

    @Test func aRenameIsSentOnceTakesTheServersNameOnAClashAndIsMadeAgainWhenTheCollectionIsGone() async throws {
        let server = FakeServer([("S1", "Kids"), ("S2", "Favourites")])
        let renamed = try #require(await sync(server, CollectionsState(collections: [local("S1", "Family", synced: true, dirty: true), local("S2", "Favourites", synced: true)], pendingDeletes: [], members: [])))
        #expect(await server.nameOf("S1") == "Family")
        #expect(renamed.collections.first { $0.id == "S1" }?.dirty == false)

        let clash = try #require(await sync(server, CollectionsState(collections: [local("S1", "favourites", synced: true, dirty: true), local("S2", "Favourites", synced: true)], pendingDeletes: [], members: [])))
        #expect(clash.collections.first { $0.id == "S1" }?.name == "Family")
        #expect(clash.collections.first { $0.id == "S1" }?.dirty == false)

        await server.removeName("S2")
        let gone = try #require(await sync(server, CollectionsState(collections: [local("S2", "Best", synced: true, dirty: true)], pendingDeletes: [], members: [item("a", ["S2"])])))
        #expect(gone.collections.filter { $0.name == "Best" }.count == 1)
        #expect(gone.collections.first { $0.name == "Best" }?.synced == true)
        #expect(await server.allNames().filter { $0 == "Best" }.count == 1)
    }

    @Test func aDeleteIsSentUntilTheServerConfirmsAndADeletedCollectionIsNotBroughtBackMeanwhile() async throws {
        let server = FakeServer([("S1", "Kids"), ("S2", "Favourites")])
        await server.setFailing(["delete"])
        let start = CollectionsState(collections: [local("S2", "Favourites", synced: true)], pendingDeletes: ["S1"], members: [])
        let first = try #require(await sync(server, start))
        #expect(first.pendingDeletes == ["S1"])
        #expect(first.collections.map(\.id) == ["S2"])
        await server.setFailing([])
        let second = try #require(await sync(server, first))
        #expect(second.pendingDeletes.isEmpty)
        #expect(await server.ids() == ["S2"])
        // An id the server no longer knows counts as deleted.
        #expect(try #require(await sync(server, CollectionsState(collections: second.collections, pendingDeletes: ["S7"], members: []))).pendingDeletes.isEmpty)
    }

    @Test func collectionsMadeOrDeletedOnAnotherPhoneArriveUnlessThisPhoneHasAnUnsentChangeForThem() async throws {
        let server = FakeServer([("S1", "Kids"), ("S3", "New elsewhere")])
        let start = CollectionsState(
            collections: [local("S1", "Old name", synced: true), local("S2", "Deleted elsewhere", synced: true), local("S4", "Also deleted", synced: true)],
            pendingDeletes: [], members: [item("a", ["S4"], pending: true), item("b", ["S2"])])
        let result = try #require(await sync(server, start))
        #expect(result.collections.first { $0.id == "S1" }?.name == "Kids")
        #expect(result.collections.contains { $0.id == "S3" && $0.synced })
        // S2 had nothing unsent: gone, and taken out of its titles.
        #expect(!result.collections.contains { $0.id == "S2" })
        #expect(result.members.first { $0.itemId == "b" }?.collectionIds == [])
        // S4 has an unsent membership: kept and made again.
        #expect(result.collections.filter { $0.name == "Also deleted" }.count == 1)
        #expect(await server.memberItems() == ["a"])
    }

    @Test func anEditedTitleSendsItsDifferenceAnyOtherTitleTakesTheServersList() async throws {
        let server = FakeServer([("S1", "Kids"), ("S2", "Favourites")])
        await server.addMember("S1", "a"); await server.addMember("S1", "b"); await server.addMember("S2", "c")
        let synced = [local("S1", "Kids", synced: true), local("S2", "Favourites", synced: true)]
        let start = CollectionsState(collections: synced, pendingDeletes: [], members: [
            item("a", ["S2"], pending: true), item("b", []), item("c", ["S1"]), item("d", [], pending: true, noServer: true)])
        let result = try #require(await sync(server, start))
        let byId = Dictionary(uniqueKeysWithValues: result.members.map { ($0.itemId, $0) })
        // a: pending, wanted only S2 → left S1, joined S2.
        #expect(await server.membersOf("a") == ["S2"])
        #expect(byId["a"]?.pending == false)
        // b and c were not edited here: they take what the server has.
        #expect(byId["b"]?.collectionIds == ["S1"])
        #expect(byId["c"]?.collectionIds == ["S2"])
        // d is not on the server yet: untouched and still pending.
        #expect(byId["d"] == item("d", [], pending: true, noServer: true))
    }

    @Test func aMembershipThatCouldNotBeSentStaysPendingAndIsSentAgain() async throws {
        let server = FakeServer([("S1", "Kids")])
        await server.setFailing(["member"])
        let start = CollectionsState(collections: [local("S1", "Kids", synced: true)], pendingDeletes: [], members: [item("a", ["S1"], pending: true)])
        let first = try #require(await sync(server, start))
        #expect(first.members[0].pending)
        #expect(first.members[0].collectionIds == ["S1"])
        await server.setFailing([])
        let second = try #require(await sync(server, first))
        #expect(!second.members[0].pending)
        #expect(await server.membersOf("a") == ["S1"])
    }
}

private func tempStore() -> LibraryStore {
    LibraryStore(root: FileManager.default.temporaryDirectory.appendingPathComponent("coll-\(UUID().uuidString)"))
}

@Suite struct LibraryStoreCollectionsTests {
    private func store() -> LibraryStore {
        let store = tempStore()
        store.add(LibraryItem(id: "a", title: "A", filename: "a.mkv", importedAt: "2026-01-01T00:00:00Z"))
        return store
    }

    private func addSynced(_ store: LibraryStore, _ id: String, _ name: String) {
        let base = store.collectionsState()
        var next = base
        next.collections.append(UserCollection(id: id, name: name, synced: true))
        store.applyCollectionsState(base: base, result: next)
    }

    @Test func aNewCollectionIsNotSyncedAndAskingForAnExistingNameGivesThatCollection() {
        let store = store()
        let kids = store.addCollection("  Kids ")
        #expect(kids == UserCollection(id: kids.id, name: "Kids", synced: false, dirty: false))
        #expect(store.addCollection("kids") == kids)
        #expect(store.collections().count == 1)
    }

    @Test func renamingMarksASyncedCollectionDirtyRefusesATakenNameAndALocalOneStaysClean() {
        let store = store()
        addSynced(store, "S1", "Kids")
        let local = store.addCollection("Favourites")
        #expect(store.renameCollection("S1", name: "Family"))
        #expect(store.collections().first { $0.id == "S1" } == UserCollection(id: "S1", name: "Family", synced: true, dirty: true))
        #expect(!store.renameCollection("S1", name: "favourites"))
        #expect(!store.renameCollection("S1", name: "   "))
        #expect(store.renameCollection(local.id, name: "Best"))
        #expect(store.collections().first { $0.id == local.id }?.dirty == false)
    }

    @Test func deletingASyncedCollectionIsRememberedForTheServerALocalOneIsNotAndTitlesOnlyLoseTheCollection() {
        let store = store()
        addSynced(store, "S1", "Kids")
        let local = store.addCollection("Local")
        store.setItemCollections("a", collectionIds: ["S1", local.id])
        store.deleteCollection("S1")
        store.deleteCollection(local.id)
        #expect(store.snapshot().pendingCollectionDeletes == ["S1"])
        #expect(store.get("a")?.collectionIds == [])
        #expect(store.collections().isEmpty)
    }

    @Test func editingATitlesCollectionsMarksItPending() {
        let store = store()
        addSynced(store, "S1", "Kids")
        #expect(store.get("a")?.collectionsPending == false)
        store.setItemCollections("a", collectionIds: ["S1", "S1"])
        #expect(store.get("a")?.collectionIds == ["S1"])
        #expect(store.get("a")?.collectionsPending == true)
    }

    @Test func aSyncResultIsWrittenBackExceptForAPartThatWasEditedWhileItRan() {
        let store = store()
        let kids = store.addCollection("Kids")
        store.setItemCollections("a", collectionIds: [kids.id])
        let base = store.collectionsState()
        let result = CollectionsState(collections: [UserCollection(id: "S1", name: "Kids", synced: true)], pendingDeletes: [],
                                      members: [MemberState(itemId: "a", serverItemId: "a", collectionIds: ["S1"], pending: false)])
        // The title is edited again while the sync runs: its part is skipped (the next sync redoes it).
        store.setItemCollections("a", collectionIds: [kids.id, "other"])
        store.applyCollectionsState(base: base, result: result)
        #expect(store.get("a")?.collectionIds == [kids.id, "other"])
        #expect(store.get("a")?.collectionsPending == true)
        #expect(store.collections().map(\.id) == ["S1"])

        // Nothing changed meanwhile: written back as it is.
        let again = store.collectionsState()
        var next = again
        next.members = [MemberState(itemId: "a", serverItemId: "a", collectionIds: ["S1"], pending: false)]
        store.applyCollectionsState(base: again, result: next)
        #expect(store.get("a")?.collectionIds == ["S1"])
        #expect(store.get("a")?.collectionsPending == false)
    }

    @Test func oldLibraryFilesWithoutTheNewFieldsStillLoadAsPhoneOnlyCollections() throws {
        let json = #"{"collections":[{"id":"L1","name":"Kids"}],"items":[{"id":"a","title":"A","filename":"a.mkv","importedAt":"2026-01-01T00:00:00Z","collectionIds":["L1"]}]}"#
        let file = try JSONDecoder().decode(LibraryFile.self, from: Data(json.utf8))
        #expect(file.collections == [UserCollection(id: "L1", name: "Kids", synced: false, dirty: false)])
        #expect(file.pendingCollectionDeletes.isEmpty)
        #expect(file.items[0].collectionsPending == false)
    }
}

private func collectionsClient() -> CatalogClient { CatalogClient(baseURL: MockHTTP.baseURL, tokens: nil, session: MockHTTP.session) }

extension Network {
@Suite struct CatalogClientCollectionsTests {
    init() { MockHTTP.reset() }

    @Test func listsCreatesRenamesDeletesAndSetsMembershipWithTheDocumentedRoutesAndBodies() async {
        MockHTTP.enqueue(200, #"{"collections":[{"id":"c1","name":"Kids","sort_order":0,"item_count":2}]}"#)
        MockHTTP.enqueue(201, #"{"id":"c2","name":"Favourites","item_count":0}"#)
        MockHTTP.enqueue(200, #"{"id":"c2","name":"Best","sort_order":0,"item_count":0}"#)
        MockHTTP.enqueue(204, "")
        MockHTTP.enqueue(200, #"{"collection_id":"c1","item_id":"i1","present":false}"#)
        let api = collectionsClient().collectionsAPI(token: "token")
        #expect(await api.list() == [RemoteCollection(id: "c1", name: "Kids")])
        #expect(await api.create("Favourites").value == RemoteCollection(id: "c2", name: "Favourites"))
        #expect(await api.rename("c2", "Best").value == RemoteCollection(id: "c2", name: "Best"))
        #expect(await api.delete("c2").outcome == .ok)
        #expect(await api.setMember(collectionId: "c1", itemId: "i1", present: false).outcome == .ok)
        let requests = MockHTTP.requests
        #expect(requests.map { "\($0.method) \($0.path)" } == ["GET /api/catalog/collections", "POST /api/catalog/collections", "PATCH /api/catalog/collections/c2",
                                                              "DELETE /api/catalog/collections/c2", "PUT /api/catalog/items/i1/collections/c1"])
        #expect(requests[0].authorization == "Bearer token")
        #expect(requests[1].body["name"] as? String == "Favourites")
        #expect(requests[2].body["name"] as? String == "Best")
        #expect(requests[4].body["present"] as? Bool == false)
    }

    @Test func answersMapToOutcomesAndAnOlderServerWithoutTheRenameRouteKeepsTheRenamePending() async {
        MockHTTP.enqueue(409)
        MockHTTP.enqueue(404, #"{"error":"collection_not_found"}"#)   // rename: gone
        MockHTTP.enqueue(405)                                         // rename: no such route
        MockHTTP.enqueue(404, #"{"message":"Route PATCH:/api/catalog/collections/c1 not found","error":"Not Found","statusCode":404}"#) // an older server
        MockHTTP.enqueue(404)                                         // delete: already gone
        MockHTTP.enqueue(500)
        let api = collectionsClient().collectionsAPI(token: "token")
        #expect(await api.create("Kids").outcome == .conflict)
        #expect(await api.rename("c1", "Family").outcome == .notFound)
        #expect(await api.rename("c1", "Family").outcome == .failed)
        #expect(await api.rename("c1", "Family").outcome == .failed)
        #expect(await api.delete("c1").outcome == .notFound)
        #expect(await api.setMember(collectionId: "c1", itemId: "i1", present: true).outcome == .failed)
    }

    @Test func anUnreachableServerFailsEveryCallAndListsNothing() async {
        for _ in 0..<3 { MockHTTP.enqueueFailure() }
        let api = collectionsClient().collectionsAPI(token: "token")
        #expect(await api.list() == nil)
        #expect(await api.create("Kids").outcome == .failed)
        #expect(await api.delete("c1").outcome == .failed)
    }

    @Test func theCatalogReadsEachTitlesCollections() throws {
        let item = try JSONDecoder().decode(RemoteCatalogItem.self, from: Data(#"{"id":"i1","title":"Dune","collections":[{"id":"c1","name":"Kids"}],"sources":[]}"#.utf8))
        #expect(item.collections == [RemoteCollection(id: "c1", name: "Kids")])
    }
}
}
