import Testing
@testable import CablegramCore

private func make(_ id: String, _ change: (inout LibraryItem) -> Void = { _ in }) -> LibraryItem {
    var item = LibraryItem(id: id, title: id, filename: "\(id).mp4", importedAt: "2026-01-01T00:00:00Z")
    change(&item)
    return item
}

@Suite struct LibraryCleanupTests {
    private let offline = LibraryConnections(telegramNeedsConnection: true, cloudNeedsConnection: true)
    private let online = LibraryConnections()

    @Test func aTitleOnThisPhoneHasNoProblem() {
        #expect(libraryProblem(make("local"), offline) == nil)
    }

    @Test func aMissingSourceIsUnavailableAndAHouseholdOnlyTitleIsNotOnThisPhone() {
        #expect(libraryProblem(make("gone") { $0.sourceAvailable = false }, online) == .unavailable)
        #expect(libraryProblem(make("other") { $0.sourceAvailable = false; $0.householdOnly = true }, online) == .notOnPhone)
    }

    @Test func aTelegramTitleIsAProblemOnlyWhileTelegramIsNotConnectedHere() {
        let title = make("tg") { $0.sourceKind = "telegram" }
        #expect(libraryProblem(title, offline) == .telegramNotConnected)
        #expect(libraryProblem(title, online) == nil)
        #expect(libraryProblem(make("tg-gone") { $0.sourceKind = "telegram"; $0.sourceAvailable = false }, online) == .unavailable)
    }

    @Test func aCloudOnlyTitleIsAProblemOnlyWhileItsStorageIsDisconnected() {
        let cloudOnly = make("cloud") { $0.ownCloudCopy = true; $0.sourceAvailable = false }
        #expect(libraryProblem(cloudOnly, offline) == .cloudNotConnected)
        #expect(libraryProblem(cloudOnly, online) == nil)
        // Still on the phone as well: it plays from the phone whatever the storage does.
        #expect(libraryProblem(make("both") { $0.ownCloudCopy = true }, offline) == nil)
    }

    @Test func groupsKeepAFixedOrderAndLeaveEmptyOnesOut() {
        let items = [
            make("tg") { $0.sourceKind = "telegram" }, make("gone") { $0.sourceAvailable = false }, make("fine"),
            make("cloud") { $0.ownCloudCopy = true; $0.sourceAvailable = false }, make("gone2") { $0.sourceAvailable = false },
        ]
        let groups = cleanupGroups(items, offline)
        #expect(groups.map(\.problem) == [.unavailable, .telegramNotConnected, .cloudNotConnected])
        #expect(groups[0].items.map(\.id) == ["gone", "gone2"])
        #expect(cleanupGroups([make("fine")], offline).isEmpty)
    }

    @Test func connectionProblemsNeedAConnectionAndTheOthersDoNot() {
        #expect(LibraryProblem.telegramNotConnected.needsConnection && LibraryProblem.cloudNotConnected.needsConnection)
        #expect(!LibraryProblem.unavailable.needsConnection && !LibraryProblem.notOnPhone.needsConnection)
    }
}
