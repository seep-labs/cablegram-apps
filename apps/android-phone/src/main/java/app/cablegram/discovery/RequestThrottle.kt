package app.cablegram.discovery

import app.cablegram.telegram.TgException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * One limit for every discovery request (FR-007): at most [perSecond] starts per second, and when Telegram
 * answers FLOOD_WAIT the whole queue waits that long and the same request is tried again. Callers keep
 * whatever they already have.
 */
class RequestThrottle(
    private val perSecond: Int = 5,
    private val now: () -> Long = System::currentTimeMillis,
) {
    /** Seconds Telegram asked us to wait, for the "Telegram asked us to wait" line. */
    val pauses = MutableSharedFlow<Int>(extraBufferCapacity = 8)

    private val lock = Mutex()
    private val interval = 1000L / perSecond
    private var nextStart = 0L
    /** No request may start before this moment: set by a FLOOD_WAIT answer, whichever chat received it. */
    private var pausedUntil = 0L

    /** [requests] is how many Telegram requests [block] sends: each one takes its own slot. */
    suspend fun <T> run(requests: Int = 1, block: suspend () -> T): T {
        while (true) {
            repeat(requests) { wait() }
            try {
                return block()
            } catch (e: TgException) {
                val seconds = e.retryAfterSeconds ?: throw e
                pauses.tryEmit(seconds)
                lock.withLock { pausedUntil = maxOf(pausedUntil, now() + seconds * 1000L) }
            }
        }
    }

    /** Takes one slot, for a caller that sends several requests inside one [run] (it paces each one after the first). */
    suspend fun pace() = wait()

    private suspend fun wait() {
        while (true) {
            var start = 0L
            val sleep = lock.withLock {
                start = maxOf(now(), nextStart, pausedUntil)
                nextStart = start + interval
                start - now()
            }
            if (sleep > 0) delay(sleep)
            // A slot reserved before a FLOOD_WAIT arrived must not fire during the pause: take a new one.
            if (lock.withLock { pausedUntil } <= start) return
        }
    }
}
