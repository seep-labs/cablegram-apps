package app.cablegram.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryStoreTest {
    @Test
    fun `defaults are empty and both switches are off`() {
        val s = DiscoveryStore(MemoryDiscoveryStorage())
        assertTrue(s.choice().isEmpty())
        assertEquals(DiscoverySwitches(false, false), s.switches())
        assertTrue(s.snapshot().scan.isEmpty())
    }

    @Test
    fun `everything survives a restart`() {
        val storage = MemoryDiscoveryStorage()
        DiscoveryStore(storage).apply {
            setChoice(listOf(StoredChoice(10, ChatKind.Channel, "A", 1)))
            setSwitches(DiscoverySwitches(includePersonalChats = true))
            addScanRow(StoredScanRow(10, ChatKind.Channel, "A", false, 5, 1, 2))
            recordThumbnail(10, 7)
        }
        val again = DiscoveryStore(storage)
        assertEquals(setOf(10L), again.choice())
        assertTrue(again.switches().includePersonalChats)
        assertEquals(listOf(10L), again.snapshot().scanned)
        assertEquals(listOf(7), again.thumbnailsOf(10))
    }

    @Test
    fun `dropping a chat forgets its choice, row and thumbnails`() {
        val s = DiscoveryStore(MemoryDiscoveryStorage())
        s.setChoice(listOf(StoredChoice(10, ChatKind.Channel, "A", 1), StoredChoice(11, ChatKind.Group, "B", 1)))
        s.addScanRow(StoredScanRow(10, ChatKind.Channel, "A", false, 5, 0, 2))
        s.recordThumbnail(10, 7)
        s.dropChat(10)
        assertEquals(setOf(11L), s.choice())
        assertTrue(s.snapshot().scan.isEmpty())
        assertTrue(s.thumbnailsOf(10).isEmpty())
    }

    @Test
    fun `clear wipes the document`() {
        val storage = MemoryDiscoveryStorage()
        val s = DiscoveryStore(storage)
        s.setChoice(listOf(StoredChoice(10, ChatKind.Channel, "A", 1)))
        s.clear()
        assertNull(storage.text)
        assertTrue(s.choice().isEmpty())
        assertFalse(s.switches().includePersonalChats)
    }

    @Test
    fun `a retired store cannot write the cleared data back`() {
        val storage = MemoryDiscoveryStorage()
        val s = DiscoveryStore(storage)
        s.setChoice(listOf(StoredChoice(10, ChatKind.Channel, "A", 1)))
        s.retire()
        storage.clear()
        s.setChoice(listOf(StoredChoice(11, ChatKind.Channel, "B", 1)))
        assertNull(storage.text)
        assertTrue(s.choice().isEmpty())
    }

    @Test
    fun `a damaged document falls back to empty defaults`() {
        assertTrue(DiscoveryStore(MemoryDiscoveryStorage("not json")).choice().isEmpty())
    }
}
