package app.cablegram.phone

/** What one collections call answered; a [NotFound] or [Conflict] is the server's decision, [Failed] means try again later. */
enum class CallOutcome { Ok, Conflict, NotFound, Failed }

class CollectionCall<out T>(val outcome: CallOutcome, val value: T? = null) {
    val ok: Boolean get() = outcome == CallOutcome.Ok
}

/** The account's collection routes (contracts/collections.md). */
interface CollectionsApi {
    /** Null when the server could not be reached; the sync then changes nothing. */
    suspend fun list(): List<RemoteCollection>?
    suspend fun create(name: String): CollectionCall<RemoteCollection>
    suspend fun rename(id: String, name: String): CollectionCall<RemoteCollection>
    suspend fun delete(id: String): CollectionCall<Unit>
    suspend fun setMember(collectionId: String, itemId: String, present: Boolean): CollectionCall<Unit>
}

/** A title as the collections sync sees it. [serverItemId] is null until the title has been pushed to the catalog. */
data class MemberState(val itemId: String, val serverItemId: String?, val collectionIds: List<String>, val pending: Boolean)

data class CollectionsState(val collections: List<UserCollection>, val pendingDeletes: List<String>, val members: List<MemberState>)

private fun String.sameName(other: String) = trim().equals(other.trim(), ignoreCase = true)

/**
 * Reconciles the phone's collections and each title's collection ids with the server, in the order the contract describes:
 * deletes, renames, what was deleted elsewhere, uploads (merging by name), what was made elsewhere, then membership. Nothing the phone has not sent is
 * dropped. [serverMembers] maps a catalog title id to the ids of the collections it is in. Returns null when the server could
 * not be listed, and the new state otherwise (a call that failed leaves its change pending for the next sync).
 */
suspend fun syncCollections(api: CollectionsApi, state: CollectionsState, serverMembers: Map<String, List<String>>): CollectionsState? {
    var server = api.list() ?: return null
    var collections = state.collections
    var members = state.members

    // 1. Deletes made here. A 404 means it is already gone.
    val stillDeleting = mutableListOf<String>()
    for (id in state.pendingDeletes.distinct()) {
        val outcome = api.delete(id).outcome
        if (outcome == CallOutcome.Ok || outcome == CallOutcome.NotFound) server = server.filterNot { it.id == id } else stillDeleting += id
    }

    fun markPending(ids: Set<String>) {
        members = members.map { if (it.collectionIds.any(ids::contains)) it.copy(pending = true) else it }
    }

    // 2. Renames made here. A name taken meanwhile gives the server's name back; a collection deleted elsewhere is made again.
    collections = collections.map { c ->
        if (!c.synced || !c.dirty) return@map c
        val result = api.rename(c.id, c.name)
        when (result.outcome) {
            CallOutcome.Ok -> c.copy(name = result.value?.name ?: c.name, dirty = false)
            CallOutcome.Conflict -> c.copy(name = server.firstOrNull { it.id == c.id }?.name ?: c.name, dirty = false)
            CallOutcome.NotFound -> { markPending(setOf(c.id)); c.copy(synced = false, dirty = false) }
            CallOutcome.Failed -> c
        }
    }

    // 3. Collections deleted elsewhere go, taken out of their titles; one this phone still has an unsent change for is made again.
    val serverIds = server.mapTo(mutableSetOf()) { it.id }
    collections = collections.mapNotNull { c ->
        if (!c.synced || c.id in serverIds) return@mapNotNull c
        if (members.any { it.pending && c.id in it.collectionIds }) return@mapNotNull c.copy(synced = false, dirty = false)
        members = members.map { m -> if (c.id in m.collectionIds) m.copy(collectionIds = m.collectionIds - c.id) else m }
        null
    }

    // 4. Collections made here: use the account's collection with the same name, or create one. Titles move to the server's id.
    val resolved = mutableListOf<UserCollection>()
    for (c in collections) {
        if (c.synced) { if (resolved.none { it.id == c.id }) resolved += c; continue }
        var target = server.firstOrNull { it.name.sameName(c.name) }
        if (target == null) {
            val created = api.create(c.name)
            when (created.outcome) {
                CallOutcome.Ok -> target = created.value
                // Someone created that name since the list: look again.
                CallOutcome.Conflict -> target = api.list()?.also { server = it }?.firstOrNull { it.name.sameName(c.name) }
                else -> {}
            }
        }
        if (target == null) { resolved += c; continue }
        if (server.none { it.id == target.id }) server = server + target
        members = members.map { m ->
            if (c.id !in m.collectionIds) m
            else m.copy(collectionIds = m.collectionIds.map { if (it == c.id) target.id else it }.distinct(), pending = true)
        }
        if (resolved.none { it.id == target.id }) resolved += UserCollection(target.id, target.name, synced = true, dirty = false)
    }
    collections = resolved

    // 4b. Collections made elsewhere arrive, and renamed ones take the new name.
    val deleting = stillDeleting.toSet()
    for (s in server) {
        if (s.id in deleting) continue
        val local = collections.firstOrNull { it.id == s.id }
        collections = when {
            local == null -> collections + UserCollection(s.id, s.name, synced = true)
            local.synced && !local.dirty && local.name != s.name -> collections.map { if (it.id == s.id) it.copy(name = s.name) else it }
            else -> collections
        }
    }

    // 5. Membership: a title edited here sends its difference; any other title takes the server's list.
    val synced = collections.filter { it.synced }.mapTo(mutableSetOf()) { it.id }
    members = members.map { m ->
        val serverItem = m.serverItemId ?: return@map m
        val onServer = serverMembers[serverItem] ?: return@map m
        val localOnly = m.collectionIds.filter { it !in synced }
        if (!m.pending) return@map m.copy(collectionIds = (onServer.filter { it in synced } + localOnly).distinct())
        val wanted = m.collectionIds.filter { it in synced }
        val calls = wanted.filter { it !in onServer }.map { it to true } + onServer.filter { it in synced && it !in wanted }.map { it to false }
        val failed = calls.any { (collection, present) -> api.setMember(collection, serverItem, present).outcome.let { it != CallOutcome.Ok && it != CallOutcome.NotFound } }
        when {
            failed -> m
            localOnly.isNotEmpty() -> m
            else -> m.copy(collectionIds = wanted, pending = false)
        }
    }

    return CollectionsState(collections, stillDeleting, members)
}
