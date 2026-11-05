package app.cablegram.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SeekResumeTest {
    @Test
    fun `a second seek during the hold keeps a playing video playing`() {
        // The hold paused the player itself; it was going to resume.
        assertTrue(resumeAfterUserSeek(settling = true, settleResume = true, playing = false))
    }

    @Test
    fun `a paused video stays paused, during the hold or not`() {
        assertFalse(resumeAfterUserSeek(settling = true, settleResume = false, playing = false))
        assertFalse(resumeAfterUserSeek(settling = false, settleResume = true, playing = false))
    }

    @Test
    fun `outside the hold a seek follows the player`() {
        assertTrue(resumeAfterUserSeek(settling = false, settleResume = false, playing = true))
    }

    @Test
    fun `the seek spinner stays until pictures move, however slow the source`() {
        assertFalse(settleHoldOver(21_000L, 100))
        assertFalse(settleHoldOver(60_000L, 0))
        assertTrue(settleHoldOver(22_000L, 8))
        assertTrue(settleHoldOver(120_000L, 0))
    }
}
