package app.cablegram.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

class FeedbackPromptTest {
    @Test
    fun `shows once after 5 minutes of TV playback`() {
        assertTrue(shouldAskForFeedback(seen = false, confirmedStart = true, playedSeconds = 300, reachedEnd = false))
    }

    @Test
    fun `shows once when playback reaches the end`() {
        assertTrue(shouldAskForFeedback(seen = false, confirmedStart = true, playedSeconds = 20, reachedEnd = true))
        assertTrue(reachedPlaybackEnd(positionMs = 5_975_000L, durationSeconds = 6000))
        assertFalse(reachedPlaybackEnd(positionMs = 5_000L, durationSeconds = 6000))
    }

    @Test
    fun `does not show after a short stop`() {
        assertFalse(shouldAskForFeedback(seen = false, confirmedStart = true, playedSeconds = 5, reachedEnd = false))
        assertFalse(shouldAskForFeedback(seen = false, confirmedStart = true, playedSeconds = 299, reachedEnd = false))
    }

    @Test
    fun `does not show after a failed or refused start`() {
        assertFalse(shouldAskForFeedback(seen = false, confirmedStart = false, playedSeconds = 600, reachedEnd = true))
    }

    @Test
    fun `does not show when already seen`() {
        assertFalse(shouldAskForFeedback(seen = true, confirmedStart = true, playedSeconds = 600, reachedEnd = true))
    }

    @Test
    fun `a film watched with the phone locked counts by the time since the TV confirmed the start`() {
        // Last position read 10 s in (the phone was then locked); stopped 6 minutes after the confirmed start.
        assertEquals(360, playedSecondsForFeedback(startPositionMs = 0, maxPositionMs = 10_000, confirmedAtMs = 1_000, nowMs = 361_000))
        assertTrue(shouldAskForFeedback(seen = false, confirmedStart = true, playedSeconds = 360, reachedEnd = false))
    }

    @Test
    fun `played time is the larger of the position advance and the time since the confirmed start`() {
        assertEquals(400, playedSecondsForFeedback(startPositionMs = 100_000, maxPositionMs = 500_000, confirmedAtMs = 0, nowMs = 60_000))
        assertEquals(0, playedSecondsForFeedback(startPositionMs = null, maxPositionMs = 0, confirmedAtMs = null, nowMs = 600_000))  // no confirmed start
        assertEquals(30, playedSecondsForFeedback(startPositionMs = null, maxPositionMs = 0, confirmedAtMs = 0, nowMs = 30_000))
    }
}
