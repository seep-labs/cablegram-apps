import Foundation

// Phone only (spec 008). The only TDLib calls discovery may make; `DiscoverySurfaceTests` pins this list.

/// What discovery learns about one chat from TDLib: type, title, protection. No last message, no members.
public struct TransportChat: Sendable, Equatable {
    public var id: Int64
    public var type: TransportChatType
    /// The other user of a one-to-one chat; 0 otherwise.
    public var userId: Int64
    public var title: String
    public var protectedContent: Bool
    /// False once the owner left, was banned from or lost access to a channel or group.
    public var isMember: Bool
    /// Id of the chat's newest message from the local chat list; 0 when there is none.
    public var lastMessageId: Int64

    public init(id: Int64, type: TransportChatType, userId: Int64 = 0, title: String, protectedContent: Bool = false, isMember: Bool = true, lastMessageId: Int64 = 0) {
        self.id = id; self.type = type; self.userId = userId; self.title = title; self.protectedContent = protectedContent
        self.isMember = isMember; self.lastMessageId = lastMessageId
    }
}

public enum TransportChatType: Sendable { case channel, group, `private`, secret }

/// One video, or one file that may be a video, found in a chosen chat.
public struct TransportVideo: Sendable, Equatable {
    public var chatId: Int64
    public var messageId: Int64
    /// Telegram's remote unique id; the library's `tgfile:` key is built from it.
    public var uniqueId: String
    public var name: String
    public var caption: String
    public var durationSeconds: Int
    public var sizeBytes: Int64
    public var date: Int
    public var isFile: Bool
    public var mimeType: String
    public var thumbnailFileId: Int?
    public var canBeSaved: Bool

    public init(chatId: Int64, messageId: Int64, uniqueId: String, name: String = "", caption: String = "", durationSeconds: Int = 0, sizeBytes: Int64 = 0,
                date: Int = 0, isFile: Bool = false, mimeType: String = "video/mp4", thumbnailFileId: Int? = nil, canBeSaved: Bool = true) {
        self.chatId = chatId; self.messageId = messageId; self.uniqueId = uniqueId; self.name = name; self.caption = caption
        self.durationSeconds = durationSeconds; self.sizeBytes = sizeBytes; self.date = date; self.isFile = isFile; self.mimeType = mimeType
        self.thumbnailFileId = thumbnailFileId; self.canBeSaved = canBeSaved
    }
}

public struct TransportPage: Sendable, Equatable {
    public var videos: [TransportVideo]
    public var nextFromMessageId: Int64
    public init(videos: [TransportVideo], nextFromMessageId: Int64) { self.videos = videos; self.nextFromMessageId = nextFromMessageId }
}

/// One page of a chat's newest messages, newest first: how many messages it held, which of them are videos or documents, and where the next
/// page starts (0 when the chat has no older message).
public struct RecentPage: Sendable, Equatable {
    public var messages: Int
    public var candidates: [TransportVideo]
    public var nextFromMessageId: Int64
    public init(messages: Int, candidates: [TransportVideo], nextFromMessageId: Int64) {
        self.messages = messages; self.candidates = candidates; self.nextFromMessageId = nextFromMessageId
    }
}

public enum ForwardResult: Sendable, Equatable {
    case sent(sourceMessageId: Int64)
    case failed(sourceMessageId: Int64, reason: String)

    public var sourceMessageId: Int64 {
        switch self { case let .sent(id), let .failed(id, _): id }
    }
}

public protocol DiscoveryTransport: Sendable {
    func myUserId() async throws -> Int64
    /// Chat ids of the main list and the archive, loaded as far as Telegram has them. `beforeEachRequest` paces the network calls.
    func chatIds(beforeEachRequest: @Sendable () async throws -> Void) async throws -> [Int64]
    func chat(_ chatId: Int64) async -> TransportChat?
    func isBot(_ userId: Int64) async -> Bool
    /// The newest `limit` videos and files of a chat from `fromMessageId` (0 = newest), for the scan's count. Never a whole history.
    func recent(chatId: Int64, fromMessageId: Int64, limit: Int) async throws -> RecentPage
    func search(chatId: Int64, files: Bool, fromMessageId: Int64, limit: Int) async throws -> TransportPage
    func forward(toChatId: Int64, fromChatId: Int64, messageIds: [Int64]) async throws -> [ForwardResult]
    /// The local path of the downloaded thumbnail, or an empty string when Telegram has none.
    func downloadThumbnail(_ fileId: Int) async throws -> String
    func deleteFile(_ fileId: Int) async throws
    func optimizeStorage(chatIds: [Int64]) async throws
}
