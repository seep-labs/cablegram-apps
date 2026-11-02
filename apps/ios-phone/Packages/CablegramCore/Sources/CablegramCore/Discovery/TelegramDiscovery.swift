import Foundation

/// Phone-only video discovery (spec 008). Every rule that keeps the promise lives here, not in the screens: a live `ScanPermit` for any scan,
/// only chosen chats for any read, and a throttle for every request.
public final class TelegramDiscovery: @unchecked Sendable {
    private let transport: any DiscoveryTransport
    private let gate: DiscoveryGate
    private let throttle: RequestThrottle
    private let libraryChatId: @Sendable () -> Int64?
    private let store: DiscoveryStore
    private let lock = NSLock()
    /// Messages `VideoPager` returned in this process; only these may be forwarded (FR-010).
    private var served = Set<ServedKey>()

    private struct ServedKey: Hashable { var chat: Int64; var message: Int64 }

    public init(transport: any DiscoveryTransport, gate: DiscoveryGate, throttle: RequestThrottle, libraryChatId: @escaping @Sendable () -> Int64?, store: DiscoveryStore) {
        self.transport = transport; self.gate = gate; self.throttle = throttle; self.libraryChatId = libraryChatId; self.store = store
    }

    /// Channels and groups (FR-005), plus personal chats and Saved Messages only if their switch is on.
    public func discoveryChats(permit: ScanPermit, switches: DiscoverySwitches) -> AsyncThrowingStream<DiscoveryChat, Error> {
        AsyncThrowingStream { continuation in
            let task = Task {
                do {
                    try gate.requirePermit(permit)
                    // getMe and getChat read TDLib's local database, so only the network calls are throttled (FR-007).
                    let me = try await transport.myUserId()
                    let throttle = self.throttle
                    let ids = try await throttle.run { try await transport.chatIds { try await throttle.pace() } }
                    for id in ids {
                        try Task.checkCancellation()
                        try gate.requirePermit(permit)
                        if id == libraryChatId() { continue }
                        guard let chat = await transport.chat(id), let kind = await classify(chat, me: me, switches: switches) else { continue }
                        permit.list(id)
                        continuation.yield(DiscoveryChat(id: DiscoveryChatId(id), kind: kind, title: chat.title, protectedContent: chat.protectedContent, lastMessageId: chat.lastMessageId))
                    }
                    continuation.finish()
                } catch {
                    continuation.finish(throwing: error)
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    /// The long videos among a chat's newest `recentWindow` videos and files (FR-004, FR-006). Videos shorter than the owner's minimum length
    /// are left out. A document has no length, so a video-type document counts when it is large; files make the total "about". Each request
    /// takes a throttle slot (FR-007): a page is two (videos, then files).
    public func chatVideoCount(permit: ScanPermit, chatId: DiscoveryChatId) async throws -> VideoCount {
        try gate.requirePermit(permit)
        if !permit.wasListed(chatId.raw) { throw DiscoveryRefused("Chat was not listed by this scan") }
        let minSeconds = store.minVideoSeconds()
        var from: Int64 = 0
        var seen = 0, videos = 0, files = 0
        while seen < Self.recentWindow {
            try gate.requirePermit(permit)
            let want = Self.recentWindow - seen, start = from
            let page = try await throttle.run(requests: 2) { try await self.transport.recent(chatId: chatId.raw, fromMessageId: start, limit: want) }
            if page.messages == 0 { break }
            seen += page.messages
            for v in page.candidates {
                if !v.isFile { if v.durationSeconds >= minSeconds { videos += 1 } }
                else if Self.looksLikeVideo(v) && v.sizeBytes >= Self.documentMinBytes(minSeconds) { files += 1 }
            }
            if page.nextFromMessageId == 0 || page.nextFromMessageId == from { break }
            from = page.nextFromMessageId
        }
        return VideoCount(videos: videos, files: files)
    }

    /// Whether a chosen chat can still be read: the owner is still a member of it. Looks at the chosen chat only.
    public func isReadable(_ chatId: Int64) async throws -> Bool {
        try gate.requireChosen(chatId)
        return await transport.chat(chatId)?.isMember == true
    }

    /// A pager over the chosen chats only. Refuses any chat outside the stored choice before touching Telegram (FR-009).
    public func videoPager(chatIds: Set<DiscoveryChatId>) throws -> VideoPager {
        for id in chatIds { try gate.requireChosen(id.raw) }
        return VideoPager(owner: self, chats: chatIds.map(\.raw).sorted(), minSeconds: store.minVideoSeconds())
    }

    public final class VideoPager: @unchecked Sendable {
        private let owner: TelegramDiscovery
        private let minSeconds: Int
        private let lock = NSLock()
        private var sources: [Source]
        private var protectedChats: [Int64: Bool] = [:]

        private final class Source {
            let chatId: Int64, files: Bool
            var from: Int64 = 0, done = false
            var buffer: [DiscoveryVideo] = []
            init(chatId: Int64, files: Bool) { self.chatId = chatId; self.files = files }
        }

        fileprivate init(owner: TelegramDiscovery, chats: [Int64], minSeconds: Int) {
            self.owner = owner; self.minSeconds = minSeconds
            sources = chats.flatMap { [Source(chatId: $0, files: false), Source(chatId: $0, files: true)] }
        }

        public var exhausted: Bool { lock.withLock { sources.allSatisfy { $0.done && $0.buffer.isEmpty } } }

        /// The next `limit` videos, newest first across all chosen chats. An empty page ends a source (a zero cursor is not reliable).
        public func next(limit: Int = 50) async throws -> [DiscoveryVideo] {
            for source in sources {
                while true {
                    let needsMore = lock.withLock { !source.done && source.buffer.count < limit }
                    if !needsMore { break }
                    try await fill(source, limit: limit)
                }
            }
            let out: [DiscoveryVideo] = lock.withLock {
                var out: [DiscoveryVideo] = []
                while out.count < limit {
                    guard let best = sources.filter({ !$0.buffer.isEmpty }).max(by: { $0.buffer[0].date < $1.buffer[0].date }) else { break }
                    out.append(best.buffer.removeFirst())
                }
                return out
            }
            owner.markServed(out)
            return out
        }

        private func fill(_ source: Source, limit: Int) async throws {
            try owner.gate.requireChosen(source.chatId)
            let known = lock.withLock { protectedChats[source.chatId] }
            let protectedChat: Bool
            if let known { protectedChat = known } else {
                let chatId = source.chatId
                let chat = try await owner.throttle.run { await self.owner.transport.chat(chatId) }
                protectedChat = chat?.protectedContent ?? true
                lock.withLock { protectedChats[source.chatId] = protectedChat }
            }
            let from = lock.withLock { source.from }, chatId = source.chatId, files = source.files
            let page = try await owner.throttle.run { try await self.owner.transport.search(chatId: chatId, files: files, fromMessageId: from, limit: limit) }
            lock.withLock {
                // A zero cursor is not trusted as the end (and must not send the next request back to the newest message): carry on from the
                // oldest message of this page, and the source ends on an empty page.
                let next = page.nextFromMessageId != 0 ? page.nextFromMessageId : (page.videos.map(\.messageId).min() ?? source.from)
                if page.videos.isEmpty || next == source.from { source.done = true }
                source.from = next
                for v in page.videos where source.files ? (TelegramDiscovery.looksLikeVideo(v) && v.sizeBytes >= TelegramDiscovery.documentMinBytes(minSeconds)) : v.durationSeconds >= minSeconds {
                    let title = v.caption.trimmingCharacters(in: .whitespaces).isEmpty ? v.name : v.caption
                    source.buffer.append(DiscoveryVideo(chatId: DiscoveryChatId(v.chatId), messageId: v.messageId, uniqueId: v.uniqueId, title: title, durationSeconds: v.durationSeconds,
                                                        sizeBytes: v.sizeBytes, date: v.date, thumbnailFileId: v.thumbnailFileId, protectedContent: protectedChat || !v.canBeSaved))
                }
            }
        }
    }

    fileprivate func markServed(_ videos: [DiscoveryVideo]) {
        lock.withLock { for v in videos { served.insert(ServedKey(chat: v.chatId.raw, message: v.messageId)) } }
    }

    /// Forwards the selected videos into the library channel and nowhere else (FR-010). There is no destination parameter. Protected videos are
    /// refused here, not only in the screen.
    public func forwardToLibrary(_ selection: [DiscoveryVideo]) async throws -> [ForwardOutcome] {
        guard let library = libraryChatId() else { throw DiscoveryRefused("No library channel") }
        for v in selection {
            try gate.requireChosen(v.chatId.raw)
            if !lock.withLock({ served.contains(ServedKey(chat: v.chatId.raw, message: v.messageId)) }) { throw DiscoveryRefused("Message was not offered by the picker") }
        }
        var outcomes: [ForwardOutcome] = []
        let blocked = selection.filter(\.protectedContent), allowed = selection.filter { !$0.protectedContent }
        for v in blocked { outcomes.append(.failed(v, reason: "This chat doesn't allow saving")) }
        var order: [Int64] = []
        var byChat: [Int64: [DiscoveryVideo]] = [:]
        for v in allowed { if byChat[v.chatId.raw] == nil { order.append(v.chatId.raw) }; byChat[v.chatId.raw, default: []].append(v) }
        for chat in order {
            for start in stride(from: 0, to: byChat[chat]!.count, by: Self.forwardBatch) {
                let batch = Array(byChat[chat]![start..<min(start + Self.forwardBatch, byChat[chat]!.count)])
                let byMessage = Dictionary(batch.map { ($0.messageId, $0) }, uniquingKeysWith: { first, _ in first })
                let results: [ForwardResult]
                do {
                    results = try await throttle.run { try await self.transport.forward(toChatId: library, fromChatId: chat, messageIds: batch.map(\.messageId)) }
                } catch let error as TgError {
                    for v in batch { outcomes.append(.failed(v, reason: error.name)) }
                    continue
                }
                for r in results {
                    guard let video = byMessage[r.sourceMessageId] else { continue }
                    switch r {
                    case .sent: outcomes.append(.added(video))
                    case let .failed(_, reason): outcomes.append(.failed(video, reason: reason))
                    }
                }
            }
        }
        return outcomes
    }

    /// Downloads a thumbnail and records it so `purgeChat` can delete exactly what was fetched (FR-008).
    public func thumbnail(for video: DiscoveryVideo) async throws -> String? {
        guard let file = video.thumbnailFileId else { return nil }
        try gate.requireChosen(video.chatId.raw)
        store.recordThumbnail(chatId: video.chatId.raw, fileId: file)
        let path = try await throttle.run { try await self.transport.downloadThumbnail(file) }
        return path.isEmpty ? nil : path
    }

    /// Deletes every cached thumbnail of a chat now, then forgets the chat (untick, switch off, left chat).
    public func purgeChat(_ chatId: Int64) async {
        for file in store.thumbnails(of: chatId) { try? await transport.deleteFile(file) }
        try? await transport.optimizeStorage(chatIds: [chatId])
        store.dropChat(chatId)
    }

    static func looksLikeVideo(_ v: TransportVideo) -> Bool {
        v.mimeType.hasPrefix("video/") || Self.videoExtensions.contains { v.name.lowercased().hasSuffix(".\($0)") }
    }

    private func classify(_ chat: TransportChat, me: Int64, switches: DiscoverySwitches) async -> ChatKind? {
        switch chat.type {
        case .channel: return .channel
        case .group: return .group
        case .secret: return nil
        case .private:
            if chat.userId == me { return switches.includeSavedMessages ? .savedMessages : nil }
            if chat.userId == Self.telegramServiceUser { return nil }
            // With both switches off a one-to-one chat is never looked at, not even to ask whether it is a bot.
            if !switches.includePersonalChats && !switches.includeBotChats { return nil }
            // getUser reads TDLib's local database (an offline method), so it needs no throttle.
            if await transport.isBot(chat.userId) { return switches.includeBotChats ? .bot : nil }
            return switches.includePersonalChats ? .personal : nil
        }
    }

    static let telegramServiceUser: Int64 = 777_000
    static let forwardBatch = 100
    /// The scan looks at this many of a chat's newest videos and files.
    static let recentWindow = 100
    /// A video-type document at least this big counts as a long video; with "any length" every one counts.
    static let documentMinBytes: Int64 = 100 * 1024 * 1024
    static func documentMinBytes(_ minSeconds: Int) -> Int64 { minSeconds <= 0 ? 0 : documentMinBytes }
    static let videoExtensions = ["mp4", "mkv", "mov", "avi", "webm", "m4v", "ts", "m2ts", "wmv", "flv", "3gp"]
}
