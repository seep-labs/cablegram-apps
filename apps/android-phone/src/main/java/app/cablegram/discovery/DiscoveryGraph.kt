package app.cablegram.discovery

/** The discovery pieces for one library channel, built once per signed-in session (phone only). */
class DiscoveryGraph(
    val store: DiscoveryStore,
    val throttle: RequestThrottle,
    val discovery: TelegramDiscovery,
    val controller: DiscoveryController,
) {
    companion object {
        fun create(transport: DiscoveryTransport, storage: DiscoveryStorage, libraryChatId: Long): DiscoveryGraph {
            val store = DiscoveryStore(storage)
            val throttle = RequestThrottle(SCAN_REQUESTS_PER_SECOND)
            val gate = DiscoveryGate({ store.choice() }, { libraryChatId })
            val discovery = TelegramDiscovery(transport, gate, throttle, { libraryChatId }, store)
            return DiscoveryGraph(store, throttle, discovery, DiscoveryController(store, discovery))
        }
    }
}
