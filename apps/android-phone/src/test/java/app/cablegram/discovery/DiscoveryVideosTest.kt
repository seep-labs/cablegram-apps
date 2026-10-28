package app.cablegram.discovery

import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscoveryVideosTest {
    private val LIBRARY = 99L
    private val chats = listOf(
        TransportChat(10, TransportChatType.Channel, 0, "A", false),
        TransportChat(11, TransportChatType.Group, 0, "B", false),
        TransportChat(12, TransportChatType.Channel, 0, "Protected", true),
        TransportChat(13, TransportChatType.Channel, 0, "Other", false),
    )

    private fun video(chat: Long, id: Long, date: Int, file: Boolean = false, mime: String = "video/mp4", name: String = "v$id.mp4", save: Boolean = true) =
        TransportVideo(chat, id, "u$id", name, "", 60, 1000, date, file, mime, 5, save)

    private fun TestScope2.build(t: FakeDiscoveryTransport, choice: Set<Long>) =
        TelegramDiscovery(t, DiscoveryGate({ choice }, { LIBRARY }), RequestThrottle(5, now), { LIBRARY }, DiscoveryStore(MemoryDiscoveryStorage()).also { it.setMinVideoSeconds(0) })

    private class TestScope2(val now: () -> Long)

    @Test
    fun `a chat outside the choice is refused before any call to Telegram`() = runTest {
        val t = FakeDiscoveryTransport(chats)
        val d = TestScope2 { currentTime }.build(t, setOf(10))
        assertThrows(DiscoveryRefused::class.java) { d.videoPager(setOf(DiscoveryChatId(10), DiscoveryChatId(13))) }
        assertThrows(DiscoveryRefused::class.java) { d.videoPager(setOf(DiscoveryChatId(LIBRARY))) }
        assertEquals(emptyList<String>(), t.calls)
    }

    @Test
    fun `videos merge newest first across chats and the pager ends on an empty page`() = runTest {
        val t = FakeDiscoveryTransport(chats, videos = listOf(video(10, 1, 100), video(10, 2, 300), video(11, 3, 200), video(11, 4, 400)))
        val pager = TestScope2 { currentTime }.build(t, setOf(10, 11)).videoPager(setOf(DiscoveryChatId(10), DiscoveryChatId(11)))
        assertEquals(listOf(4L, 2L, 3L), pager.next(3).map { it.messageId })
        assertEquals(listOf(1L), pager.next(3).map { it.messageId })
        assertTrue(pager.next(3).isEmpty())
        assertTrue(pager.exhausted)
    }

    @Test
    fun `files are listed only when they are videos`() = runTest {
        val t = FakeDiscoveryTransport(chats, videos = listOf(
            video(10, 1, 100, file = true, mime = "video/x-matroska", name = "a.mkv"),
            video(10, 2, 200, file = true, mime = "application/octet-stream", name = "b.MKV"),
            video(10, 3, 300, file = true, mime = "application/pdf", name = "c.pdf"),
        ))
        val pager = TestScope2 { currentTime }.build(t, setOf(10)).videoPager(setOf(DiscoveryChatId(10)))
        assertEquals(listOf(2L, 1L), pager.next(10).map { it.messageId })
    }

    @Test
    fun `protected chats and unsaveable messages are marked`() = runTest {
        val t = FakeDiscoveryTransport(chats, videos = listOf(video(12, 1, 100), video(10, 2, 200, save = false), video(10, 3, 300)))
        val pager = TestScope2 { currentTime }.build(t, setOf(10, 12)).videoPager(setOf(DiscoveryChatId(10), DiscoveryChatId(12)))
        assertEquals(mapOf(3L to false, 2L to true, 1L to true), pager.next(10).associate { it.messageId to it.protectedContent })
    }

    @Test
    fun `forward goes only to the library, only what was offered, never protected`() = runTest {
        val t = FakeDiscoveryTransport(chats, videos = listOf(video(10, 1, 100), video(10, 2, 200), video(10, 3, 300, save = false), video(11, 4, 400)), failForward = setOf(2))
        val d = TestScope2 { currentTime }.build(t, setOf(10))
        val offered = d.videoPager(setOf(DiscoveryChatId(10))).next(10)
        val outcomes = d.forwardToLibrary(offered)
        assertTrue(t.forwards.all { it.first == LIBRARY && it.second == 10L })
        assertEquals(listOf(1L, 2L), t.forwards.single().third.sorted())
        assertEquals(setOf(1L), outcomes.filterIsInstance<ForwardOutcome.Added>().map { it.video.messageId }.toSet())
        assertEquals(setOf(2L, 3L), outcomes.filterIsInstance<ForwardOutcome.Failed>().map { it.video.messageId }.toSet())
        // A message the picker never returned, or from a chat outside the choice, is refused with no call.
        val before = t.calls.size
        assertThrows(DiscoveryRefused::class.java) { kotlinx.coroutines.runBlocking { d.forwardToLibrary(listOf(offered.first().copy(messageId = 77))) } }
        assertThrows(DiscoveryRefused::class.java) { kotlinx.coroutines.runBlocking { d.forwardToLibrary(listOf(offered.first().copy(chatId = DiscoveryChatId(13)))) } }
        assertEquals(before, t.calls.size)
    }

    @Test
    fun `purging a chat deletes exactly the thumbnails it downloaded and forgets it`() = runTest {
        val t = FakeDiscoveryTransport(chats, videos = listOf(video(10, 1, 100)))
        val store = DiscoveryStore(MemoryDiscoveryStorage()).also { it.setMinVideoSeconds(0) }
        val d = TelegramDiscovery(t, DiscoveryGate({ setOf(10) }, { LIBRARY }), RequestThrottle(5) { currentTime }, { LIBRARY }, store)
        val v = d.videoPager(setOf(DiscoveryChatId(10))).next(5).single()
        d.thumbnail(v)
        assertEquals(listOf(5), store.thumbnailsOf(10))
        d.purgeChat(10)
        assertEquals(listOf(5), t.deletedFiles)
        assertEquals(listOf(listOf(10L)), t.optimized)
        assertTrue(store.thumbnailsOf(10).isEmpty())
    }
}
