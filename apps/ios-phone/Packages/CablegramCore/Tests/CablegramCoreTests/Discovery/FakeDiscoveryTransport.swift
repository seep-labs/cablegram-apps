import Foundation
@testable import CablegramCore

/// A scripted Telegram for discovery tests: chats, their videos and files, and a record of every call.
final class FakeDiscoveryTransport: DiscoveryTransport, @unchecked Sendable {
    private let lock = NSLock()
    var me: Int64 = 1
    var chats: [Int64: TransportChat] = [:]
    var order: [Int64] = []
    var bots = Set<Int64>()
    /// Per chat: videos (newest first) and files (newest first).
    var videos: [Int64: [TransportVideo]] = [:]
    var files: [Int64: [TransportVideo]] = [:]
    private(set) var calls: [String] = []
    private(set) var searched: [Int64] = []
    private(set) var forwarded: [(to: Int64, from: Int64, ids: [Int64])] = []
    private(set) var deleted: [Int] = []
    private(set) var optimized: [[Int64]] = []
    var floodOnce: [String: Int] = [:]
    var forwardFailures = Set<Int64>()

    func add(_ chat: TransportChat, videos v: [TransportVideo] = [], files f: [TransportVideo] = []) {
        lock.withLock { chats[chat.id] = chat; order.append(chat.id); videos[chat.id] = v; files[chat.id] = f }
    }

    private func record(_ call: String) { lock.withLock { calls.append(call) } }

    func myUserId() async throws -> Int64 { record("getMe"); return me }
    func chatIds(beforeEachRequest: @Sendable () async throws -> Void) async throws -> [Int64] {
        try await beforeEachRequest(); record("loadChats"); return lock.withLock { order }
    }
    func chat(_ chatId: Int64) async -> TransportChat? { record("getChat"); return lock.withLock { chats[chatId] } }
    func isBot(_ userId: Int64) async -> Bool { record("getUser"); return lock.withLock { bots.contains(userId) } }

    private func flood(_ name: String) throws {
        let seconds: Int? = lock.withLock { floodOnce.removeValue(forKey: name) }
        if let seconds { throw TgError(code: 420, name: "FLOOD_WAIT_\(seconds)") }
    }

    func recent(chatId: Int64, fromMessageId: Int64, limit: Int) async throws -> RecentPage {
        record("searchChatMessages"); try flood("recent")
        lock.withLock { searched.append(chatId) }
        let all = lock.withLock { (videos[chatId] ?? []) + (files[chatId] ?? []) }.sorted { $0.messageId > $1.messageId }
        let older = all.filter { fromMessageId == 0 || $0.messageId < fromMessageId }
        let page = Array(older.prefix(limit))
        let more = older.count > page.count
        return RecentPage(messages: page.count, candidates: page, nextFromMessageId: more ? (page.last?.messageId ?? 0) : 0)
    }

    func search(chatId: Int64, files isFiles: Bool, fromMessageId: Int64, limit: Int) async throws -> TransportPage {
        record("searchChatMessages"); try flood("search")
        lock.withLock { searched.append(chatId) }
        let all = lock.withLock { (isFiles ? files[chatId] : videos[chatId]) ?? [] }.sorted { $0.messageId > $1.messageId }
        let older = all.filter { fromMessageId == 0 || $0.messageId < fromMessageId }
        let page = Array(older.prefix(limit))
        return TransportPage(videos: page, nextFromMessageId: older.count > page.count ? (page.last?.messageId ?? 0) : 0)
    }

    func forward(toChatId: Int64, fromChatId: Int64, messageIds: [Int64]) async throws -> [ForwardResult] {
        record("forwardMessages")
        lock.withLock { forwarded.append((toChatId, fromChatId, messageIds)) }
        return messageIds.map { forwardFailures.contains($0) ? .failed(sourceMessageId: $0, reason: "nope") : .sent(sourceMessageId: $0) }
    }

    func downloadThumbnail(_ fileId: Int) async throws -> String { record("downloadFile"); return "/tmp/thumb-\(fileId).jpg" }
    func deleteFile(_ fileId: Int) async throws { lock.withLock { deleted.append(fileId) } }
    func optimizeStorage(chatIds: [Int64]) async throws { lock.withLock { optimized.append(chatIds) } }
}

enum Fixture {
    static let library: Int64 = -1001
    static let minute = 60

    static func channel(_ id: Int64, _ title: String = "Channel") -> TransportChat { TransportChat(id: id, type: .channel, title: title, lastMessageId: id * 10) }
    static func group(_ id: Int64) -> TransportChat { TransportChat(id: id, type: .group, title: "Group \(id)") }
    static func person(_ id: Int64, user: Int64) -> TransportChat { TransportChat(id: id, type: .private, userId: user, title: "Person \(user)") }

    static func video(chat: Int64, id: Int64, minutes: Int = 20, caption: String = "", name: String = "v.mp4", saved: Bool = true) -> TransportVideo {
        TransportVideo(chatId: chat, messageId: id, uniqueId: "u\(chat)_\(id)", name: name, caption: caption, durationSeconds: minutes * 60, sizeBytes: 50_000_000, date: Int(id), canBeSaved: saved)
    }
    static func file(chat: Int64, id: Int64, megabytes: Int = 700, name: String = "film.mkv", mime: String = "video/x-matroska") -> TransportVideo {
        TransportVideo(chatId: chat, messageId: id, uniqueId: "f\(chat)_\(id)", name: name, durationSeconds: 0, sizeBytes: Int64(megabytes) * 1024 * 1024, date: Int(id), isFile: true, mimeType: mime)
    }

    static func graph(_ transport: FakeDiscoveryTransport, storage: MemoryDiscoveryStorage = MemoryDiscoveryStorage()) -> DiscoveryGraph {
        DiscoveryGraph.create(transport: transport, storage: storage, libraryChatId: library)
    }

    static func scan(_ graph: DiscoveryGraph, switches: DiscoverySwitches = DiscoverySwitches()) async throws -> (ScanPermit, [DiscoveryChat]) {
        let permit = graph.controller.grantScan()
        var chats: [DiscoveryChat] = []
        for try await chat in graph.discovery.discoveryChats(permit: permit, switches: switches) { chats.append(chat) }
        return (permit, chats)
    }
}
