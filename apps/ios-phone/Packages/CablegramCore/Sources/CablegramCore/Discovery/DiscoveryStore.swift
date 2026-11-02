import Foundation

// Phone only (spec 008). Everything here stays on the phone, encrypted, and is never sent anywhere.

/// Where the encrypted document lives. The phone encrypts it into a file; tests use memory.
public protocol DiscoveryStorage: Sendable {
    func read() -> String?
    func write(_ json: String)
    func clear()
}

public struct StoredChoice: Codable, Sendable, Equatable {
    public var chatId: Int64
    public var kind: ChatKind
    public var title: String
    public var chosenAt: Int64
    public init(chatId: Int64, kind: ChatKind, title: String, chosenAt: Int64) { self.chatId = chatId; self.kind = kind; self.title = title; self.chosenAt = chosenAt }
}

public struct StoredScanRow: Codable, Sendable, Equatable {
    public var chatId: Int64
    public var kind: ChatKind
    public var title: String
    public var protectedContent: Bool
    public var videos: Int
    public var files: Int
    /// When this chat was last read.
    public var scannedAt: Int64
    /// Newest message id of the chat when it was read; a rescan skips the chat while it is unchanged.
    public var lastMessageId: Int64

    public init(chatId: Int64, kind: ChatKind, title: String, protectedContent: Bool, videos: Int, files: Int, scannedAt: Int64, lastMessageId: Int64 = 0) {
        self.chatId = chatId; self.kind = kind; self.title = title; self.protectedContent = protectedContent; self.videos = videos; self.files = files
        self.scannedAt = scannedAt; self.lastMessageId = lastMessageId
    }

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        chatId = try c.decode(Int64.self, forKey: .chatId); kind = try c.decode(ChatKind.self, forKey: .kind); title = try c.decode(String.self, forKey: .title)
        protectedContent = try c.decode(Bool.self, forKey: .protectedContent); videos = try c.decode(Int.self, forKey: .videos)
        files = try c.decode(Int.self, forKey: .files); scannedAt = try c.decode(Int64.self, forKey: .scannedAt)
        lastMessageId = try c.decodeIfPresent(Int64.self, forKey: .lastMessageId) ?? 0
    }
}

public struct DiscoveryState: Codable, Sendable, Equatable {
    /// Starts empty: nothing is chosen until the owner ticks it (FR-008).
    public var choice: [StoredChoice] = []
    public var scan: [StoredScanRow] = []
    /// Chats already counted by an unfinished scan, so Look again can continue (US1 acceptance 5).
    public var scanned: [Int64] = []
    public var scanCompleted = false
    public var includePersonalChats = false
    public var includeSavedMessages = false
    public var includeBotChats = false
    /// Videos shorter than this are left out of the scan and the picker; 0 means any length.
    public var minVideoSeconds = defaultMinVideoSeconds
    /// TDLib file ids of thumbnails downloaded for a chat, so unticking can delete exactly those.
    public var thumbnails: [String: [Int]] = [:]

    public init() {}

    public init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        choice = try c.decodeIfPresent([StoredChoice].self, forKey: .choice) ?? []
        scan = try c.decodeIfPresent([StoredScanRow].self, forKey: .scan) ?? []
        scanned = try c.decodeIfPresent([Int64].self, forKey: .scanned) ?? []
        scanCompleted = try c.decodeIfPresent(Bool.self, forKey: .scanCompleted) ?? false
        includePersonalChats = try c.decodeIfPresent(Bool.self, forKey: .includePersonalChats) ?? false
        includeSavedMessages = try c.decodeIfPresent(Bool.self, forKey: .includeSavedMessages) ?? false
        includeBotChats = try c.decodeIfPresent(Bool.self, forKey: .includeBotChats) ?? false
        minVideoSeconds = try c.decodeIfPresent(Int.self, forKey: .minVideoSeconds) ?? defaultMinVideoSeconds
        thumbnails = try c.decodeIfPresent([String: [Int]].self, forKey: .thumbnails) ?? [:]
    }
}

public final class DiscoveryStore: @unchecked Sendable {
    private let storage: DiscoveryStorage
    private let lock = NSLock()
    private var state: DiscoveryState
    private var retired = false

    public init(storage: DiscoveryStorage) {
        self.storage = storage
        state = storage.read().flatMap { try? JSONDecoder().decode(DiscoveryState.self, from: Data($0.utf8)) } ?? DiscoveryState()
    }

    private func update(_ change: (inout DiscoveryState) -> Void) {
        lock.withLock {
            if retired { return }
            change(&state)
            if let data = try? JSONEncoder().encode(state), let text = String(data: data, encoding: .utf8) { storage.write(text) }
        }
    }

    public func snapshot() -> DiscoveryState { lock.withLock { state } }
    public func choice() -> Set<Int64> { Set(snapshot().choice.map(\.chatId)) }
    public func switches() -> DiscoverySwitches {
        let s = snapshot()
        return DiscoverySwitches(includePersonalChats: s.includePersonalChats, includeSavedMessages: s.includeSavedMessages, includeBotChats: s.includeBotChats)
    }

    public func minVideoSeconds() -> Int { snapshot().minVideoSeconds }

    /// The counts depend on this length, so a change clears the scan results (the chosen chats stay).
    public func setMinVideoSeconds(_ seconds: Int) {
        update { $0.minVideoSeconds = seconds; $0.scan = []; $0.scanned = []; $0.scanCompleted = false }
    }

    public func setChoice(_ chosen: [StoredChoice]) { update { $0.choice = chosen } }
    public func setSwitches(_ switches: DiscoverySwitches) {
        update { $0.includePersonalChats = switches.includePersonalChats; $0.includeSavedMessages = switches.includeSavedMessages; $0.includeBotChats = switches.includeBotChats }
    }

    public func addScanRow(_ row: StoredScanRow) {
        update {
            $0.scan.removeAll { $0.chatId == row.chatId }
            $0.scan.append(row)
            if !$0.scanned.contains(row.chatId) { $0.scanned.append(row.chatId) }
        }
    }
    public func finishScan(completed: Bool) { update { $0.scanCompleted = completed } }
    public func restartScan() { update { $0.scanned = []; $0.scanCompleted = false } }

    public func recordThumbnail(chatId: Int64, fileId: Int) {
        update {
            var ids = $0.thumbnails[String(chatId)] ?? []
            if !ids.contains(fileId) { ids.append(fileId) }
            $0.thumbnails[String(chatId)] = ids
        }
    }
    public func thumbnails(of chatId: Int64) -> [Int] { snapshot().thumbnails[String(chatId)] ?? [] }

    /// Forgets a chat everywhere: choice, scan row and thumbnail ledger. The files are deleted by `TelegramDiscovery.purgeChat`.
    public func dropChat(_ chatId: Int64) {
        update {
            $0.choice.removeAll { $0.chatId == chatId }
            $0.scan.removeAll { $0.chatId == chatId }
            $0.scanned.removeAll { $0 == chatId }
            $0.thumbnails[String(chatId)] = nil
        }
    }

    /// After a disconnect: whoever still holds this store can no longer write to the phone's storage.
    public func retire() { lock.withLock { retired = true; state = DiscoveryState() } }

    /// Disconnect Telegram: the choice, the switches, the counts and the ledger all go (spec edge cases).
    public func clear() { lock.withLock { state = DiscoveryState(); storage.clear() } }
}

/// In memory only, for tests.
public final class MemoryDiscoveryStorage: DiscoveryStorage, @unchecked Sendable {
    private let lock = NSLock()
    private var text: String?
    public init(_ text: String? = nil) { self.text = text }
    public func read() -> String? { lock.withLock { text } }
    public func write(_ json: String) { lock.withLock { text = json } }
    public func clear() { lock.withLock { text = nil } }
}
