package app.cablegram.phone

import app.cablegram.discovery.DiscoveryChatId
import app.cablegram.discovery.DiscoveryGate
import app.cablegram.discovery.DiscoveryStore
import app.cablegram.discovery.DiscoverySwitches
import app.cablegram.discovery.FakeDiscoveryTransport
import app.cablegram.discovery.ForwardOutcome
import app.cablegram.discovery.MemoryDiscoveryStorage
import app.cablegram.discovery.RequestThrottle
import app.cablegram.discovery.ScanPermits
import app.cablegram.discovery.TelegramDiscovery
import app.cablegram.discovery.TransportChat
import app.cablegram.discovery.TransportChatType
import app.cablegram.discovery.TransportVideo
import app.cablegram.telegram.FakeTelegramApi
import app.cablegram.telegram.TelegramLibrarySync
import app.cablegram.telegram.TgFile
import app.cablegram.telegram.TgVideo
import app.cablegram.telegram.TgVideoPage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Spec 008 T021 (FR-002, SC-003, contract half): after a scan, a choice, paging and a forward, the one thing the
 * control plane hears about is the forwarded videos in the library channel. No request line, header or body
 * carries a source chat id or title.
 */
class DiscoveryPrivacyContractTest {
    private val library = -1001000000099L
    private val canaryIds = listOf(-1009999999001L, -1009999999002L)
    private val canaryTitles = listOf("CANARY-7f3a-one", "CANARY-7f3a-two")
    private lateinit var server: MockWebServer
    private val seen = mutableListOf<RecordedRequest>()

    @Before
    fun setUp() {
        server = MockWebServer().also {
            it.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setResponseCode(201).setBody("{}").also { seen += request }
            }
            it.start()
        }
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `no request to the control plane carries a source chat id or title`() = runBlocking {
        val chats = canaryIds.zip(canaryTitles).map { (id, title) -> TransportChat(id, TransportChatType.Channel, 0, title, false) }
        val videos = canaryIds.flatMapIndexed { c, id ->
            (1..3).map { n ->
                TransportVideo(id, n * 10L, "uid-$c-$n", "clip $c-$n.mp4", "clip $c-$n", 60, 1_000_000, 100 + n, false, "video/mp4", null, true)
            }
        }
        val transport = FakeDiscoveryTransport(chats, 1L, counts = canaryIds.associate { (it to false) to 3 }, videos = videos)
        val store = DiscoveryStore(MemoryDiscoveryStorage())
        val discovery = TelegramDiscovery(transport, DiscoveryGate({ store.choice() }, { library }), RequestThrottle(1000), { library }, store)

        // Scan, choose, page, forward three.
        val listed = discovery.discoveryChats(ScanPermits().grantScan(), DiscoverySwitches()).toList()
        assertTrue("the control must see the canary titles on the phone", listed.map { it.title }.containsAll(canaryTitles))
        store.setChoice(listed.map { app.cablegram.discovery.StoredChoice(it.id.raw, it.kind, it.title, 0) })
        val page = discovery.videoPager(listed.map { it.id }.toSet()).next(50)
        val outcomes = discovery.forwardToLibrary(page.take(3))
        assertEquals(3, outcomes.filterIsInstance<ForwardOutcome.Added>().size)

        // The forwarded messages now sit in the library channel; the 004 sync registers them.
        val api = FakeTelegramApi()
        val inLibrary = page.take(3).mapIndexed { i, v ->
            TgVideo(library, 5000L + i, TgFile(i + 1, v.sizeBytes, v.uniqueId, 0, 0, 0, false), "${v.title}.mp4", v.title, "video/mp4", v.durationSeconds, true, v.date.toLong())
        }
        api.videoPages[0] = TgVideoPage(inLibrary, 0)
        val client = CatalogClient(server.url("/").toString(), null)
        val sync = TelegramLibrarySync(api, library, register = { client.registerTelegramVideo("token", it) }, reconcile = { client.reconcileTelegramVideos("token", it) })
        sync.fullScan()

        assertTrue("the sync must have talked to the control plane", seen.isNotEmpty())
        for (request in seen) {
            val everything = request.requestLine + "\n" + request.headers + "\n" + request.body.readUtf8()
            for (id in canaryIds) assertFalse("source chat id in ${request.requestLine}", everything.contains(id.toString().removePrefix("-")))
            for (title in canaryTitles) assertFalse("source chat title in ${request.requestLine}", everything.contains(title))
        }
    }
}
