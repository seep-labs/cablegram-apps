package app.cablegram.phone

import app.cablegram.discovery.DiscoveryGraph
import app.cablegram.discovery.DiscoveryTransport
import app.cablegram.discovery.FakeDiscoveryTransport
import app.cablegram.discovery.MemoryDiscoveryStorage
import app.cablegram.discovery.RecentPage
import app.cablegram.discovery.TransportChat
import app.cablegram.discovery.TransportChatType
import app.cablegram.discovery.TransportVideo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The owner need not wait for the scan: they can choose chats while it runs, and Use stops it. */
@OptIn(ExperimentalCoroutinesApi::class)
class FindVideosModelTest {
    private val library = 99L
    private val chats = listOf(10L, 11L, 12L).map { TransportChat(it, TransportChatType.Channel, 0, "Chat $it", false) }
    private fun video(chat: Long, id: Long) = TransportVideo(chat, id, "u$chat-$id", "f.mp4", "", 3_600, 1_000, 100, false, "video/mp4", null, true)

    /** Chat 10 is counted at once; the others wait for [release], like a scan still in progress. */
    private class SlowTransport(private val inner: FakeDiscoveryTransport, val release: CompletableDeferred<Unit>) : DiscoveryTransport by inner {
        override suspend fun recent(chatId: Long, fromMessageId: Long, limit: Int): RecentPage {
            if (chatId != 10L) release.await()
            return inner.recent(chatId, fromMessageId, limit)
        }
    }

    private fun graph(release: CompletableDeferred<Unit>): DiscoveryGraph {
        val fake = FakeDiscoveryTransport(chats, videos = listOf(video(10, 1), video(11, 1), video(12, 1)))
        return DiscoveryGraph.create(SlowTransport(fake, release), MemoryDiscoveryStorage(), library)
    }

    @Test
    fun `a chat ticked while the scan runs can be chosen, and Use stops the scan`() = runTest {
        val release = CompletableDeferred<Unit>()
        val graph = graph(release)
        val model = FindVideosModel(graph, this)

        model.look()
        advanceUntilIdle() // chat 10 is counted; 11 and 12 are still waiting
        assertEquals(FindVideosModel.Phase.Scanning, model.phase)
        assertEquals(listOf(10L), model.rows.map { it.chatId })

        model.toggle(10)
        var saved = false
        model.useChats { saved = true }
        advanceUntilIdle()

        assertTrue("Use saved the choice while the scan was unfinished", saved)
        assertEquals(setOf(10L), graph.store.choice())
        assertEquals("the scan stopped", FindVideosModel.Phase.Done, model.phase)
        assertFalse("an unfinished scan is not marked complete, so the next Look continues it", graph.store.snapshot().scanCompleted)
        assertEquals("what the scan had read stays", listOf(10L), graph.store.snapshot().scanned)
    }

    @Test
    fun `a row keeps its place when a rescan counts it again`() = runTest {
        val release = CompletableDeferred<Unit>().also { it.complete(Unit) }
        val graph = graph(release)
        val model = FindVideosModel(graph, this)
        model.look(); advanceUntilIdle()
        val order = model.rows.map { it.chatId }
        assertEquals(setOf(10L, 11L, 12L), order.toSet())

        // A new model reads the stored rows, then a second scan replaces them in place.
        val again = FindVideosModel(graph, this)
        again.look(); advanceUntilIdle()
        assertEquals(order, again.rows.map { it.chatId })
    }
}
