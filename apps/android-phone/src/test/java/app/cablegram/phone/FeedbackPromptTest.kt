package app.cablegram.phone

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
}
