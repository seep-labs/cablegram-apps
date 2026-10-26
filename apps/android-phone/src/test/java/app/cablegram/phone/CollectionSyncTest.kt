package app.cablegram.phone

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The same scenarios, against the same fake server, as CollectionSyncTests.swift on iOS. */
class CollectionSyncTest {
    /** The account's collections on a fake server; every call is recorded. A name in [failing] answers with a network failure. */
    private class FakeServer(vararg initial: Pair<String, String>) : CollectionsApi {
        val names = linkedMapOf<String, String>().apply { initial.forEach { put(it.first, it.second) } }
        val members = mutableSetOf<Pair<String, String>>()
        val calls = mutableListOf<String>()
        var reachable = true
        var failing = setOf<String>()
        private var next = 0

        private fun fails(call: String) = !reachable || call in failing
        override suspend fun list(): List<RemoteCollection>? = if (!reachable) null else names.map { RemoteCollection(it.key, it.value) }
        override suspend fun create(name: String): CollectionCall<RemoteCollection> {
            calls += "create $name"
            if (fails("create")) return CollectionCall(CallOutcome.Failed)
            if (names.values.any { it.equals(name, ignoreCase = true) }) return CollectionCall(CallOutcome.Conflict)
            val id = "N${++next}"
            names[id] = name
            return CollectionCall(CallOutcome.Ok, RemoteCollection(id, name))
        }
        override suspend fun rename(id: String, name: String): CollectionCall<RemoteCollection> {
            calls += "rename $id $name"
            if (fails("rename")) return CollectionCall(CallOutcome.Failed)
            if (id !in names) return CollectionCall(CallOutcome.NotFound)
            if (names.any { it.key != id && it.value.equals(name, ignoreCase = true) }) return CollectionCall(CallOutcome.Conflict)
            names[id] = name
            return CollectionCall(CallOutcome.Ok, RemoteCollection(id, name))
        }
        override suspend fun delete(id: String): CollectionCall<Unit> {
            calls += "delete $id"
            if (fails("delete")) return CollectionCall(CallOutcome.Failed)
            if (names.remove(id) == null) return CollectionCall(CallOutcome.NotFound)
            members.removeAll { it.first == id }
            return CollectionCall(CallOutcome.Ok, Unit)
        }
        override suspend fun setMember(collectionId: String, itemId: String, present: Boolean): CollectionCall<Unit> {
            calls += "member $collectionId $itemId $present"
            if (fails("member")) return CollectionCall(CallOutcome.Failed)
            if (collectionId !in names) return CollectionCall(CallOutcome.NotFound)
            if (present) members += collectionId to itemId else members -= collectionId to itemId
            return CollectionCall(CallOutcome.Ok, Unit)
        }
        fun membersOf(item: String) = members.filter { it.second == item }.map { it.first }
    }

    private fun sync(server: FakeServer, state: CollectionsState): CollectionsState? {
        val serverMembers = state.members.mapNotNull { it.serverItemId }.distinct().associateWith { server.membersOf(it) }
        return runBlocking { syncCollections(server, state, serverMembers) }
    }

    private fun local(id: String, name: String, synced: Boolean = false, dirty: Boolean = false) = UserCollection(id, name, synced, dirty)
    private fun item(id: String, ids: List<String>, pending: Boolean = false, server: String? = id) = MemberState(id, server, ids, pending)

    @Test
    fun `phone-only collections are created once and their titles move to the server's ids`() {
        val server = FakeServer()
        val start = CollectionsState(listOf(local("L1", "Kids")), emptyList(), listOf(item("a", listOf("L1")), item("b", emptyList())))
        val first = sync(server, start)!!
        assertEquals(listOf(UserCollection("N1", "Kids", synced = true)), first.collections)
        assertEquals(listOf("N1"), first.members.first { it.itemId == "a" }.collectionIds)
        assertEquals(false, first.members.first { it.itemId == "a" }.pending)
        assertEquals(listOf("N1"), server.membersOf("a"))
        // Running it again changes nothing and calls nothing.
        server.calls.clear()
        assertEquals(first, sync(server, first))
        assertTrue(server.calls.isEmpty())
    }

    @Test
    fun `a collection with the same name already on the account is used, ignoring case`() {
        val server = FakeServer("S9" to "kids")
        val start = CollectionsState(listOf(local("L1", "Kids"), local("L2", "Favourites")), emptyList(), listOf(item("a", listOf("L1", "L2"))))
        val result = sync(server, start)!!
        assertEquals(listOf("create Favourites"), server.calls.filter { it.startsWith("create") })
        assertEquals(listOf("S9", "N1").sorted(), result.collections.map { it.id }.sorted())
        assertEquals("kids", result.collections.first { it.id == "S9" }.name)
        assertEquals(setOf("S9", "N1"), result.members.single().collectionIds.toSet())
        assertEquals(setOf("S9", "N1"), server.membersOf("a").toSet())
    }

    @Test
    fun `two local collections with one name become one`() {
        val server = FakeServer()
        val start = CollectionsState(listOf(local("L1", "Kids"), local("L2", "kids")), emptyList(), listOf(item("a", listOf("L1")), item("b", listOf("L2"))))
        val result = sync(server, start)!!
        assertEquals(1, result.collections.size)
        assertEquals(1, server.names.size)
        assertEquals(listOf(listOf("N1"), listOf("N1")), result.members.map { it.collectionIds })
    }

    @Test
    fun `a collection that could not be created stays on the phone and is tried again`() {
        val server = FakeServer().apply { failing = setOf("create") }
        val start = CollectionsState(listOf(local("L1", "Kids")), emptyList(), listOf(item("a", listOf("L1"), pending = true)))
        val first = sync(server, start)!!
        assertEquals(start, first)
        server.failing = emptySet()
        val second = sync(server, first)!!
        assertEquals(listOf(UserCollection("N1", "Kids", synced = true)), second.collections)
        assertEquals(listOf("N1"), server.membersOf("a"))
    }

    @Test
    fun `an unreachable server changes nothing`() {
        val server = FakeServer().apply { reachable = false }
        assertNull(sync(server, CollectionsState(listOf(local("L1", "Kids")), listOf("S5"), emptyList())))
    }

    @Test
    fun `a rename is sent once, takes the server's name on a clash and is made again when the collection is gone`() {
        val server = FakeServer("S1" to "Kids", "S2" to "Favourites")
        val renamed = sync(server, CollectionsState(listOf(local("S1", "Family", synced = true, dirty = true), local("S2", "Favourites", synced = true)), emptyList(), emptyList()))!!
        assertEquals("Family", server.names["S1"])
        assertEquals(false, renamed.collections.first { it.id == "S1" }.dirty)

        val clash = sync(server, CollectionsState(listOf(local("S1", "favourites", synced = true, dirty = true), local("S2", "Favourites", synced = true)), emptyList(), emptyList()))!!
        assertEquals("Family", clash.collections.first { it.id == "S1" }.name)
        assertEquals(false, clash.collections.first { it.id == "S1" }.dirty)

        server.names.remove("S2")
        val gone = sync(server, CollectionsState(listOf(local("S2", "Best", synced = true, dirty = true)), emptyList(), listOf(item("a", listOf("S2")))))!!
        assertEquals(1, gone.collections.count { it.name == "Best" })
        assertTrue(gone.collections.first { it.name == "Best" }.synced)
        assertEquals(listOf("Best"), server.names.values.filter { it == "Best" })
    }

    @Test
    fun `a delete is sent until the server confirms, and a deleted collection is not brought back meanwhile`() {
        val server = FakeServer("S1" to "Kids", "S2" to "Favourites").apply { failing = setOf("delete") }
        val start = CollectionsState(listOf(local("S2", "Favourites", synced = true)), listOf("S1"), emptyList())
        val first = sync(server, start)!!
        assertEquals(listOf("S1"), first.pendingDeletes)
        assertEquals(listOf("S2"), first.collections.map { it.id })
        server.failing = emptySet()
        val second = sync(server, first)!!
        assertEquals(emptyList<String>(), second.pendingDeletes)
        assertEquals(setOf("S2"), server.names.keys)
        // An id the server no longer knows counts as deleted.
        assertEquals(emptyList<String>(), sync(server, CollectionsState(second.collections, listOf("S7"), emptyList()))!!.pendingDeletes)
    }

    @Test
    fun `collections made or deleted on another phone arrive, unless this phone has an unsent change for them`() {
        val server = FakeServer("S1" to "Kids", "S3" to "New elsewhere")
        val start = CollectionsState(
            listOf(local("S1", "Old name", synced = true), local("S2", "Deleted elsewhere", synced = true), local("S4", "Also deleted", synced = true)),
            emptyList(),
            listOf(item("a", listOf("S4"), pending = true), item("b", listOf("S2"))),
        )
        val result = sync(server, start)!!
        assertEquals("Kids", result.collections.first { it.id == "S1" }.name)
        assertTrue(result.collections.any { it.id == "S3" && it.synced })
        // S2 had nothing unsent: gone, and taken out of its titles.
        assertTrue(result.collections.none { it.id == "S2" })
        assertEquals(emptyList<String>(), result.members.first { it.itemId == "b" }.collectionIds)
        // S4 has an unsent membership: kept and made again.
        assertEquals(1, result.collections.count { it.name == "Also deleted" })
        assertEquals(listOf("a"), server.members.map { it.second }.distinct())
    }

    @Test
    fun `an edited title sends its difference, any other title takes the server's list`() {
        val server = FakeServer("S1" to "Kids", "S2" to "Favourites").apply { members += "S1" to "a"; members += "S1" to "b"; members += "S2" to "c" }
        val synced = listOf(local("S1", "Kids", synced = true), local("S2", "Favourites", synced = true))
        val start = CollectionsState(
            synced, emptyList(),
            listOf(item("a", listOf("S2"), pending = true), item("b", emptyList()), item("c", listOf("S1")), item("d", emptyList(), pending = true, server = null)),
        )
        val result = sync(server, start)!!
        val byId = result.members.associateBy { it.itemId }
        // a: pending, wanted only S2 → left S1, joined S2.
        assertEquals(listOf("S2"), server.membersOf("a"))
        assertEquals(false, byId.getValue("a").pending)
        // b and c were not edited here: they take what the server has.
        assertEquals(listOf("S1"), byId.getValue("b").collectionIds)
        assertEquals(listOf("S2"), byId.getValue("c").collectionIds)
        // d is not on the server yet: untouched and still pending.
        assertEquals(item("d", emptyList(), pending = true, server = null), byId.getValue("d"))
    }

    @Test
    fun `a membership that could not be sent stays pending and is sent again`() {
        val server = FakeServer("S1" to "Kids").apply { failing = setOf("member") }
        val start = CollectionsState(listOf(local("S1", "Kids", synced = true)), emptyList(), listOf(item("a", listOf("S1"), pending = true)))
        val first = sync(server, start)!!
        assertEquals(true, first.members.single().pending)
        assertEquals(listOf("S1"), first.members.single().collectionIds)
        server.failing = emptySet()
        val second = sync(server, first)!!
        assertEquals(false, second.members.single().pending)
        assertEquals(listOf("S1"), server.membersOf("a"))
    }
}
