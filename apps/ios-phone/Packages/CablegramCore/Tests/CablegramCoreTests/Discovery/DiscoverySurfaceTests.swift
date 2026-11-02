import Foundation
import Testing

// Port of `DiscoverySurfaceTest.kt`: pins what video discovery may ask TDLib. Adding a call needs review against the privacy page.

@Suite struct DiscoverySurfaceTests {
    static let allowedTdRequests: Set<String> = [
        // Chat list and chat types (FR-005), read from TDLib's local database or one page at a time.
        "getMe", "loadChats", "getChats", "getChat", "getSupergroup", "getBasicGroup", "getUser",
        // The newest videos and documents of a chat, never a whole history (FR-004).
        "searchChatMessages",
        // Add to library (FR-010) and the thumbnails of chosen chats (FR-008).
        "forwardMessages", "downloadFile", "deleteFile", "optimizeStorage",
    ]

    static let allowedTransportMembers: Set<String> = [
        "myUserId", "chatIds", "chat", "isBot", "recent", "search", "forward", "downloadThumbnail", "deleteFile", "optimizeStorage",
    ]

    private static var discoveryFolder: URL {
        var root = URL(fileURLWithPath: #filePath)
        for _ in 0..<4 { root.deleteLastPathComponent() }
        return root.appendingPathComponent("Sources/CablegramCore/Discovery")
    }

    private static func source(_ name: String) throws -> String { try String(contentsOf: discoveryFolder.appendingPathComponent(name), encoding: .utf8) }

    @Test func discoveryAsksTdlibOnlyForTheAllowedCalls() throws {
        let text = try Self.source("TdDiscoveryTransport.swift")
        let regex = try NSRegularExpression(pattern: #"call\("(\w+)""#)
        let used = Set(regex.matches(in: text, range: NSRange(text.startIndex..., in: text)).compactMap { Range($0.range(at: 1), in: text).map { String(text[$0]) } })
        #expect(!used.isEmpty)
        #expect(used == Self.allowedTdRequests, "extra: \(used.subtracting(Self.allowedTdRequests).sorted()), missing: \(Self.allowedTdRequests.subtracting(used).sorted())")
    }

    @Test func theTransportProtocolExposesOnlyTheAllowedMembers() throws {
        let text = try Self.source("DiscoveryTransport.swift")
        var inside = false
        var names = Set<String>()
        let member = try NSRegularExpression(pattern: #"^\s*func\s+(\w+)\s*[(<]"#)
        for line in text.components(separatedBy: "\n") {
            if !inside { inside = line.contains("public protocol DiscoveryTransport"); continue }
            if line.hasPrefix("}") { break }
            if let m = member.firstMatch(in: line, range: NSRange(line.startIndex..., in: line)), let r = Range(m.range(at: 1), in: line) { names.insert(String(line[r])) }
        }
        #expect(names == Self.allowedTransportMembers)
    }

    /// A whole history also delivers service messages, and one of them (a changed chat background) makes the TDLib wrapper throw (Android crash,
    /// 2026-10-28). Discovery asks for videos and documents only.
    @Test func discoveryNeverReadsAWholeHistory() throws {
        for file in try FileManager.default.contentsOfDirectory(atPath: Self.discoveryFolder.path) where file.hasSuffix(".swift") {
            let text = try Self.source(file)
            #expect(!text.contains("getChatHistory"), "\(file) reads a whole history")
        }
    }

    @Test func discoveryHasNoNetworkOfItsOwn() throws {
        for file in try FileManager.default.contentsOfDirectory(atPath: Self.discoveryFolder.path) where file.hasSuffix(".swift") {
            let text = try Self.source(file)
            for banned in ["URLSession", "URLRequest", "CatalogClient", "import UIKit"] { #expect(!text.contains(banned), "\(file) uses \(banned)") }
        }
    }
}
