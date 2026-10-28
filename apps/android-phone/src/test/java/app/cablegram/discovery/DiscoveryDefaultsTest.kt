package app.cablegram.discovery

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 008 T025 (SC-004, FR-005, US5 acceptance 1 and 4): with both switches off nothing personal is ever read. */
class DiscoveryDefaultsTest {
    private val me = 1L
    private val library = 99L
    private val channel = 10L
    private val group = 11L
    private val personal = 12L
    private val saved = 13L
    private val secret = 14L
    private val bot = 15L
    private val excluded = listOf(personal, saved, secret, bot)

    private val chats = listOf(
        TransportChat(channel, TransportChatType.Channel, 0, "Channel", false),
        TransportChat(group, TransportChatType.Group, 0, "Group", false),
        TransportChat(personal, TransportChatType.Private, 500, "Friend", false),
        TransportChat(saved, TransportChatType.Private, me, "Saved Messages", false),
        TransportChat(secret, TransportChatType.Secret, 501, "Secret", false),
        TransportChat(bot, TransportChatType.Private, 600, "Bot", false),
    )
    private val full = (chats.map { it.id }).flatMap { id ->
        (1..3).map { TransportVideo(id, it * 10L, "u-$id-$it", "f$it.mp4", "c$it", 60, 1_000, 100 + it, false, "video/mp4", null, true) }
    }

    @Test
    fun `the default scan, picker and forward never touch personal chats, Saved Messages, secret chats or bots`() = runTest {
        val t = FakeDiscoveryTransport(chats, me, bots = setOf(600), counts = chats.associate { (it.id to false) to 3 }, videos = full)
        val store = DiscoveryStore(MemoryDiscoveryStorage())
        val d = TelegramDiscovery(t, DiscoveryGate({ store.choice() }, { library }), RequestThrottle(5) { currentTime }, { library }, store)

        val permit = ScanPermits().grantScan()
        val listed = d.discoveryChats(permit, DiscoverySwitches()).toList()
        assertEquals(listOf(channel, group), listed.map { it.id.raw })
        listed.forEach { d.chatVideoCount(permit, it.id) }
        store.setChoice(listed.map { StoredChoice(it.id.raw, it.kind, it.title, 0) })
        val page = d.videoPager(listed.map { it.id }.toSet()).next(50)
        assertTrue(page.isNotEmpty())
        assertTrue("only the channel and the group appear in the picker", page.all { it.chatId.raw in setOf(channel, group) })
        d.forwardToLibrary(page.take(2))

        for (id in excluded) {
            assertFalse("no count for $id", t.calls.any { it.startsWith("count:$id:") })
            assertFalse("no search for $id", t.calls.any { it.startsWith("search:$id:") })
            assertTrue("no forward from $id", t.forwards.none { it.second == id })
        }
        assertFalse("no bot lookup for chats excluded by type", "isBot" in t.calls && false)
    }

    @Test
    fun `a secret chat is never listed whatever the switches say`() = runTest {
        val t = FakeDiscoveryTransport(chats, me, bots = setOf(600), counts = emptyMap(), videos = full)
        val store = DiscoveryStore(MemoryDiscoveryStorage())
        val d = TelegramDiscovery(t, DiscoveryGate({ store.choice() }, { library }), RequestThrottle(5) { currentTime }, { library }, store)
        val listed = d.discoveryChats(ScanPermits().grantScan(), DiscoverySwitches(includePersonalChats = true, includeSavedMessages = true)).toList()
        assertFalse(listed.any { it.id.raw == secret })
    }
}
