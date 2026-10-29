import Foundation

/// Permission to scan, given by the owner tapping "Look for videos" on the "On this phone" prompt (FR-003). It is used once, lives in memory
/// only and is never saved, so nothing can remember a grant.
public final class ScanPermit: @unchecked Sendable {
    private let lock = NSLock()
    private var live = true
    private var listedIds = Set<Int64>()

    fileprivate init() {}

    public var isLive: Bool { lock.withLock { live } }
    public func revoke() { lock.withLock { live = false } }

    /// Chats this scan listed; only these may be counted under this permit.
    func list(_ id: Int64) { lock.withLock { _ = listedIds.insert(id) } }
    func wasListed(_ id: Int64) -> Bool { lock.withLock { listedIds.contains(id) } }

    /// The only place a permit is made. Only the prompt's "Look for videos" handler may call `grantScan`.
    public final class Issuer: @unchecked Sendable {
        private let lock = NSLock()
        private var current: ScanPermit?

        public init() {}

        public func grantScan() -> ScanPermit {
            lock.withLock {
                current?.revoke()
                let permit = ScanPermit()
                current = permit
                return permit
            }
        }

        public func revokeAll() { lock.withLock { current?.revoke(); current = nil } }
    }
}

public typealias ScanPermits = ScanPermit.Issuer

/// FR-003 and FR-009 in code: reads happen only with a live permit, and only for chats the owner chose.
public struct DiscoveryGate: Sendable {
    private let choice: @Sendable () -> Set<Int64>
    private let libraryChatId: @Sendable () -> Int64?

    public init(choice: @escaping @Sendable () -> Set<Int64>, libraryChatId: @escaping @Sendable () -> Int64?) {
        self.choice = choice; self.libraryChatId = libraryChatId
    }

    public func requirePermit(_ permit: ScanPermit) throws {
        if !permit.isLive { throw DiscoveryRefused("The owner has not given permission for this scan") }
    }

    public func requireChosen(_ chatId: Int64) throws {
        if chatId == libraryChatId() { throw DiscoveryRefused("The library channel is not a discovery source") }
        if !choice().contains(chatId) { throw DiscoveryRefused("Chat is not in the owner's choice") }
    }
}
