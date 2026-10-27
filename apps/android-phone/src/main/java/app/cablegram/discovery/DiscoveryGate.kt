package app.cablegram.discovery

/**
 * Permission to scan, given by the owner tapping "Look for videos" on the "On this phone" prompt (FR-003).
 * It is used once, lives in memory only and is never saved, so nothing can remember a grant.
 */
class ScanPermit internal constructor() {
    @Volatile private var live = true
    /** Chats this scan listed; only these may be counted under this permit. */
    internal val listed: MutableSet<Long> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    val isLive get() = live
    fun revoke() { live = false }
}

/** The only place a [ScanPermit] is made. Only the prompt's "Look for videos" handler may call [grantScan]. */
class ScanPermits {
    private var current: ScanPermit? = null

    fun grantScan(): ScanPermit {
        current?.revoke()
        return ScanPermit().also { current = it }
    }

    fun revokeAll() { current?.revoke(); current = null }
}

/** FR-003 and FR-009 in code: reads happen only with a live permit, and only for chats the owner chose. */
class DiscoveryGate(private val choice: () -> Set<Long>, private val libraryChatId: () -> Long?) {
    fun requirePermit(permit: ScanPermit) {
        if (!permit.isLive) throw DiscoveryRefused("The owner has not given permission for this scan")
    }

    fun requireChosen(chatId: Long) {
        if (chatId == libraryChatId()) throw DiscoveryRefused("The library channel is not a discovery source")
        if (chatId !in choice()) throw DiscoveryRefused("Chat is not in the owner's choice")
    }
}
