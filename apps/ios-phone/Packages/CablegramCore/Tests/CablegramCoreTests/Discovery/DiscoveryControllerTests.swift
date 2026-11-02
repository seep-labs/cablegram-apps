import Foundation
import Testing
@testable import CablegramCore

@Suite struct DiscoveryControllerTests {
    private func chosen(_ graph: DiscoveryGraph, _ transport: FakeDiscoveryTransport, ids: [Int64]) async throws {
        let (_, chats) = try await Fixture.scan(graph, switches: DiscoverySwitches(includePersonalChats: true, includeSavedMessages: true, includeBotChats: true))
        await graph.controller.useChats(chats.filter { ids.contains($0.id.raw) })
    }

    @Test func useChatsSavesTheChoiceAndPurgesWhatWasUnticked() async throws {
        let t = FakeDiscoveryTransport(); t.add(Fixture.channel(10)); t.add(Fixture.channel(11))
        let graph = Fixture.graph(t)
        try await chosen(graph, t, ids: [10, 11])
        graph.store.recordThumbnail(chatId: 11, fileId: 500)
        try await chosen(graph, t, ids: [10])
        #expect(graph.store.choice() == [10])
        #expect(t.deleted == [500], "the thumbnail of the unticked chat is deleted at once")
        #expect(t.optimized.contains([11]))
    }

    @Test func nothingIsChosenByDefault() async throws {
        let t = FakeDiscoveryTransport(); t.add(Fixture.channel(10))
        let graph = Fixture.graph(t)
        _ = try await Fixture.scan(graph)
        #expect(graph.store.choice().isEmpty)
    }

    @Test func turningASwitchOffPurgesThoseChatsButTurningOnStartsNothing() async throws {
        let t = FakeDiscoveryTransport(); t.add(Fixture.channel(10)); t.add(Fixture.person(12, user: 50)); t.add(Fixture.person(16, user: 70)); t.bots = [70]
        let graph = Fixture.graph(t)
        await graph.controller.setSwitches(DiscoverySwitches(includePersonalChats: true, includeBotChats: true))
        #expect(t.calls.isEmpty, "turning a switch on reads nothing")
        try await chosen(graph, t, ids: [10, 12, 16])
        graph.store.addScanRow(StoredScanRow(chatId: 12, kind: .personal, title: "P", protectedContent: false, videos: 1, files: 0, scannedAt: 1))
        await graph.controller.setSwitches(DiscoverySwitches(includePersonalChats: false, includeBotChats: true))
        #expect(graph.store.choice() == [10, 16])
        #expect(!graph.store.snapshot().scan.contains { $0.chatId == 12 })
        await graph.controller.setSwitches(DiscoverySwitches())
        #expect(graph.store.choice() == [10])
    }

    @Test func aChatTheOwnerLeftLeavesTheChoiceWhenThePickerOpens() async throws {
        let t = FakeDiscoveryTransport(); t.add(Fixture.channel(10)); t.add(Fixture.channel(11))
        let graph = Fixture.graph(t)
        try await chosen(graph, t, ids: [10, 11])
        t.chats[11]?.isMember = false
        let dropped = await graph.controller.dropUnreadable { (try? await graph.discovery.isReadable($0)) ?? false }
        #expect(dropped == 1)
        #expect(graph.store.choice() == [10])
    }

    @Test func aQuietChatIsRecognisedFromItsNewestMessage() {
        let chat = DiscoveryChat(id: DiscoveryChatId(10), kind: .channel, title: "A", protectedContent: false, lastMessageId: 99)
        let row = StoredScanRow(chatId: 10, kind: .channel, title: "A", protectedContent: false, videos: 1, files: 0, scannedAt: 1, lastMessageId: 99)
        #expect(isUnchangedSinceLastLook(row, chat))
        var newer = chat; newer.lastMessageId = 100
        #expect(!isUnchangedSinceLastLook(row, newer))
        #expect(!isUnchangedSinceLastLook(nil, chat))
        var unknown = chat; unknown.lastMessageId = 0
        #expect(!isUnchangedSinceLastLook(StoredScanRow(chatId: 10, kind: .channel, title: "A", protectedContent: false, videos: 1, files: 0, scannedAt: 1), unknown))
    }
}
