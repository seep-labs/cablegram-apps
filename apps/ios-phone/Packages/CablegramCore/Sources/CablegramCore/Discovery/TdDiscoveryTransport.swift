import Foundation

/// `DiscoveryTransport` over the phone's one TDLib client (the same one `TdlibTelegramApi` uses).
public final class TdDiscoveryTransport: DiscoveryTransport, @unchecked Sendable {
    private let td: any TdTransport

    public init(transport: any TdTransport) { td = transport }

    @discardableResult
    private func call(_ type: String, _ fields: [String: Any] = [:]) async throws -> TdObject {
        var request = fields
        request["@type"] = type
        return try await td.send(TdObject(request))
    }

    public func myUserId() async throws -> Int64 { try await call("getMe").int64("id") }

    public func chatIds(beforeEachRequest: @Sendable () async throws -> Void) async throws -> [Int64] {
        var ids: [Int64] = []
        var seen = Set<Int64>()
        for list in ["chatListMain", "chatListArchive"] {
            // loadChats answers 404 once every chat of the list is loaded.
            while true {
                try await beforeEachRequest()
                do { try await call("loadChats", ["chat_list": ["@type": list], "limit": 100]) } catch let error as TgError where error.code == 404 { break }
            }
            for id in try await call("getChats", ["chat_list": ["@type": list], "limit": 10_000]).int64s("chat_ids") where seen.insert(id).inserted { ids.append(id) }
        }
        return ids
    }

    public func chat(_ chatId: Int64) async -> TransportChat? {
        guard let chat = try? await call("getChat", ["chat_id": chatId]), let type = chat.object("type") else { return nil }
        let kind: TransportChatType
        var userId: Int64 = 0
        switch type.type {
        case "chatTypeSupergroup": kind = type.bool("is_channel") ? .channel : .group
        case "chatTypeBasicGroup": kind = .group
        case "chatTypePrivate": kind = .private; userId = type.int64("user_id")
        case "chatTypeSecret": kind = .secret; userId = type.int64("user_id")
        default: return nil
        }
        return TransportChat(id: chat.int64("id"), type: kind, userId: userId, title: chat.string("title"), protectedContent: chat.bool("has_protected_content"),
                             isMember: await isMember(type), lastMessageId: chat.object("last_message")?.int64("id") ?? 0)
    }

    /// Left, banned and restricted-out members can no longer read a chat as a member (spec 008 edge case).
    private func isMember(_ type: TdObject) async -> Bool {
        let status: TdObject?
        switch type.type {
        case "chatTypeSupergroup": status = try? await call("getSupergroup", ["supergroup_id": type.int64("supergroup_id")]).object("status")
        case "chatTypeBasicGroup": status = try? await call("getBasicGroup", ["basic_group_id": type.int64("basic_group_id")]).object("status")
        default: return true
        }
        guard let status else { return true }
        switch status.type {
        case "chatMemberStatusLeft", "chatMemberStatusBanned": return false
        case "chatMemberStatusRestricted": return status.bool("is_member")
        default: return true
        }
    }

    public func isBot(_ userId: Int64) async -> Bool {
        (try? await call("getUser", ["user_id": userId]))?.object("type")?.type == "userTypeBot"
    }

    /// The newest videos and documents of a chat, newest first (at most `limit`). Only those two kinds are asked for: reading a whole history
    /// also delivers service messages, and one of them (a changed chat background) makes the TDLib wrapper throw on its receiver thread.
    public func recent(chatId: Int64, fromMessageId: Int64, limit: Int) async throws -> RecentPage {
        async let videos = searchRaw(chatId: chatId, files: false, fromMessageId: fromMessageId, limit: limit)
        async let files = searchRaw(chatId: chatId, files: true, fromMessageId: fromMessageId, limit: limit)
        let answers = try await [videos, files]
        let found = answers.flatMap { $0.objects("messages") }.sorted { $0.int64("date") > $1.int64("date") }.prefix(limit)
        // A short answer does not mean the end (TDLib may return fewer than asked): only a zero cursor from both searches does.
        let more = answers.contains { $0.int64("next_from_message_id") != 0 }
        let oldest = found.map { $0.int64("id") }.min() ?? 0
        return RecentPage(messages: found.count, candidates: found.compactMap(Self.video), nextFromMessageId: (!more || found.isEmpty) ? 0 : oldest)
    }

    public func search(chatId: Int64, files: Bool, fromMessageId: Int64, limit: Int) async throws -> TransportPage {
        let found = try await searchRaw(chatId: chatId, files: files, fromMessageId: fromMessageId, limit: limit)
        return TransportPage(videos: found.objects("messages").compactMap(Self.video), nextFromMessageId: found.int64("next_from_message_id"))
    }

    private func searchRaw(chatId: Int64, files: Bool, fromMessageId: Int64, limit: Int) async throws -> TdObject {
        try await call("searchChatMessages", ["chat_id": chatId, "query": "", "from_message_id": fromMessageId, "offset": 0, "limit": limit,
                                              "filter": ["@type": files ? "searchMessagesFilterDocument" : "searchMessagesFilterVideo"]])
    }

    public func forward(toChatId: Int64, fromChatId: Int64, messageIds: [Int64]) async throws -> [ForwardResult] {
        // Telegram wants ids in strictly increasing order; searches return newest first.
        let ordered = messageIds.sorted()
        // Updates are hot: listen before sending, so a fast result cannot be missed (as uploadVideo does).
        let outcomes = ForwardOutcomes()
        let updates = td.updates
        let listener = Task {
            for await update in updates {
                switch update.type {
                case "updateMessageSendSucceeded": outcomes.set(update.int64("old_message_id"), .sent)
                case "updateMessageSendFailed": outcomes.set(update.int64("old_message_id"), .failed(update.object("error")?.string("message") ?? "failed"))
                default: break
                }
            }
        }
        defer { listener.cancel() }
        let answer = try await call("forwardMessages", ["chat_id": toChatId, "from_chat_id": fromChatId, "message_ids": ordered, "send_copy": false, "remove_caption": false])
        // `messages` holds a null for a message Telegram did not forward, so positions must not shift.
        let sent = answer.fields["messages"] as? [Any] ?? []
        var results: [ForwardResult] = []
        var pending: [(temporary: Int64, source: Int64)] = []
        for (index, entry) in sent.enumerated() {
            let source = index < ordered.count ? ordered[index] : 0
            if let message = entry as? [String: Any] { pending.append((TdObject(message).int64("id"), source)) }
            else { results.append(.failed(sourceMessageId: source, reason: "not forwarded")) }
        }
        let ids = pending.map(\.temporary)
        // A message whose result never arrived (network dropped) counts as failed, so the picker never hangs.
        let done = (try? await withTimeout(seconds: Self.forwardTimeout) { await outcomes.waitForAll(ids) }) ?? [:]
        for (temporary, source) in pending {
            switch done[temporary] ?? outcomes.result(for: temporary) {
            case .sent?: results.append(.sent(sourceMessageId: source))
            case let .failed(reason)?: results.append(.failed(sourceMessageId: source, reason: reason))
            case nil: results.append(.failed(sourceMessageId: source, reason: "no answer from Telegram"))
            }
        }
        return results
    }

    public func downloadThumbnail(_ fileId: Int) async throws -> String {
        let local = try await call("downloadFile", ["file_id": fileId, "priority": 1, "offset": 0, "limit": 0, "synchronous": true]).object("local")
        return local?.bool("is_downloading_completed") == true ? (local?.string("path") ?? "") : ""
    }

    public func deleteFile(_ fileId: Int) async throws { try await call("deleteFile", ["file_id": fileId]) }

    public func optimizeStorage(chatIds: [Int64]) async throws {
        try await call("optimizeStorage", ["size": 0, "ttl": -1, "count": -1, "immunity_delay": 0, "file_types": [["@type": "fileTypeThumbnail"], ["@type": "fileTypePhoto"]],
                                           "chat_ids": chatIds, "exclude_chat_ids": [Int64](), "return_deleted_file_statistics": false, "chat_limit": 1])
    }

    private static let forwardTimeout = 60.0

    private static func video(_ m: TdObject) -> TransportVideo? {
        guard let c = m.object("content") else { return nil }
        let chat = m.int64("chat_id"), id = m.int64("id"), date = m.int("date"), canSave = m.bool("can_be_saved")
        switch c.type {
        case "messageVideo":
            let v = c.object("video") ?? TdObject([:]), file = v.object("video") ?? TdObject([:])
            return TransportVideo(chatId: chat, messageId: id, uniqueId: file.object("remote")?.string("unique_id") ?? "", name: v.string("file_name"),
                                  caption: c.object("caption")?.string("text") ?? "", durationSeconds: v.int("duration"), sizeBytes: file.int64("size"), date: date, isFile: false,
                                  mimeType: v.string("mime_type"), thumbnailFileId: v.object("thumbnail")?.object("file")?.int("id"), canBeSaved: canSave)
        case "messageDocument":
            let d = c.object("document") ?? TdObject([:]), file = d.object("document") ?? TdObject([:])
            return TransportVideo(chatId: chat, messageId: id, uniqueId: file.object("remote")?.string("unique_id") ?? "", name: d.string("file_name"),
                                  caption: c.object("caption")?.string("text") ?? "", durationSeconds: 0, sizeBytes: file.int64("size"), date: date, isFile: true,
                                  mimeType: d.string("mime_type"), thumbnailFileId: d.object("thumbnail")?.object("file")?.int("id"), canBeSaved: canSave)
        default: return nil
        }
    }
}

/// Results of forwards in flight, keyed by the temporary message id TDLib gave them.
private final class ForwardOutcomes: @unchecked Sendable {
    enum Result: Sendable, Equatable { case sent, failed(String) }
    private let lock = NSLock()
    private var results: [Int64: Result] = [:]

    func set(_ oldId: Int64, _ result: Result) { lock.withLock { results[oldId] = result } }
    func result(for id: Int64) -> Result? { lock.withLock { results[id] } }

    /// Polls until every id has a result; the caller's timeout ends the wait.
    func waitForAll(_ ids: [Int64]) async -> [Int64: Result] {
        while !Task.isCancelled {
            let snapshot = lock.withLock { results }
            if ids.allSatisfy({ snapshot[$0] != nil }) { return snapshot }
            try? await Task.sleep(nanoseconds: 50_000_000)
        }
        return lock.withLock { results }
    }
}
