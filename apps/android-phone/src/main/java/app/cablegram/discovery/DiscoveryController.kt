package app.cablegram.discovery

/**
 * What changes the owner's choice, and what each change deletes (FR-008, US2, US5). The screens call this;
 * none of them touch the store or the cache directly.
 */
class DiscoveryController(
    private val store: DiscoveryStore,
    private val discovery: TelegramDiscovery,
    private val permits: ScanPermits = ScanPermits(),
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Called only by the "On this phone" prompt's **Look for videos** (FR-003). Each call ends the previous permit. */
    fun grantScan(): ScanPermit = permits.grantScan()

    /** Leaving the screen, stopping the scan or finishing it ends the permission. */
    fun endScan() = permits.revokeAll()

    /** **Use N chats**: ticked chats become the choice; anything unticked is purged at once. */
    suspend fun useChats(ticked: List<DiscoveryChat>) {
        val keep = ticked.map { it.id.raw }.toSet()
        for (gone in store.choice() - keep) discovery.purgeChat(gone)
        val before = store.snapshot().choice.associateBy { it.chatId }
        store.setChoice(ticked.map { before[it.id.raw] ?: StoredChoice(it.id.raw, it.kind, it.title, now()) })
    }

    /** A new shortest length makes the old counts wrong, so the results are cleared and the next scan starts afresh. */
    fun setMinVideoSeconds(seconds: Int) = store.setMinVideoSeconds(seconds)

    /** Turning a switch on starts nothing; turning one off removes those chats from the choice with their thumbnails. */
    suspend fun setSwitches(switches: DiscoverySwitches) {
        val old = store.switches()
        store.setSwitches(switches)
        if (old.includePersonalChats && !switches.includePersonalChats) purgeKind(ChatKind.Personal)
        if (old.includeSavedMessages && !switches.includeSavedMessages) purgeKind(ChatKind.SavedMessages)
    }

    /** When the picker opens: chats the owner left, or can no longer read, leave the choice. Returns how many were dropped. */
    suspend fun dropUnreadable(readable: suspend (Long) -> Boolean): Int {
        val gone = store.choice().filter { !readable(it) }
        gone.forEach { discovery.purgeChat(it) }
        return gone.size
    }

    private suspend fun purgeKind(kind: ChatKind) {
        store.snapshot().choice.filter { it.kind == kind }.forEach { discovery.purgeChat(it.chatId) }
        store.snapshot().scan.filter { it.kind == kind }.forEach { store.dropChat(it.chatId) }
    }
}
