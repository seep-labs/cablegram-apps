import Foundation

/// What changes the owner's choice, and what each change deletes (FR-008, US2, US5). The screens call this; none of them touch the store or the
/// cache directly.
public final class DiscoveryController: @unchecked Sendable {
    private let store: DiscoveryStore
    private let discovery: TelegramDiscovery
    private let permits: ScanPermits
    private let now: @Sendable () -> Int64

    public init(store: DiscoveryStore, discovery: TelegramDiscovery, permits: ScanPermits = ScanPermits(), now: @escaping @Sendable () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.store = store; self.discovery = discovery; self.permits = permits; self.now = now
    }

    /// Called only by the "On this phone" prompt's **Look for videos** (FR-003). Each call ends the previous permit.
    public func grantScan() -> ScanPermit { permits.grantScan() }

    /// Leaving the screen, stopping the scan or finishing it ends the permission.
    public func endScan() { permits.revokeAll() }

    /// **Use N chats**: ticked chats become the choice; anything unticked is purged at once.
    public func useChats(_ ticked: [DiscoveryChat]) async {
        let keep = Set(ticked.map(\.id.raw))
        for gone in store.choice().subtracting(keep) { await discovery.purgeChat(gone) }
        let before = Dictionary(store.snapshot().choice.map { ($0.chatId, $0) }, uniquingKeysWith: { first, _ in first })
        store.setChoice(ticked.map { before[$0.id.raw] ?? StoredChoice(chatId: $0.id.raw, kind: $0.kind, title: $0.title, chosenAt: now()) })
    }

    /// A new shortest length makes the old counts wrong, so the results are cleared and the next scan starts afresh.
    public func setMinVideoSeconds(_ seconds: Int) { store.setMinVideoSeconds(seconds) }

    /// Turning a switch on starts nothing; turning one off removes those chats from the choice with their thumbnails.
    public func setSwitches(_ switches: DiscoverySwitches) async {
        let old = store.switches()
        store.setSwitches(switches)
        if old.includePersonalChats && !switches.includePersonalChats { await purge(kind: .personal) }
        if old.includeSavedMessages && !switches.includeSavedMessages { await purge(kind: .savedMessages) }
        if old.includeBotChats && !switches.includeBotChats { await purge(kind: .bot) }
    }

    /// When the picker opens: chats the owner left, or can no longer read, leave the choice. Returns how many were dropped.
    public func dropUnreadable(_ readable: @Sendable (Int64) async -> Bool) async -> Int {
        var gone: [Int64] = []
        for id in store.choice() where !(await readable(id)) { gone.append(id) }
        for id in gone { await discovery.purgeChat(id) }
        return gone.count
    }

    private func purge(kind: ChatKind) async {
        for c in store.snapshot().choice where c.kind == kind { await discovery.purgeChat(c.chatId) }
        for r in store.snapshot().scan where r.kind == kind { store.dropChat(r.chatId) }
    }
}

/// The discovery pieces for one library channel, built once per signed-in session (phone only).
public final class DiscoveryGraph: @unchecked Sendable {
    public let store: DiscoveryStore
    public let throttle: RequestThrottle
    public let discovery: TelegramDiscovery
    public let controller: DiscoveryController

    init(store: DiscoveryStore, throttle: RequestThrottle, discovery: TelegramDiscovery, controller: DiscoveryController) {
        self.store = store; self.throttle = throttle; self.discovery = discovery; self.controller = controller
    }

    public static func create(transport: any DiscoveryTransport, storage: DiscoveryStorage, libraryChatId: Int64) -> DiscoveryGraph {
        let store = DiscoveryStore(storage: storage)
        let throttle = RequestThrottle(perSecond: scanRequestsPerSecond)
        let gate = DiscoveryGate(choice: { store.choice() }, libraryChatId: { libraryChatId })
        let discovery = TelegramDiscovery(transport: transport, gate: gate, throttle: throttle, libraryChatId: { libraryChatId }, store: store)
        return DiscoveryGraph(store: store, throttle: throttle, discovery: discovery, controller: DiscoveryController(store: store, discovery: discovery))
    }
}
