package app.cablegram.ui

import app.cablegram.telegram.TgException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StandaloneTelegramSignInTest {
    @Test
    fun `maps FLOOD_WAIT to minutes`() {
        val (msg, fatal) = mapStandaloneTgError(TgException(420, "FLOOD_WAIT_120"), 3)
        assertEquals("Try again in 2 minutes.", msg)
        assertFalse(fatal)
    }

    @Test
    fun `maps invalid phone and code`() {
        assertEquals("That phone number isn't valid." to false, mapStandaloneTgError(TgException(400, "PHONE_NUMBER_INVALID"), 3))
        assertEquals("Wrong or expired code." to false, mapStandaloneTgError(TgException(400, "PHONE_CODE_EXPIRED"), 3))
    }

    @Test
    fun `password error counts down 3 tries and becomes fatal on 3rd failure`() {
        val (m1, f1) = mapStandaloneTgError(TgException(400, "PASSWORD_HASH_INVALID"), 3)
        assertEquals("Wrong password (2 tries left).", m1)
        assertFalse(f1)

        val (m2, f2) = mapStandaloneTgError(TgException(400, "PASSWORD_HASH_INVALID"), 2)
        assertEquals("Wrong password (1 tries left).", m2)
        assertFalse(f2)

        val (m3, f3) = mapStandaloneTgError(TgException(400, "PASSWORD_HASH_INVALID"), 1)
        assertTrue(m3.contains("Too many wrong passwords"))
        assertTrue(f3)
    }
}
