package app.cablegram.phone

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryStoreCollectionsTest {
    private lateinit var store: LibraryStore

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // The store is a singleton that remembers its first context; every test gets a fresh one with its own files.
        LibraryStore::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        val dir = java.io.File(context.filesDir, "library")
        dir.deleteRecursively()
        dir.mkdirs()
        val file = LibraryFile(items = listOf(LibraryItem(id = "a", title = "A", filename = "a.mkv", importedAt = "2026-01-01T00:00:00Z")))
        java.io.File(dir, "index.json").writeText(kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(LibraryFile.serializer(), file))
        store = LibraryStore(context)
    }

    private fun addSynced(id: String, name: String) {
        val base = store.collectionsState()
        store.applyCollectionsState(base, base.copy(collections = base.collections + UserCollection(id, name, synced = true)))
    }

    @Test
    fun `a new collection is not synced, and asking for an existing name gives that collection`() {
        val kids = store.addCollection("  Kids ")
        assertEquals(UserCollection(kids.id, "Kids", synced = false, dirty = false), kids)
        assertEquals(kids, store.addCollection("kids"))
        assertEquals(1, store.collections().size)
    }

    @Test
    fun `renaming marks a synced collection dirty, refuses a name another collection has, and a local one stays clean`() {
        addSynced("S1", "Kids")
        val local = store.addCollection("Favourites")
        assertTrue(store.renameCollection("S1", "Family"))
        assertEquals(UserCollection("S1", "Family", synced = true, dirty = true), store.collections().first { it.id == "S1" })
        assertFalse(store.renameCollection("S1", "favourites"))
        assertFalse(store.renameCollection("S1", "   "))
        assertTrue(store.renameCollection(local.id, "Best"))
        assertFalse(store.collections().first { it.id == local.id }.dirty)
        assertTrue(store.renameCollection("S1", "Family"))
        assertEquals("Family", store.collections().first { it.id == "S1" }.name)
    }

    @Test
    fun `deleting a synced collection is remembered for the server, a local one is not, and titles only lose the collection`() {
        addSynced("S1", "Kids")
        val local = store.addCollection("Local")
        store.setItemCollections("a", listOf("S1", local.id))
        store.deleteCollection("S1")
        store.deleteCollection(local.id)
        assertEquals(listOf("S1"), store.snapshot().pendingCollectionDeletes)
        assertEquals(emptyList<String>(), store.get("a")!!.collectionIds)
        assertTrue(store.collections().isEmpty())
    }

    @Test
    fun `editing a title's collections marks it pending`() {
        addSynced("S1", "Kids")
        assertFalse(store.get("a")!!.collectionsPending)
        store.setItemCollections("a", listOf("S1", "S1"))
        assertEquals(listOf("S1"), store.get("a")!!.collectionIds)
        assertTrue(store.get("a")!!.collectionsPending)
    }

    @Test
    fun `a sync result is written back, except for a part that was edited while it ran`() {
        val kids = store.addCollection("Kids")
        store.setItemCollections("a", listOf(kids.id))
        val base = store.collectionsState()
        val result = CollectionsState(
            listOf(UserCollection("S1", "Kids", synced = true)), emptyList(),
            listOf(MemberState("a", "a", listOf("S1"), pending = false)),
        )
        // The title is edited again while the sync runs: its part is skipped (the next sync redoes it).
        store.setItemCollections("a", listOf(kids.id, "other"))
        store.applyCollectionsState(base, result)
        assertEquals(listOf(kids.id, "other"), store.get("a")!!.collectionIds)
        assertTrue(store.get("a")!!.collectionsPending)
        assertEquals(listOf("S1"), store.collections().map { it.id })

        // Nothing changed meanwhile: written back as it is.
        val again = store.collectionsState()
        store.applyCollectionsState(again, again.copy(members = listOf(MemberState("a", "a", listOf("S1"), pending = false))))
        assertEquals(listOf("S1"), store.get("a")!!.collectionIds)
        assertFalse(store.get("a")!!.collectionsPending)
    }
}
