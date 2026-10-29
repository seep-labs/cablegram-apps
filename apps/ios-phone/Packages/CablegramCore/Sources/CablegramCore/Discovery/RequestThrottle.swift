import Foundation

/// One limit for every discovery request (FR-007): at most `perSecond` starts per second, and when Telegram answers FLOOD_WAIT the whole
/// queue waits that long and the same request is tried again. Callers keep whatever they already have.
public actor RequestThrottle {
    private let interval: Int64
    private let now: @Sendable () -> Int64
    private let sleep: @Sendable (Int64) async -> Void
    private var nextStart: Int64 = 0
    /// No request may start before this moment: set by a FLOOD_WAIT answer, whichever chat received it.
    private var pausedUntil: Int64 = 0
    private let pauseEvents = Broadcaster<Int>()

    public init(perSecond: Int = 5,
                now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
                sleep: @escaping @Sendable (Int64) async -> Void = { try? await Task.sleep(nanoseconds: UInt64($0) * 1_000_000) }) {
        interval = 1000 / Int64(max(perSecond, 1)); self.now = now; self.sleep = sleep
    }

    /// Seconds Telegram asked us to wait, for the "Telegram asked us to wait" line.
    public nonisolated var pauses: AsyncStream<Int> { pauseEvents.stream() }

    /// `requests` is how many Telegram requests `block` sends: each one takes its own slot.
    public func run<T: Sendable>(requests: Int = 1, _ block: @Sendable () async throws -> T) async throws -> T {
        while true {
            try Task.checkCancellation()
            for _ in 0..<requests { try await wait() }
            do {
                return try await block()
            } catch let error as TgError {
                guard let seconds = error.retryAfterSeconds else { throw error }
                pauseEvents.send(seconds)
                pausedUntil = max(pausedUntil, now() + Int64(seconds) * 1000)
            }
        }
    }

    /// Takes one slot, for a caller that sends several requests inside one `run` (it paces each one after the first).
    public func pace() async throws { try await wait() }

    private func wait() async throws {
        while true {
            let start = max(now(), nextStart, pausedUntil)
            nextStart = start + interval
            let delay = start - now()
            if delay > 0 { await sleep(delay) }
            try Task.checkCancellation()
            // A slot reserved before a FLOOD_WAIT arrived must not fire during the pause: take a new one.
            if pausedUntil <= start { return }
        }
    }
}
