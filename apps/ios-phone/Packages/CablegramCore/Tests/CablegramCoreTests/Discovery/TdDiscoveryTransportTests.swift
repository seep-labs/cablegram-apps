import Foundation
import Testing
@testable import CablegramCore

@Suite struct TdDiscoveryTransportTests {
    private func videoMessage(id: Int64, chat: Int64 = 10, date: Int = 100, minutes: Int = 20) -> [String: Any] {
        ["@type": "message", "id": id, "chat_id": chat, "date": date, "can_be_saved": true,
         "content": ["@type": "messageVideo", "caption": ["text": "cap \(id)"],
                     "video": ["duration": minutes * 60, "file_name": "v\(id).mp4", "mime_type": "video/mp4", "thumbnail": ["file": ["id": 9]],
                               "video": ["id": 1, "size": 5_000, "remote": ["unique_id": "u\(id)"]]]]]
    }

    private func documentMessage(id: Int64, date: Int = 100) -> [String: Any] {
        ["@type": "message", "id": id, "chat_id": 10, "date": date, "can_be_saved": false,
         "content": ["@type": "messageDocument", "caption": ["text": ""],
                     "document": ["file_name": "film.mkv", "mime_type": "video/x-matroska", "document": ["id": 2, "size": 900_000_000, "remote": ["unique_id": "d\(id)"]]]]]
    }

    @Test func loadsEveryChatOfBothListsUntilTelegramAnswers404AndNamesNoOneTwice() async throws {
        let td = FakeTd()
        let loads = AttemptLog()
        td.handlers["loadChats"] = { request in
            let list = (request["chat_list"] as? [String: Any])?["@type"] as? String ?? ""
            if loads.count(list) >= 2 { return ["@type": "error", "code": 404, "message": "Not Found"] }
            loads.add(list)
            return ["@type": "ok"]
        }
        td.handlers["getChats"] = { request in
            (request["chat_list"] as? [String: Any])?["@type"] as? String == "chatListMain" ? ["@type": "chats", "chat_ids": [1, 2, 3]] : ["@type": "chats", "chat_ids": [3, 4]]
        }
        let paced = AttemptLog()
        let ids = try await TdDiscoveryTransport(transport: td).chatIds { paced.add(0) }
        #expect(ids == [1, 2, 3, 4])
        #expect(paced.values.count == 6, "every loadChats request takes a throttle slot: two successes and a 404 per list")
    }

    @Test func recentAsksForVideosAndDocumentsOnlyAndMergesThemNewestFirst() async throws {
        let td = FakeTd()
        td.handlers["searchChatMessages"] = { request in
            let filter = (request["filter"] as? [String: Any])?["@type"] as? String
            return filter == "searchMessagesFilterVideo"
                ? ["@type": "foundChatMessages", "messages": [self.videoMessage(id: 30, date: 300), self.videoMessage(id: 10, date: 100)], "next_from_message_id": 10]
                : ["@type": "foundChatMessages", "messages": [self.documentMessage(id: 20, date: 200)], "next_from_message_id": 0]
        }
        let page = try await TdDiscoveryTransport(transport: td).recent(chatId: 10, fromMessageId: 0, limit: 100)
        #expect(page.candidates.map(\.messageId) == [30, 20, 10])
        #expect(page.nextFromMessageId == 10, "a cursor from either search keeps the read going, from the oldest message seen")
        let filters = td.sent("searchChatMessages").compactMap { ($0["filter"] as? [String: Any])?["@type"] as? String }
        #expect(Set(filters) == ["searchMessagesFilterVideo", "searchMessagesFilterDocument"])
        #expect(td.sent("getChatHistory").isEmpty)
        let doc = page.candidates[1]
        #expect(doc.isFile && !doc.canBeSaved && doc.uniqueId == "d20" && doc.sizeBytes == 900_000_000)
        let video = page.candidates[0]
        #expect(!video.isFile && video.durationSeconds == 1200 && video.caption == "cap 30" && video.thumbnailFileId == 9 && video.uniqueId == "u30")
    }

    @Test func readsTheChatTypeProtectionAndNewestMessage() async throws {
        let td = FakeTd()
        td.handlers["getChat"] = { _ in ["@type": "chat", "id": -1001, "title": "Films", "has_protected_content": true, "last_message": ["id": 77],
                                         "type": ["@type": "chatTypeSupergroup", "supergroup_id": 5, "is_channel": true]] }
        td.handlers["getSupergroup"] = { _ in ["@type": "supergroup", "status": ["@type": "chatMemberStatusLeft"]] }
        let chat = try #require(await TdDiscoveryTransport(transport: td).chat(-1001))
        #expect(chat.type == .channel && chat.title == "Films" && chat.protectedContent && chat.lastMessageId == 77)
        #expect(!chat.isMember, "a channel the owner left can no longer be read")
    }

    @Test func recognisesBotsAndPrivateChats() async throws {
        let td = FakeTd()
        td.handlers["getUser"] = { request in ["@type": "user", "type": ["@type": (request["user_id"] as? Int64) == 70 ? "userTypeBot" : "userTypeRegular"]] }
        let transport = TdDiscoveryTransport(transport: td)
        #expect(await transport.isBot(70))
        #expect(await !transport.isBot(71))
    }

    @Test func forwardsInIncreasingOrderWithoutCopyingAndReportsEachResult() async throws {
        let td = FakeTd()
        td.handlers["forwardMessages"] = { _ in ["@type": "messages", "messages": [["id": 901], NSNull(), ["id": 903]]] }
        let transport = TdDiscoveryTransport(transport: td)
        let job = Task { try await transport.forward(toChatId: -1001, fromChatId: 10, messageIds: [30, 20, 10]) }
        try await Task.sleep(nanoseconds: 150_000_000)
        td.push(["@type": "updateMessageSendSucceeded", "old_message_id": 901, "message": ["id": 5]])
        td.push(["@type": "updateMessageSendFailed", "old_message_id": 903, "error": ["code": 400, "message": "CHAT_SEND_FORBIDDEN"]])
        let results = try await job.value
        let sent = try #require(td.sent("forwardMessages").first)
        #expect((sent["message_ids"] as? [Int64]) == [10, 20, 30] && sent["chat_id"] as? Int64 == -1001 && sent["from_chat_id"] as? Int64 == 10)
        #expect(sent["send_copy"] as? Bool == false)
        #expect(Set(results.map { "\($0)" }) == Set(["sent(sourceMessageId: 10)", "failed(sourceMessageId: 20, reason: \"not forwarded\")", "failed(sourceMessageId: 30, reason: \"CHAT_SEND_FORBIDDEN\")"]))
    }

    @Test func aThumbnailThatIsNotDownloadedGivesNoPath() async throws {
        let td = FakeTd()
        td.handlers["downloadFile"] = { _ in ["@type": "file", "local": ["is_downloading_completed": false, "path": ""]] }
        let path = try await TdDiscoveryTransport(transport: td).downloadThumbnail(5)
        #expect(path.isEmpty)
    }
}

private extension AttemptLog {
    func count(_ list: String) -> Int { markers.filter { $0 == list }.count }
}
