package app.cablegram.discovery

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TelegramDiscoveryTest {
    private val ME = 1L
    private val LIBRARY = 99L
    private val all = listOf(
        TransportChat(10, TransportChatType.Channel, 0, "Channel", false),
        TransportChat(11, TransportChatType.Group, 0, "Group", false),
        TransportChat(12, TransportChatType.Private, 500, "Friend", false),
        TransportChat(13, TransportChatType.Private, ME, "Saved Messages", false),
        TransportChat(14, TransportChatType.Secret, 501, "Secret", false),
        TransportChat(15, TransportChatType.Private, 600, "Bot", false),
        TransportChat(16, TransportChatType.Private, 777000, "Telegram", false),
        TransportChat(LIBRARY, TransportChatType.Channel, 0, "Cablegram library", false),
    )

    private fun TestScopeHolder.discovery(t: FakeDiscoveryTransport) =
        TelegramDiscovery(t, DiscoveryGate({ emptySet() }, { LIBRARY }), RequestThrottle(5, now), { LIBRARY }, DiscoveryStore(MemoryDiscoveryStorage()))

    private class TestScopeHolder(val now: () -> Long)

    private suspend fun scan(switches: DiscoverySwitches, t: FakeDiscoveryTransport, now: () -> Long): List<DiscoveryChat> {
        val permit = ScanPermits().grantScan()
        return TestScopeHolder(now).discovery(t).discoveryChats(permit, switches).toList()
    }

    @Test
    fun `by default only channels and groups are listed`() = runTest {
        val t = FakeDiscoveryTransport(all, ME, bots = setOf(600))
        val kinds = scan(DiscoverySwitches(), t) { currentTime }.map { it.kind }
        assertEquals(listOf(ChatKind.Channel, ChatKind.Group), kinds)
        assertTrue("no bot lookup for chats that are already excluded", "isBot" !in t.calls)
    }

    @Test
    fun `personal chats appear only with their switch, bots and the service account never`() = runTest {
        val t = FakeDiscoveryTransport(all, ME, bots = setOf(600))
        val kinds = scan(DiscoverySwitches(includePersonalChats = true), t) { currentTime }.map { it.kind }
        assertEquals(listOf(ChatKind.Channel, ChatKind.Group, ChatKind.Personal), kinds)
    }

    @Test
    fun `saved messages appear only with their switch`() = runTest {
        val t = FakeDiscoveryTransport(all, ME)
        val kinds = scan(DiscoverySwitches(includeSavedMessages = true), t) { currentTime }.map { it.kind }
        assertEquals(listOf(ChatKind.Channel, ChatKind.Group, ChatKind.SavedMessages), kinds)
    }

    @Test
    fun `a scan without a live permit reads nothing`() = runTest {
        val t = FakeDiscoveryTransport(all, ME)
        val permit = ScanPermits().grantScan().also { it.revoke() }
        val d = TelegramDiscovery(t, DiscoveryGate({ emptySet() }, { LIBRARY }), RequestThrottle(5) { currentTime }, { LIBRARY }, DiscoveryStore(MemoryDiscoveryStorage()))
        assertThrows(DiscoveryRefused::class.java) { kotlinx.coroutines.runBlocking { d.discoveryChats(permit, DiscoverySwitches()).toList() } }
        assertEquals(emptyList<String>(), t.calls)
    }

    private fun video(chat: Long, id: Long, seconds: Int, size: Long = 50_000_000, file: Boolean = false, mime: String = "video/mp4", name: String = "f$id.mp4") =
        TransportVideo(chat, id, "u-$chat-$id", name, "", seconds, size, 100 + id.toInt(), file, mime, null, true)

    private fun TestScopeHolder.counter(t: FakeDiscoveryTransport, store: DiscoveryStore = DiscoveryStore(MemoryDiscoveryStorage())) =
        TelegramDiscovery(t, DiscoveryGate({ emptySet() }, { LIBRARY }), RequestThrottle(5, now), { LIBRARY }, store)

    @Test
    fun `only chats listed by this scan can be counted`() = runTest {
        val t = FakeDiscoveryTransport(all, ME, videos = listOf(video(10, 1, 1200)))
        val d = TestScopeHolder { currentTime }.counter(t)
        val permit = ScanPermits().grantScan()
        d.discoveryChats(permit, DiscoverySwitches()).toList()
        assertEquals(1, d.chatVideoCount(permit, DiscoveryChatId(10)).videos)
        val before = t.calls.size
        assertThrows(DiscoveryRefused::class.java) { kotlinx.coroutines.runBlocking { d.chatVideoCount(permit, DiscoveryChatId(12)) } }
        assertEquals("no read for a chat the scan did not list", before, t.calls.size)
    }

    @Test
    fun `videos shorter than the minimum are not counted, and a big video document counts as about`() = runTest {
        val videos = listOf(
            video(10, 1, 1200), video(10, 2, 700), video(10, 3, 650),      // three of 10 minutes or more
            video(10, 4, 90), video(10, 5, 30),                              // short clips
            video(10, 6, 0, size = 900_000_000, file = true, mime = "application/x-matroska", name = "film.mkv"), // a film sent as a file
            video(10, 7, 0, size = 4_000_000, file = true, name = "clip.mp4"),                                      // a small file
        )
        val t = FakeDiscoveryTransport(all, ME, videos = videos)
        val d = TestScopeHolder { currentTime }.counter(t)
        val permit = ScanPermits().grantScan()
        d.discoveryChats(permit, DiscoverySwitches()).toList()
        val count = d.chatVideoCount(permit, DiscoveryChatId(10))
        assertEquals(3, count.videos)
        assertEquals(1, count.files)
        assertTrue(count.approximate)
    }

    @Test
    fun `any length counts every video and every video document`() = runTest {
        val videos = listOf(video(10, 1, 1200), video(10, 4, 90), video(10, 7, 0, size = 4_000_000, file = true))
        val t = FakeDiscoveryTransport(all, ME, videos = videos)
        val store = DiscoveryStore(MemoryDiscoveryStorage()).also { it.setMinVideoSeconds(0) }
        val d = TestScopeHolder { currentTime }.counter(t, store)
        val permit = ScanPermits().grantScan()
        d.discoveryChats(permit, DiscoverySwitches()).toList()
        val count = d.chatVideoCount(permit, DiscoveryChatId(10))
        assertEquals(2, count.videos)
        assertEquals(1, count.files)
    }

    @Test
    fun `only the newest 100 videos and files of a chat are looked at`() = runTest {
        val videos = (1L..150L).map { video(10, it, 1200) }
        val t = FakeDiscoveryTransport(all, ME, videos = videos)
        val d = TestScopeHolder { currentTime }.counter(t)
        val permit = ScanPermits().grantScan()
        d.discoveryChats(permit, DiscoverySwitches()).toList()
        assertEquals(100, d.chatVideoCount(permit, DiscoveryChatId(10)).videos)
    }

    @Test
    fun `the picker leaves out short videos too`() = runTest {
        val videos = listOf(video(10, 1, 1200), video(10, 2, 90), video(10, 3, 700))
        val t = FakeDiscoveryTransport(all, ME, videos = videos)
        val store = DiscoveryStore(MemoryDiscoveryStorage()).also { it.setChoice(listOf(StoredChoice(10, ChatKind.Channel, "Channel", 0))) }
        val d = TelegramDiscovery(t, DiscoveryGate({ store.choice() }, { LIBRARY }), RequestThrottle(5) { currentTime }, { LIBRARY }, store)
        val page = d.videoPager(setOf(DiscoveryChatId(10))).next(50)
        assertEquals(listOf(3L, 1L), page.map { it.messageId })
    }
}
