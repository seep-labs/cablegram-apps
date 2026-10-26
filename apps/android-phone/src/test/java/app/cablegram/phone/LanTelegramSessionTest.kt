package app.cablegram.phone

import androidx.test.core.app.ApplicationProvider
import app.cablegram.telegram.FakeTelegramApi
import app.cablegram.telegram.TelegramRole
import app.cablegram.telegram.TelegramSession
import app.cablegram.telegram.TelegramState
import app.cablegram.telegram.TgAuthState
import app.cablegram.telegram.TgParameters
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/** A phone without a usable Telegram session tells the TV so (503), but one that is still starting is waited for. */
@RunWith(RobolectricTestRunner::class)
class LanTelegramSessionTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val params = TgParameters("db", "files", ByteArray(32), 1, "hash", "Model", "Android", "test")
    private val credentials = LanCredentials(LanCapabilityVerifier(verify = { "tv-1" })).apply {
        sync(listOf(PairedTv(capability = "cap-tv1", deviceId = "tv-1", name = "Living room", pin = "111111")))
    }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val api = FakeTelegramApi()
    private val session = TelegramSession(api, TelegramRole.Phone, { params }, scope).also { it.start() }
    private var server: LanLibraryServer? = null

    @After
    fun tearDown() {
        server?.stop()
        scope.cancel()
    }

    private fun media(linked: Boolean = true, chatAsked: AtomicBoolean = AtomicBoolean(), waitMs: Long = 2_000) = PhoneTelegramMedia(
        context = context,
        isRemoved = { false },
        sessionProvider = { session },
        wasLinked = { linked },
        readyWaitMs = waitMs,
        chatId = { chatAsked.set(true); null },
    )

    private fun serve(media: TelegramMedia): Int {
        val started = LanLibraryServer(
            store = LibraryStore(context),
            commands = CommandQueue(context),
            credentials = credentials,
            telegram = media,
            hostname = "127.0.0.1",
            port = 0,
        )
        started.start()
        server = started
        return started.listeningPort
    }

    private fun get(port: Int, path: String): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection
        connection.setRequestProperty("Authorization", "Bearer cap-tv1")
        val code = connection.responseCode
        val body = (if (code >= 400) connection.errorStream else connection.inputStream)?.bufferedReader()?.readText().orEmpty()
        return code to body.trim()
    }

    private fun awaitState(predicate: (TelegramState) -> Boolean) = runBlocking { withTimeout(2_000) { session.state.first(predicate) } }

    @Test
    fun `a phone that waits for a sign-in answers 503 telegram_not_signed_in on the link and the stream`() {
        runBlocking { api.authStates.emit(TgAuthState.WaitPhoneNumber) }
        awaitState { it is TelegramState.NeedsPhoneNumber }
        val media = media()
        assertTrue(media.signedOut)
        val port = serve(media)
        assertEquals(503 to """{"error":"telegram_not_signed_in"}""", get(port, "/links/telegram/abcd1234efgh"))
        assertEquals(503 to """{"error":"telegram_not_signed_in"}""", get(port, "/telegram/abcd1234efgh"))
    }

    @Test
    fun `a phone never linked to Telegram here counts as signed out`() {
        val port = serve(media(linked = false))
        assertEquals(503, get(port, "/links/telegram/abcd1234efgh").first)
    }

    @Test
    fun `a session that is still starting is not signed out, and the link is issued`() {
        assertTrue(session.state.value is TelegramState.Starting)
        val media = media()
        assertFalse(media.signedOut)
        val (code, body) = get(serve(media), "/links/telegram/abcd1234efgh")
        assertEquals(200, code)
        assertTrue(body.contains(""""link":"""))
    }

    @Test
    fun `resolve waits for a starting session that becomes ready`() {
        val chatAsked = AtomicBoolean()
        val media = media(chatAsked = chatAsked)
        scope.launch {
            delay(300)
            api.authStates.emit(TgAuthState.Ready)
        }
        // No library channel in this fake, so nothing resolves; asking for the channel proves the wait ended in Ready.
        assertNull(media.resolve("abcd1234efgh"))
        assertTrue(chatAsked.get())
        assertFalse(media.signedOut)
    }

    @Test
    fun `resolve stops waiting once the session asks for a sign-in`() {
        val chatAsked = AtomicBoolean()
        val media = media(chatAsked = chatAsked, waitMs = 10_000)
        scope.launch {
            delay(300)
            api.authStates.emit(TgAuthState.WaitPhoneNumber)
        }
        val began = System.currentTimeMillis()
        assertNull(media.resolve("abcd1234efgh"))
        assertTrue(System.currentTimeMillis() - began < 5_000)
        assertFalse(chatAsked.get())
        assertTrue(media.signedOut)
    }
}
