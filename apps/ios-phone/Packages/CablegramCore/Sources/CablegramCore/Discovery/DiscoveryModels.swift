import Foundation

// Phone only (spec 008), port of `app.cablegram.discovery` (Android). Nothing in this folder may be used by a TV or sent to a server.

/// A Telegram chat id other than the library channel. Its text form hides the number so it cannot reach a log.
public struct DiscoveryChatId: Hashable, Sendable, CustomStringConvertible, CustomDebugStringConvertible {
    public let raw: Int64
    public init(_ raw: Int64) { self.raw = raw }
    public var description: String { "chat#redacted" }
    public var debugDescription: String { description }
}

public enum ChatKind: String, Codable, Sendable { case channel = "Channel", group = "Group", personal = "Personal", savedMessages = "SavedMessages", bot = "Bot" }

/// A chat the scan may list. Deliberately has no last message, photo bytes or members.
public struct DiscoveryChat: Sendable, Equatable {
    public var id: DiscoveryChatId
    public var kind: ChatKind
    public var title: String
    public var protectedContent: Bool
    /// Id of the chat's newest message, read from the chat list (no request); 0 when unknown. Tells a rescan the chat is quiet.
    public var lastMessageId: Int64

    public init(id: DiscoveryChatId, kind: ChatKind, title: String, protectedContent: Bool, lastMessageId: Int64 = 0) {
        self.id = id; self.kind = kind; self.title = title; self.protectedContent = protectedContent; self.lastMessageId = lastMessageId
    }
}

/// A rescan does not read a chat again when its newest message is the one seen last time (nothing new to count).
public func isUnchangedSinceLastLook(_ row: StoredScanRow?, _ chat: DiscoveryChat) -> Bool {
    guard let row else { return false }
    return row.lastMessageId != 0 && row.lastMessageId == chat.lastMessageId
}

/// Requests per second the scan may send to Telegram, all chats together; every request takes its own slot (FR-007). A FLOOD_WAIT pauses
/// every chat for the time Telegram names and then retries (`RequestThrottle`).
public let scanRequestsPerSecond = 20

/// How many chats a scan reads at once.
public let scanParallelChats = 16

/// The shortest video the scan and the picker look for, until the owner changes it in Advanced settings.
public let defaultMinVideoSeconds = 600

/// Counts for one chat. `approximate` is true when files are included, because a file is only known to be a video once listed.
public struct VideoCount: Sendable, Equatable {
    public var videos: Int
    public var files: Int
    public init(videos: Int, files: Int) { self.videos = videos; self.files = files }
    public var approximate: Bool { files > 0 }
    public var total: Int { videos + files }
}

/// Raised when a call would break the promise: no permission, a chat outside the choice, or a protected chat.
public struct DiscoveryRefused: Error, Equatable, Sendable {
    public var message: String
    public init(_ message: String) { self.message = message }
}

/// One video in a chosen chat, ready for the picker.
public struct DiscoveryVideo: Sendable, Equatable, Hashable {
    public var chatId: DiscoveryChatId
    public var messageId: Int64
    public var uniqueId: String
    public var title: String
    public var durationSeconds: Int
    public var sizeBytes: Int64
    public var date: Int
    public var thumbnailFileId: Int?
    /// True when the chat or the message forbids saving; such a video cannot be selected (FR-010).
    public var protectedContent: Bool

    public init(chatId: DiscoveryChatId, messageId: Int64, uniqueId: String, title: String, durationSeconds: Int, sizeBytes: Int64, date: Int,
                thumbnailFileId: Int?, protectedContent: Bool) {
        self.chatId = chatId; self.messageId = messageId; self.uniqueId = uniqueId; self.title = title; self.durationSeconds = durationSeconds
        self.sizeBytes = sizeBytes; self.date = date; self.thumbnailFileId = thumbnailFileId; self.protectedContent = protectedContent
    }

    /// The library's `stable_source_key` for this video (004 FR-006).
    public var libraryKey: String { "tgfile:\(uniqueId)" }
}

public enum ForwardOutcome: Sendable, Equatable {
    case added(DiscoveryVideo)
    case failed(DiscoveryVideo, reason: String)

    public var video: DiscoveryVideo {
        switch self { case let .added(video), let .failed(video, _): video }
    }
}

/// Switches from Telegram → Advanced settings (US5) and the one under the scan results. All are off until the owner turns them on.
public struct DiscoverySwitches: Sendable, Equatable {
    public var includePersonalChats: Bool
    public var includeSavedMessages: Bool
    /// Chats with bots (often download bots that send videos into the chat). Switched on from the scan results.
    public var includeBotChats: Bool

    public init(includePersonalChats: Bool = false, includeSavedMessages: Bool = false, includeBotChats: Bool = false) {
        self.includePersonalChats = includePersonalChats; self.includeSavedMessages = includeSavedMessages; self.includeBotChats = includeBotChats
    }

    /// Whether a scan looks at anything beyond channels and groups.
    public var any: Bool { includePersonalChats || includeSavedMessages || includeBotChats }
}
