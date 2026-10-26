import Foundation

extension CatalogClient {
    /// The account's collection routes for the library sync (contracts/collections.md).
    public func collectionsAPI(token: String) -> CollectionsAPI { RemoteCollectionsAPI(client: self, token: token) }
}

private struct RemoteCollectionsAPI: CollectionsAPI {
    let client: CatalogClient
    let token: String

    private func outcome(_ status: Int?) -> CallOutcome {
        guard let status else { return .failed }
        switch status {
        case 200...299: return .ok
        case 409: return .conflict
        case 404: return .notFound
        default: return .failed
        }
    }

    private func collection(_ reply: APIReply) -> RemoteCollection? { reply.isSuccess ? reply.decode(RemoteCollection.self) : nil }

    func list() async -> [RemoteCollection]? {
        let reply = await client.send("GET", "/api/catalog/collections", token: token)
        guard reply.isSuccess, let rows = reply.object?["collections"] as? [[String: Any]] else { return nil }
        return rows.compactMap { row in
            guard let id = row["id"] as? String, let name = row["name"] as? String else { return nil }
            return RemoteCollection(id: id, name: name)
        }
    }

    func create(_ name: String) async -> CollectionCall<RemoteCollection> {
        let reply = await client.send("POST", "/api/catalog/collections", token: token, json: ["name": name])
        return CollectionCall(outcome(reply.status), collection(reply))
    }

    func rename(_ id: String, _ name: String) async -> CollectionCall<RemoteCollection> {
        let reply = await client.send("PATCH", "/api/catalog/collections/\(id)", token: token, json: ["name": name])
        // A server older than the rename route answers 405, or 404 with "Route PATCH:… not found". Only the server's own
        // `collection_not_found` means the collection is gone; anything else keeps the rename pending.
        let gone = reply.status == 404 && reply.errorCode == "collection_not_found"
        let routeMissing = reply.status == 405 || (reply.status == 404 && !gone)
        return CollectionCall(routeMissing ? .failed : outcome(reply.status), collection(reply))
    }

    func delete(_ id: String) async -> CollectionCall<Void> {
        CollectionCall(outcome(await client.send("DELETE", "/api/catalog/collections/\(id)", token: token).status), ())
    }

    func setMember(collectionId: String, itemId: String, present: Bool) async -> CollectionCall<Void> {
        CollectionCall(outcome(await client.send("PUT", "/api/catalog/items/\(itemId)/collections/\(collectionId)", token: token, json: ["present": present]).status), ())
    }
}
