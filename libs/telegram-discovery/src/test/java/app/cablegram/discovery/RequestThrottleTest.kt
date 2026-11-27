package app.cablegram.discovery

import app.cablegram.telegram.TgException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RequestThrottleTest {
    @Test
    fun `never starts more than five requests in a second`() = runTest {
        val throttle = RequestThrottle(5) { currentTime }
        val starts = (1..20).map { async { throttle.run { currentTime } } }.awaitAll()
        for (s in starts) assertTrue(starts.count { it in s until s + 1000 } <= 5)
        assertTrue("20 requests take about 3.8 s", currentTime >= 3800)
    }

    @Test
    fun `a call that sends two requests takes two slots`() = runTest {
        val throttle = RequestThrottle(5) { currentTime }
        (1..10).map { async { throttle.run(requests = 2) { currentTime } } }.awaitAll()
        assertTrue("10 two-request calls are 20 requests: about 3.8 s", currentTime >= 3800)
    }

    @Test
    fun `flood wait pauses everything and then retries the same request`() = runTest {
        val throttle = RequestThrottle(5) { currentTime }
        var calls = 0
        val pauses = mutableListOf<Int>()
        val seen = async(start = CoroutineStart.UNDISPATCHED) { throttle.pauses.collect { pauses += it } }
        val result = throttle.run {
            if (++calls == 1) throw TgException(420, "FLOOD_WAIT_23")
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(2, calls)
        assertTrue(currentTime >= 23_000)
        assertEquals(listOf(23), pauses)
        seen.cancel()
    }

    @Test
    fun `a flood wait on one chat holds back every other chat, including requests already queued`() = runTest {
        val throttle = RequestThrottle(5) { currentTime }
        var first = true
        val starts = (1..8).map { i ->
            async {
                throttle.run {
                    if (i == 1 && first) { first = false; throw TgException(420, "FLOOD_WAIT_30") }
                    currentTime
                }
            }
        }.awaitAll()
        assertTrue("no request starts inside the 30 s pause: $starts", starts.all { it >= 30_000 })
    }

    @Test
    fun `other errors are not retried`() = runTest {
        val throttle = RequestThrottle(5) { currentTime }
        var calls = 0
        try {
            throttle.run { calls++; throw TgException(400, "CHAT_ID_INVALID") }
        } catch (_: TgException) {
        }
        assertEquals(1, calls)
    }
}
