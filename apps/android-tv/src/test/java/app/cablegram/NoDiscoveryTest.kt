package app.cablegram

import org.junit.Assert.fail
import org.junit.Test

// TV only (spec 008 FR-001, 004 FR-022 amendment). The phone has app.cablegram.discovery.DiscoverySurfaceTest.

/** TVs never scan, list or browse chats other than the library channel, so video discovery must not exist here. */
class NoDiscoveryTest {
    @Test
    fun `the TV app has no video discovery`() {
        for (name in listOf("TelegramDiscovery", "DiscoveryTransport", "TdlibDiscoveryTransport", "ScanPermit", "DiscoveryStore")) {
            try {
                Class.forName("app.cablegram.discovery.$name")
                fail("app.cablegram.discovery.$name must not exist in the TV app")
            } catch (_: ClassNotFoundException) {
            }
        }
    }
}
