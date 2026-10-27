package app.cablegram.discovery

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryControllerTest {
    private val LIBRARY = 99L
    private fun chat(id: Long, kind: ChatKind) = DiscoveryChat(DiscoveryChatId(id), kind, "t$id", false)

    private class Setup(val transport: FakeDiscoveryTransport, val store: DiscoveryStore, val controller: DiscoveryController)

    private fun kotlinx.coroutines.test.TestScope.setup(): Setup {
        val transport = FakeDiscoveryTransport()
        val store = DiscoveryStore(MemoryDiscoveryStorage())
        val discovery = TelegramDiscovery(transport, DiscoveryGate({ store.choice() }, { LIBRARY }), RequestThrottle(5) { currentTime }, { LIBRARY }, store)
        return Setup(transport, store, DiscoveryController(store, discovery) { 1000L })
    }

    @Test
    fun `use chats saves the ticked chats and purges the unticked ones at once`() = runTest {
        val s = setup()
        s.controller.useChats(listOf(chat(10, ChatKind.Channel), chat(11, ChatKind.Group)))
        s.store.recordThumbnail(11, 5)
        s.controller.useChats(listOf(chat(10, ChatKind.Channel)))
        assertEquals(setOf(10L), s.store.choice())
        assertEquals(listOf(5), s.transport.deletedFiles)
        assertEquals(listOf(listOf(11L)), s.transport.optimized)
    }

    @Test
    fun `turning personal chats off removes them from the choice and keeps the others`() = runTest {
        val s = setup()
        s.controller.setSwitches(DiscoverySwitches(includePersonalChats = true))
        s.controller.useChats(listOf(chat(10, ChatKind.Channel), chat(12, ChatKind.Personal)))
        s.store.recordThumbnail(12, 8)
        s.controller.setSwitches(DiscoverySwitches())
        assertEquals(setOf(10L), s.store.choice())
        assertEquals(listOf(8), s.transport.deletedFiles)
    }

    @Test
    fun `turning a switch on chooses nothing`() = runTest {
        val s = setup()
        s.controller.setSwitches(DiscoverySwitches(includeSavedMessages = true))
        assertTrue(s.store.choice().isEmpty())
        assertTrue(s.store.switches().includeSavedMessages)
    }

    @Test
    fun `chats the owner left are dropped when the picker opens`() = runTest {
        val s = setup()
        s.controller.useChats(listOf(chat(10, ChatKind.Channel), chat(11, ChatKind.Group)))
        val dropped = s.controller.dropUnreadable { it != 11L }
        assertEquals(1, dropped)
        assertEquals(setOf(10L), s.store.choice())
    }

    @Test
    fun `each scan needs its own permit and ending a scan revokes it`() = runTest {
        val s = setup()
        val first = s.controller.grantScan()
        val second = s.controller.grantScan()
        assertFalse(first.isLive)
        s.controller.endScan()
        assertFalse(second.isLive)
    }
}
