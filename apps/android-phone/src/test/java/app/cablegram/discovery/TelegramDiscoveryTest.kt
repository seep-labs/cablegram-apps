package app.cablegram.discovery

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramDiscoveryTest {
    private val ME = 1L
    private val LIBRARY = 99L
    private val all = listOf(
        TransportChat(10, TransportChatType.Channel, 0, "Channel", false),
        TransportChat(11, TransportChatType.Group, 0, "Group", false),
        TransportChat(12, TransportChatType.Private, 500, "Friend", false),
        TransportChat(13, TransportChatType.Private, ME, "Saved Messages", false),
        TransportChat(14, TransportChatType.Secret, 501, "Secret", false),
        TransportChat(15, TransportChatType.Private, 600, "Bot", false),
        TransportChat(16, TransportChatType.Private, 777000, "Telegram", false),
        TransportChat(LIBRARY, TransportChatType.Channel, 0, "Cablegram library", false),
    )

    private fun TestScopeHolder.discovery(t: FakeDiscoveryTransport) =
        TelegramDiscovery(t, DiscoveryGate({ emptySet() }, { LIBRARY }), RequestThrottle(5, now), { LIBRARY }, DiscoveryStore(MemoryDiscoveryStorage()))

    private class TestScopeHolder(val now: () -> Long)

    private suspend fun scan(switches: DiscoverySwitches, t: FakeDiscoveryTransport, now: () -> Long): List<DiscoveryChat> {
        val permit = ScanPermits().grantScan()
        return TestScopeHolder(now).discovery(t).discoveryChats(permit, switches).toList()
    }

    @Test
    fun `by default only channels and groups are listed`() = runTest {
        val t = FakeDiscoveryTransport(all, ME, bots = setOf(600))
        val kinds = scan(DiscoverySwitches(), t) { currentTime }.map { it.kind }
        assertEquals(listOf(ChatKind.Channel, ChatKind.Group), kinds)
        assertTrue("no bot lookup for chats that are already excluded", "isBot" !in t.calls)
    }

    @Test
    fun `personal chats appear only with their switch, bots and the service account never`() = runTest {
        val t = FakeDiscoveryTransport(all, ME, bots = setOf(600))
        val kinds = scan(DiscoverySwitches(includePersonalChats = true), t) { currentTime }.map { it.kind }
        assertEquals(listOf(ChatKind.Channel, ChatKind.Group, ChatKind.Personal), kinds)
    }

    @Test
    fun `saved messages appear only with their switch`() = runTest {
        val t = FakeDiscoveryTransport(all, ME)
        val kinds = scan(DiscoverySwitches(includeSavedMessages = true), t) { currentTime }.map { it.kind }
        assertEquals(listOf(ChatKind.Channel, ChatKind.Group, ChatKind.SavedMessages), kinds)
    }

    @Test
    fun `a scan without a live permit reads nothing`() = runTest {
        val t = FakeDiscoveryTransport(all, ME)
        val permit = ScanPermits().grantScan().also { it.revoke() }
        val d = TelegramDiscovery(t, DiscoveryGate({ emptySet() }, { LIBRARY }), RequestThrottle(5) { currentTime }, { LIBRARY }, DiscoveryStore(MemoryDiscoveryStorage()))
        assertThrows(DiscoveryRefused::class.java) { kotlinx.coroutines.runBlocking { d.discoveryChats(permit, DiscoverySwitches()).toList() } }
        assertEquals(emptyList<String>(), t.calls)
    }

    @Test
    fun `only chats listed by this scan can be counted, files make the count approximate`() = runTest {
        val t = FakeDiscoveryTransport(all, ME, counts = mapOf((10L to false) to 214, (10L to true) to 3, (12L to false) to 5))
        val d = TelegramDiscovery(t, DiscoveryGate({ emptySet() }, { LIBRARY }), RequestThrottle(5) { currentTime }, { LIBRARY }, DiscoveryStore(MemoryDiscoveryStorage()))
        val permit = ScanPermits().grantScan()
        d.discoveryChats(permit, DiscoverySwitches()).toList()
        val count = d.chatVideoCount(permit, DiscoveryChatId(10))
        assertEquals(214, count.videos); assertEquals(3, count.files); assertTrue(count.approximate)
        val before = t.calls.size
        assertThrows(DiscoveryRefused::class.java) { kotlinx.coroutines.runBlocking { d.chatVideoCount(permit, DiscoveryChatId(12)) } }
        assertEquals("no count call for a chat the scan did not list", before, t.calls.size)
    }
}
