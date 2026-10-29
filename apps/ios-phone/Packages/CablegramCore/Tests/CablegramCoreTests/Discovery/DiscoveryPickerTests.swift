import Foundation
import Testing
@testable import CablegramCore

@Suite struct DiscoveryPickerTests {
    private func chosenGraph(_ t: FakeDiscoveryTransport, ids: [Int64]) async throws -> DiscoveryGraph {
        let graph = Fixture.graph(t)
        let (_, chats) = try await Fixture.scan(graph)
        await graph.controller.useChats(chats.filter { ids.contains($0.id.raw) })
        return graph
    }

    @Test func thePagerRefusesAChatOutsideTheChoiceBeforeTouchingTelegram() async throws {
        let t = FakeDiscoveryTransport(); t.add(Fixture.channel(10), videos: [Fixture.video(chat: 10, id: 1)]); t.add(Fixture.channel(11), videos: [Fixture.video(chat: 11, id: 2)])
        let graph = try await chosenGraph(t, ids: [10])
        let before = t.searched.count
        #expect(throws: DiscoveryRefused.self) { _ = try graph.discovery.videoPager(chatIds: [DiscoveryChatId(11)]) }
        #expect(throws: DiscoveryRefused.self) { _ = try graph.discovery.videoPager(chatIds: [DiscoveryChatId(Fixture.library)]) }
        #expect(t.searched.count == before)
    }

    @Test func listsNewestFirstAcrossChatsAndHidesShortClips() async throws {
        let t = FakeDiscoveryTransport()
        t.add(Fixture.channel(10), videos: [Fixture.video(chat: 10, id: 8, caption: "Film A"), Fixture.video(chat: 10, id: 3, minutes: 2), Fixture.video(chat: 10, id: 1)])
        t.add(Fixture.channel(11), videos: [Fixture.video(chat: 11, id: 6, name: "b.mp4")], files: [Fixture.file(chat: 11, id: 7)])
        let graph = try await chosenGraph(t, ids: [10, 11])
        let pager = try graph.discovery.videoPager(chatIds: [DiscoveryChatId(10), DiscoveryChatId(11)])
        let page = try await pager.next(limit: 50)
        #expect(page.map(\.messageId) == [8, 7, 6, 1])
        #expect(page[0].title == "Film A" && page[2].title == "b.mp4", "caption, else file name")
        #expect(pager.exhausted)
    }

    @Test func pagesInSlicesAndKeepsGoing() async throws {
        let t = FakeDiscoveryTransport(); t.add(Fixture.channel(10), videos: (1...120).map { Fixture.video(chat: 10, id: Int64($0)) })
        let graph = try await chosenGraph(t, ids: [10])
        let pager = try graph.discovery.videoPager(chatIds: [DiscoveryChatId(10)])
        let first = try await pager.next(limit: 50), second = try await pager.next(limit: 50), third = try await pager.next(limit: 50)
        #expect(first.count == 50 && second.count == 50 && third.count == 20)
        #expect(Set(first.map(\.messageId)).isDisjoint(with: second.map(\.messageId)))
        #expect(pager.exhausted)
    }

    @Test func forwardsOnlyIntoTheLibraryAndOnlyWhatThePickerOffered() async throws {
        let t = FakeDiscoveryTransport(); t.add(Fixture.channel(10), videos: [Fixture.video(chat: 10, id: 1), Fixture.video(chat: 10, id: 2)])
        let graph = try await chosenGraph(t, ids: [10])
        let pager = try graph.discovery.videoPager(chatIds: [DiscoveryChatId(10)])
        let page = try await pager.next()
        let outcomes = try await graph.discovery.forwardToLibrary(page)
        #expect(outcomes.allSatisfy { if case .added = $0 { true } else { false } })
        #expect(t.forwarded.count == 1 && t.forwarded[0].to == Fixture.library && t.forwarded[0].from == 10 && t.forwarded[0].ids == [2, 1])
        let invented = DiscoveryVideo(chatId: DiscoveryChatId(10), messageId: 999, uniqueId: "x", title: "x", durationSeconds: 1200, sizeBytes: 1, date: 1, thumbnailFileId: nil, protectedContent: false)
        await #expect(throws: DiscoveryRefused.self) { _ = try await graph.discovery.forwardToLibrary([invented]) }
        #expect(t.forwarded.count == 1)
    }

    @Test func protectedVideosAreRefusedInTheEngineNotOnlyInTheScreen() async throws {
        let t = FakeDiscoveryTransport()
        t.add(TransportChat(id: 10, type: .channel, title: "Locked", protectedContent: true), videos: [Fixture.video(chat: 10, id: 1)])
        t.add(Fixture.channel(11), videos: [Fixture.video(chat: 11, id: 2, saved: false)])
        let graph = try await chosenGraph(t, ids: [10, 11])
        let page = try await graph.discovery.videoPager(chatIds: [DiscoveryChatId(10), DiscoveryChatId(11)]).next()
        #expect(page.allSatisfy { $0.protectedContent })
        let outcomes = try await graph.discovery.forwardToLibrary(page)
        #expect(t.forwarded.isEmpty)
        #expect(outcomes.count == 2 && outcomes.allSatisfy { if case .failed = $0 { true } else { false } })
    }

    @Test func someForwardsFailingAreReportedPerVideo() async throws {
        let t = FakeDiscoveryTransport(); t.add(Fixture.channel(10), videos: [Fixture.video(chat: 10, id: 1), Fixture.video(chat: 10, id: 2)]); t.forwardFailures = [1]
        let graph = try await chosenGraph(t, ids: [10])
        let page = try await graph.discovery.videoPager(chatIds: [DiscoveryChatId(10)]).next()
        let outcomes = try await graph.discovery.forwardToLibrary(page)
        #expect(outcomes.filter { if case .added = $0 { true } else { false } }.map(\.video.messageId) == [2])
        #expect(outcomes.filter { if case .failed = $0 { true } else { false } }.map(\.video.messageId) == [1])
    }

    @Test func thumbnailsAreRecordedSoUntickingCanDeleteExactlyThose() async throws {
        let t = FakeDiscoveryTransport()
        var v = Fixture.video(chat: 10, id: 1); v.thumbnailFileId = 42
        t.add(Fixture.channel(10), videos: [v])
        let graph = try await chosenGraph(t, ids: [10])
        let video = try await graph.discovery.videoPager(chatIds: [DiscoveryChatId(10)]).next()[0]
        let path = try await graph.discovery.thumbnail(for: video)
        #expect(path == "/tmp/thumb-42.jpg")
        #expect(graph.store.thumbnails(of: 10) == [42])
        await graph.discovery.purgeChat(10)
        #expect(t.deleted == [42] && graph.store.choice().isEmpty)
    }
}
