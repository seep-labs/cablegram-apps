package app.cablegram.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FeedbackUrlTest {
    @Test
    fun `feedback link targets the cablegram feedback page`() {
        assertEquals("https://cablegram.app/feedback.html", FEEDBACK_URL)
        assertTrue(FEEDBACK_URL.startsWith("https://"))
    }
}
