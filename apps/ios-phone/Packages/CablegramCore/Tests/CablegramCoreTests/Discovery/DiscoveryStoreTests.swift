import Foundation
import Testing
@testable import CablegramCore

@Suite struct DiscoveryStoreTests {
    @Test func startsEmptyWithEverythingOff() {
        let store = DiscoveryStore(storage: MemoryDiscoveryStorage())
        #expect(store.choice().isEmpty)
        #expect(store.switches() == DiscoverySwitches())
        #expect(store.minVideoSeconds() == 600)
    }

    @Test func survivesARestartAndIgnoresUnknownOrMissingFields() {
        let storage = MemoryDiscoveryStorage()
        let first = DiscoveryStore(storage: storage)
        first.setChoice([StoredChoice(chatId: 5, kind: .channel, title: "A", chosenAt: 1)])
        first.setSwitches(DiscoverySwitches(includePersonalChats: true))
        #expect(DiscoveryStore(storage: storage).choice() == [5])
        #expect(DiscoveryStore(storage: storage).switches().includePersonalChats)
        let old = MemoryDiscoveryStorage(#"{"choice":[{"chatId":9,"kind":"Group","title":"G","chosenAt":2,"extra":1}]}"#)
        #expect(DiscoveryStore(storage: old).choice() == [9])
    }

    @Test func changingTheMinimumLengthClearsTheCountsButKeepsTheChoice() {
        let store = DiscoveryStore(storage: MemoryDiscoveryStorage())
        store.setChoice([StoredChoice(chatId: 5, kind: .channel, title: "A", chosenAt: 1)])
        store.addScanRow(StoredScanRow(chatId: 5, kind: .channel, title: "A", protectedContent: false, videos: 3, files: 0, scannedAt: 1))
        store.finishScan(completed: true)
        store.setMinVideoSeconds(900)
        let s = store.snapshot()
        #expect(s.scan.isEmpty && s.scanned.isEmpty && !s.scanCompleted)
        #expect(s.choice.count == 1 && s.minVideoSeconds == 900)
    }

    @Test func droppingAChatForgetsItsChoiceCountsAndThumbnails() {
        let store = DiscoveryStore(storage: MemoryDiscoveryStorage())
        store.setChoice([StoredChoice(chatId: 5, kind: .channel, title: "A", chosenAt: 1)])
        store.addScanRow(StoredScanRow(chatId: 5, kind: .channel, title: "A", protectedContent: false, videos: 3, files: 0, scannedAt: 1))
        store.recordThumbnail(chatId: 5, fileId: 77)
        store.dropChat(5)
        #expect(store.choice().isEmpty && store.snapshot().scan.isEmpty && store.thumbnails(of: 5).isEmpty)
    }

    @Test func clearWipesStorageAndARetiredStoreWritesNothing() {
        let storage = MemoryDiscoveryStorage()
        let store = DiscoveryStore(storage: storage)
        store.setSwitches(DiscoverySwitches(includeBotChats: true))
        store.clear()
        #expect(storage.read() == nil)
        store.retire()
        store.setSwitches(DiscoverySwitches(includeBotChats: true))
        #expect(storage.read() == nil, "after a disconnect an old reference can no longer write")
    }

    @Test func aChatIdNeverReachesALog() {
        let text = "\(DiscoveryChatId(-1001234567890))"
        #expect(!text.contains("1234567890"))
    }
}
