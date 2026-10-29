import Foundation
import Testing
@testable import CablegramCore

@Suite struct TelegramDiscoveryTests {
    @Test func aScanListsOnlyChannelsAndGroupsByDefault() async throws {
        let t = FakeDiscoveryTransport()
        t.add(Fixture.channel(10)); t.add(Fixture.group(11)); t.add(Fixture.person(12, user: 50)); t.add(Fixture.person(13, user: 1))
        t.add(TransportChat(id: 14, type: .secret, userId: 60, title: "Secret")); t.add(Fixture.person(15, user: 777_000)); t.add(Fixture.person(16, user: 70)); t.bots = [70]
        t.add(Fixture.channel(Fixture.library, "Cablegram library"))
        let (_, chats) = try await Fixture.scan(Fixture.graph(t))
        #expect(chats.map(\.id.raw) == [10, 11])
        #expect(!t.calls.contains("getUser"), "with both switches off a one-to-one chat is never even checked for being a bot")
    }

    @Test func switchesAddPersonalChatsSavedMessagesAndBotsButNeverSecretOrService() async throws {
        let t = FakeDiscoveryTransport()
        t.add(Fixture.person(12, user: 50)); t.add(Fixture.person(13, user: 1)); t.add(Fixture.person(16, user: 70)); t.bots = [70]
        t.add(TransportChat(id: 14, type: .secret, userId: 60, title: "Secret")); t.add(Fixture.person(15, user: 777_000))
        let all = DiscoverySwitches(includePersonalChats: true, includeSavedMessages: true, includeBotChats: true)
        let (_, chats) = try await Fixture.scan(Fixture.graph(t), switches: all)
        #expect(Dictionary(uniqueKeysWithValues: chats.map { ($0.id.raw, $0.kind) }) == [12: .personal, 13: .savedMessages, 16: .bot])
        let (_, onlyBots) = try await Fixture.scan(Fixture.graph(t), switches: DiscoverySwitches(includeBotChats: true))
        #expect(onlyBots.map(\.id.raw) == [16])
    }

    @Test func nothingIsReadWithoutALivePermit() async throws {
        let t = FakeDiscoveryTransport(); t.add(Fixture.channel(10))
        let graph = Fixture.graph(t)
        let permit = graph.controller.grantScan()
        graph.controller.endScan()
        await #expect(throws: DiscoveryRefused.self) { for try await _ in graph.discovery.discoveryChats(permit: permit, switches: DiscoverySwitches()) {} }
        await #expect(throws: DiscoveryRefused.self) { _ = try await graph.discovery.chatVideoCount(permit: permit, chatId: DiscoveryChatId(10)) }
        #expect(t.calls.isEmpty)
    }

    @Test func aNewPermitEndsTheOldOne() {
        let graph = Fixture.graph(FakeDiscoveryTransport())
        let first = graph.controller.grantScan()
        let second = graph.controller.grantScan()
        #expect(!first.isLive && second.isLive)
    }

    @Test func aChatTheScanDidNotListCannotBeCounted() async throws {
        let t = FakeDiscoveryTransport(); t.add(Fixture.channel(10)); t.add(Fixture.person(12, user: 50), videos: [Fixture.video(chat: 12, id: 1)])
        let graph = Fixture.graph(t)
        let (permit, _) = try await Fixture.scan(graph)
        await #expect(throws: DiscoveryRefused.self) { _ = try await graph.discovery.chatVideoCount(permit: permit, chatId: DiscoveryChatId(12)) }
        #expect(t.searched.isEmpty)
    }

    @Test func countsLongVideosAndBigVideoFilesAmongTheNewest() async throws {
        let t = FakeDiscoveryTransport()
        t.add(Fixture.channel(10), videos: [Fixture.video(chat: 10, id: 5, minutes: 30), Fixture.video(chat: 10, id: 4, minutes: 3), Fixture.video(chat: 10, id: 3, minutes: 10)],
              files: [Fixture.file(chat: 10, id: 6), Fixture.file(chat: 10, id: 2, megabytes: 20), Fixture.file(chat: 10, id: 1, name: "notes.pdf", mime: "application/pdf")])
        let graph = Fixture.graph(t)
        let (permit, _) = try await Fixture.scan(graph)
        let count = try await graph.discovery.chatVideoCount(permit: permit, chatId: DiscoveryChatId(10))
        #expect(count == VideoCount(videos: 2, files: 1))
        #expect(count.approximate)
    }

    @Test func anyLengthCountsEveryVideoAndEveryVideoFile() async throws {
        let t = FakeDiscoveryTransport()
        t.add(Fixture.channel(10), videos: [Fixture.video(chat: 10, id: 5, minutes: 1)], files: [Fixture.file(chat: 10, id: 6, megabytes: 5)])
        let graph = Fixture.graph(t)
        graph.controller.setMinVideoSeconds(0)
        let (permit, _) = try await Fixture.scan(graph)
        let count = try await graph.discovery.chatVideoCount(permit: permit, chatId: DiscoveryChatId(10))
        #expect(count == VideoCount(videos: 1, files: 1))
    }

    @Test func theCountLooksAtMostAtTheNewestHundred() async throws {
        let t = FakeDiscoveryTransport()
        t.add(Fixture.channel(10), videos: (1...250).map { Fixture.video(chat: 10, id: Int64($0)) })
        let graph = Fixture.graph(t)
        let (permit, _) = try await Fixture.scan(graph)
        let count = try await graph.discovery.chatVideoCount(permit: permit, chatId: DiscoveryChatId(10))
        #expect(count.videos == 100)
    }

    @Test func aFloodWaitPausesAndThenRetriesWithoutLosingTheCount() async throws {
        let t = FakeDiscoveryTransport()
        t.add(Fixture.channel(10), videos: [Fixture.video(chat: 10, id: 5)]); t.floodOnce["recent"] = 3
        let clock = ManualClock()
        let storage = MemoryDiscoveryStorage()
        let store = DiscoveryStore(storage: storage)
        let throttle = RequestThrottle(perSecond: 20, now: clock.now, sleep: clock.sleep)
        let gate = DiscoveryGate(choice: { store.choice() }, libraryChatId: { Fixture.library })
        let discovery = TelegramDiscovery(transport: t, gate: gate, throttle: throttle, libraryChatId: { Fixture.library }, store: store)
        let issuer = ScanPermits()
        let permit = issuer.grantScan()
        var chats: [DiscoveryChat] = []
        for try await c in discovery.discoveryChats(permit: permit, switches: DiscoverySwitches()) { chats.append(c) }
        let heard = AttemptLog()
        let pauses = throttle.pauses
        let listener = Task { for await s in pauses { heard.add(s); break } }
        try await Task.sleep(nanoseconds: 50_000_000)
        let before = clock.time
        let count = try await discovery.chatVideoCount(permit: permit, chatId: chats[0].id)
        for _ in 0..<100 where heard.values.isEmpty { try await Task.sleep(nanoseconds: 20_000_000) }
        listener.cancel()
        #expect(count.videos == 1)
        #expect(clock.time - before >= 3000, "the retry waited out the 3 s Telegram named")
        #expect(heard.values == [3])
    }
}

/// Time that only moves when something sleeps, so throttle tests run instantly.
final class ManualClock: @unchecked Sendable {
    private let lock = NSLock()
    private var current: Int64 = 1_000
    var time: Int64 { lock.withLock { current } }
    var now: @Sendable () -> Int64 { { [self] in time } }
    var sleep: @Sendable (Int64) async -> Void { { [self] ms in lock.withLock { current += ms } } }
}

final class AttemptLog: @unchecked Sendable {
    private let lock = NSLock()
    private var items: [Int] = []
    private var marks: [String] = []
    func add(_ value: Int) { lock.withLock { items.append(value) } }
    func add(_ marker: String) { lock.withLock { marks.append(marker); items.append(0) } }
    var values: [Int] { lock.withLock { items } }
    var markers: [String] { lock.withLock { marks } }
}
