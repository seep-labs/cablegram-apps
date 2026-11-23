package app.cablegram.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryGateTest {
    private val gate = DiscoveryGate(choice = { setOf(10L, 20L) }, libraryChatId = { 99L })

    @Test
    fun `a fresh permit allows a scan`() {
        gate.requirePermit(ScanPermits().grantScan())
    }

    @Test
    fun `a revoked permit is refused`() {
        val permit = ScanPermits().grantScan().also { it.revoke() }
        assertThrows(DiscoveryRefused::class.java) { gate.requirePermit(permit) }
    }

    @Test
    fun `granting again ends the earlier permit so each scan needs its own prompt`() {
        val permits = ScanPermits()
        val first = permits.grantScan()
        val second = permits.grantScan()
        assertFalse(first.isLive)
        assertTrue(second.isLive)
        permits.revokeAll()
        assertFalse(second.isLive)
    }

    @Test
    fun `only chosen chats can be read`() {
        gate.requireChosen(10L)
        assertThrows(DiscoveryRefused::class.java) { gate.requireChosen(30L) }
    }

    @Test
    fun `the library channel is never a source even if it is in the choice`() {
        val g = DiscoveryGate(choice = { setOf(99L) }, libraryChatId = { 99L })
        assertThrows(DiscoveryRefused::class.java) { g.requireChosen(99L) }
    }

    @Test
    fun `a chat id never prints its number`() {
        assertEquals("chat#redacted", DiscoveryChatId(-1001234567890).toString())
        assertFalse("${DiscoveryChat(DiscoveryChatId(-1001234567890), ChatKind.Channel, "t", false)}".contains("1234567890"))
    }
}
