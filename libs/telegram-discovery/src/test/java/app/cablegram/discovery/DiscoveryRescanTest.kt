package app.cablegram.discovery

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A rescan reads only the chats where something new was posted since the last look. */
class DiscoveryRescanTest {
    private fun chat(last: Long) = DiscoveryChat(DiscoveryChatId(10), ChatKind.Channel, "Channel", false, last)
    private fun row(last: Long) = StoredScanRow(10, ChatKind.Channel, "Channel", false, 4, 0, 1L, last)

    @Test
    fun `a chat whose newest message is the one seen last time is skipped`() =
        assertTrue(isUnchangedSinceLastLook(row(500), chat(500)))

    @Test
    fun `a chat with a newer message is read again`() =
        assertFalse(isUnchangedSinceLastLook(row(500), chat(612)))

    @Test
    fun `a chat never read before is read`() =
        assertFalse(isUnchangedSinceLastLook(null, chat(500)))

    @Test
    fun `an unknown newest message is never treated as unchanged`() {
        assertFalse("rows from before this field existed carry 0", isUnchangedSinceLastLook(row(0), chat(0)))
        assertFalse(isUnchangedSinceLastLook(row(500), chat(0)))
    }
}
