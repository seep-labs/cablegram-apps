package app.cablegram.discovery

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spec 008 edge case: a chosen chat the owner left leaves the choice, with its thumbnails, before anything is read from it. */
class DiscoveryLeftChatTest {
    private val library = 99L
    private val stillThere = TransportChat(10, TransportChatType.Channel, 0, "Stays", false)
    private val left = TransportChat(11, TransportChatType.Channel, 0, "Left", false, isMember = false)

    @Test
    fun `a chosen chat the owner left is dropped and a chosen chat they are still in is kept`() = runTest {
        val t = FakeDiscoveryTransport(listOf(stillThere, left))
        val store = DiscoveryStore(MemoryDiscoveryStorage())
        store.setChoice(listOf(stillThere, left).map { StoredChoice(it.id, ChatKind.Channel, it.title, 0) })
        val d = TelegramDiscovery(t, DiscoveryGate({ store.choice() }, { library }), RequestThrottle(5) { currentTime }, { library }, store)
        val controller = DiscoveryController(store, d)

        assertTrue(d.isReadable(10))
        assertFalse(d.isReadable(11))
        assertEquals(1, controller.dropUnreadable { d.isReadable(it) })
        assertEquals(setOf(10L), store.choice())
    }

    @Test
    fun `readability is only ever asked about chosen chats`() = runTest {
        val t = FakeDiscoveryTransport(listOf(stillThere, left))
        val store = DiscoveryStore(MemoryDiscoveryStorage())
        val d = TelegramDiscovery(t, DiscoveryGate({ store.choice() }, { library }), RequestThrottle(5) { currentTime }, { library }, store)
        assertThrows(DiscoveryRefused::class.java) { kotlinx.coroutines.runBlocking { d.isReadable(10) } }
        assertTrue("no chat lookup for a chat that was not chosen", t.calls.none { it == "chat" })
    }
}
