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

    suspend fun <T> run(block: suspend () -> T): T {
        while (true) {
            wait()
            try {
                return block()
            } catch (e: TgException) {
                val seconds = e.retryAfterSeconds ?: throw e
                pauses.tryEmit(seconds)
                lock.withLock { nextStart = maxOf(nextStart, now() + seconds * 1000L) }
            }
        }
    }

    private suspend fun wait() {
        val sleep = lock.withLock {
            val start = maxOf(now(), nextStart)
            nextStart = start + interval
            start - now()
        }
        if (sleep > 0) delay(sleep)
    }
}
