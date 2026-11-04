package app.cablegram.phone

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class LibraryStoreForgetHouseholdTest {
    @Test fun `a deleted account leaves only files on this phone, so web titles can't double up in the next account`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        LibraryStore::class.java.getDeclaredField("instance").apply { isAccessible = true; set(null, null) }
        val dir = java.io.File(context.filesDir, "library")
        dir.deleteRecursively()
        dir.mkdirs()
        fun item(id: String, kind: String) =
            LibraryItem(id = id, title = id, filename = "$id.mkv", importedAt = "2026-01-01T00:00:00Z", sourceKind = kind)
        val file = LibraryFile(items = listOf(item("mine", "phone_local"), item("film", "web"), item("channel", "telegram")))
        java.io.File(dir, "index.json").writeText(kotlinx.serialization.json.Json { encodeDefaults = true }.encodeToString(LibraryFile.serializer(), file))
        val store = LibraryStore(context)

        store.forgetHousehold()

        assertEquals(listOf("mine"), store.list().map { it.id })
    }
}
