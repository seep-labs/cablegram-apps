import Foundation
import Testing
@testable import CablegramCore

@Suite struct RequestThrottleTests {
    @Test func spacesRequestsAtTheConfiguredRate() async throws {
        let clock = ManualClock()
        let throttle = RequestThrottle(perSecond: 20, now: clock.now, sleep: clock.sleep)
        let start = clock.time
        for _ in 0..<41 { try await throttle.run { 0 } }
        #expect(clock.time - start >= 2000 - 50, "41 requests at 20 per second take about two seconds")
    }

    @Test func aRequestOfTwoTakesTwoSlots() async throws {
        let clock = ManualClock()
        let throttle = RequestThrottle(perSecond: 10, now: clock.now, sleep: clock.sleep)
        let start = clock.time
        for _ in 0..<5 { try await throttle.run(requests: 2) { 0 } }
        #expect(clock.time - start >= 900)
    }

    @Test func aFloodWaitPausesEveryRequestAndThenRetriesTheSameOne() async throws {
        let clock = ManualClock()
        let throttle = RequestThrottle(perSecond: 20, now: clock.now, sleep: clock.sleep)
        let tries = AttemptCounter()
        let result = try await throttle.run { () -> Int in
            if tries.next() == 1 { throw TgError(code: 420, name: "FLOOD_WAIT_5") }
            return 7
        }
        #expect(result == 7 && tries.value == 2)
        let before = clock.time
        try await throttle.run { 0 }
        #expect(clock.time >= before)
    }

    @Test func otherErrorsAreNotRetried() async {
        let throttle = RequestThrottle(perSecond: 20)
        await #expect(throws: TgError.self) { try await throttle.run { () -> Int in throw TgError(code: 400, name: "CHAT_INVALID") } }
    }
}

final class AttemptCounter: @unchecked Sendable {
    private let lock = NSLock()
    private(set) var value = 0
    func next() -> Int { lock.withLock { value += 1; return value } }
}
