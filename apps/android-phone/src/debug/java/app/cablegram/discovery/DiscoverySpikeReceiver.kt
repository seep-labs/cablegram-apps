package app.cablegram.discovery

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import app.cablegram.phone.PhoneTelegram
import app.cablegram.telegram.TgException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

/**
 * Debug only, spec 008 T001. `adb shell am broadcast -n app.cablegram.phone/app.cablegram.discovery.DiscoverySpikeReceiver -a SCAN`
 * scans channels and groups and logs timings and counts under the tag `DiscoverySpike`. It skips the owner prompt
 * on purpose (a developer measuring their own test account) and so exists in debug builds only.
 * It logs numbers only: no chat title, id, caption or file name.
 */
class DiscoverySpikeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                PhoneTelegram.session(context)
                val transport = PhoneTelegram.discoveryTransport ?: return@launch log("no transport")
                when (intent.action) {
                    "SCAN" -> scan(transport, intent.getBooleanExtra("personal", false))
                    else -> log("unknown action ${intent.action}")
                }
            } catch (e: Throwable) {
                log("failed: ${e::class.java.simpleName} ${(e as? TgException)?.name ?: ""}")
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun scan(transport: DiscoveryTransport, personal: Boolean) {
        val throttle = RequestThrottle(5)
        val pauses = mutableListOf<Int>()
        val watcher = throttle.pauses.onEach { pauses += it; log("FLOOD_WAIT ${it}s") }.launchIn(CoroutineScope(Dispatchers.IO))
        val permits = ScanPermits()
        val library = transport.chatIds().firstOrNull { transport.chat(it)?.title == "Cablegram library" }
        val discovery = TelegramDiscovery(transport, DiscoveryGate({ emptySet() }, { library }), throttle) { library }
        val permit = permits.grantScan()
        val started = System.currentTimeMillis()
        val chats = mutableListOf<DiscoveryChat>()
        discovery.discoveryChats(permit, DiscoverySwitches(includePersonalChats = personal)).collect { chats += it }
        val listedMs = System.currentTimeMillis() - started
        log("listed ${chats.size} chats in ${listedMs}ms; protected=${chats.count { it.protectedContent }}")
        var withVideos = 0; var approximate = 0; var totalVideos = 0; var totalFiles = 0
        for ((i, chat) in chats.withIndex()) {
            val count = try { discovery.chatVideoCount(permit, chat.id) } catch (e: TgException) { log("count failed ${e.name}"); continue }
            totalVideos += count.videos; totalFiles += count.files
            if (count.total > 0) withVideos++
            if (count.approximate) approximate++
            if ((i + 1) % 25 == 0) log("counted ${i + 1}/${chats.size} at ${System.currentTimeMillis() - started}ms")
        }
        log("done: ${chats.size} chats, $withVideos with videos or files, $approximate approximate, videos=$totalVideos files=$totalFiles, pauses=$pauses, total ${System.currentTimeMillis() - started}ms")
        watcher.cancel()
    }

    private fun log(message: String) { Log.i("DiscoverySpike", message) }
}
