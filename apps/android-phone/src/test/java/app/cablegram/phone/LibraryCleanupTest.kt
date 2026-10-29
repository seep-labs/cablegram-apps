package app.cablegram.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryCleanupTest {
    private val offline = LibraryConnections(telegramNeedsConnection = true, cloudNeedsConnection = true)
    private val online = LibraryConnections()

    private fun make(id: String, change: (LibraryItem) -> LibraryItem = { it }) =
        change(LibraryItem(id = id, title = id, filename = "$id.mp4", importedAt = "2026-01-01T00:00:00Z"))

    @Test fun `a title on this phone has no problem`() {
        assertNull(libraryProblem(make("local"), offline))
    }

    @Test fun `a missing source is unavailable and a household only title is not on this phone`() {
        assertEquals(LibraryProblem.Unavailable, libraryProblem(make("gone") { it.copy(sourceAvailable = false) }, online))
        assertEquals(LibraryProblem.NotOnPhone, libraryProblem(make("other") { it.copy(sourceAvailable = false, householdOnly = true) }, online))
    }

    @Test fun `a telegram title is a problem only while telegram is not connected here`() {
        val title = make("tg") { it.copy(sourceKind = "telegram") }
        assertEquals(LibraryProblem.TelegramNotConnected, libraryProblem(title, offline))
        assertNull(libraryProblem(title, online))
        assertEquals(LibraryProblem.Unavailable, libraryProblem(make("tg-gone") { it.copy(sourceKind = "telegram", sourceAvailable = false) }, online))
    }

    @Test fun `a cloud only title is a problem only while its storage is disconnected`() {
        val cloudOnly = make("cloud") { it.copy(ownCloudCopy = true, sourceAvailable = false) }
        assertEquals(LibraryProblem.CloudNotConnected, libraryProblem(cloudOnly, offline))
        assertNull(libraryProblem(cloudOnly, online))
        // Still on the phone as well: it plays from the phone whatever the storage does.
        assertNull(libraryProblem(make("both") { it.copy(ownCloudCopy = true) }, offline))
    }

    @Test fun `groups keep a fixed order and leave empty ones out`() {
        val items = listOf(
            make("tg") { it.copy(sourceKind = "telegram") },
            make("gone") { it.copy(sourceAvailable = false) },
            make("fine"),
            make("cloud") { it.copy(ownCloudCopy = true, sourceAvailable = false) },
            make("gone2") { it.copy(sourceAvailable = false) },
        )
        val groups = cleanupGroups(items, offline)
        assertEquals(listOf(LibraryProblem.Unavailable, LibraryProblem.TelegramNotConnected, LibraryProblem.CloudNotConnected), groups.map { it.problem })
        assertEquals(listOf("gone", "gone2"), groups[0].items.map { it.id })
        assertTrue(cleanupGroups(listOf(make("fine")), offline).isEmpty())
    }

    @Test fun `connection problems need a connection and the others do not`() {
        assertTrue(LibraryProblem.TelegramNotConnected.needsConnection && LibraryProblem.CloudNotConnected.needsConnection)
        assertFalse(LibraryProblem.Unavailable.needsConnection || LibraryProblem.NotOnPhone.needsConnection)
    }
}
