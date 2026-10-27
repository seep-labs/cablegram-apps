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
                    "PAGE" -> page(transport, intent.getBooleanExtra("personal", false))
                    "FORWARD" -> forward(transport, intent.getIntExtra("n", 3))
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
        val discovery = TelegramDiscovery(transport, DiscoveryGate({ emptySet() }, { library }), throttle, { library }, DiscoveryStore(object : DiscoveryStorage { override fun read(): String? = null; override fun write(json: String) = Unit; override fun clear() = Unit }))
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

    /** Pages 20 videos and 20 files from each chat that has some; checks canBeSaved, thumbnails and the purge. */
    private suspend fun page(transport: DiscoveryTransport, personal: Boolean) {
        val throttle = RequestThrottle(5)
        var chatsSeen = 0
        for (id in transport.chatIds()) {
            val chat = throttle.run { transport.chat(id) } ?: continue
            val ok = chat.type == TransportChatType.Channel || chat.type == TransportChatType.Group || (personal && chat.type == TransportChatType.Private)
            if (!ok || chat.title == "Cablegram library") continue
            val started = System.currentTimeMillis()
            val videos = throttle.run { transport.search(id, false, 0, 20) }
            val files = throttle.run { transport.search(id, true, 0, 20) }
            val ms = System.currentTimeMillis() - started
            val all = videos.videos + files.videos
            if (all.isEmpty()) continue
            chatsSeen++
            val notSaveable = all.count { !it.canBeSaved }
            val video = files.videos.count { it.mimeType.startsWith("video/") }
            val ext = files.videos.count { v -> VIDEO_EXTENSIONS.any { v.name.lowercase().endsWith(".$it") } }
            val mimes = files.videos.groupingBy { it.mimeType.substringBefore("/") }.eachCount()
            log("chat#$chatsSeen protected=${chat.protectedContent} videos=${videos.videos.size} files=${files.videos.size} (video mime ${video}, video extension ${ext}, mime types $mimes) canBeSaved=false:$notSaveable page=${ms}ms next=${videos.nextFromMessageId != 0L}")
            val thumbs = all.mapNotNull { it.thumbnailFileId }.take(5)
            val bytes = thumbs.sumOf { throttle.run { transport.downloadThumbnail(it) } }
            thumbs.forEach { transport.deleteFile(it) }
            transport.optimizeStorage(longArrayOf(id))
            log("chat#$chatsSeen thumbnails=${thumbs.size} bytes=$bytes deleted and optimizeStorage ok")
        }
        log("page done, $chatsSeen chats with videos")
    }

    /** Forwards the first n saveable videos of the first suitable chat into the test account's library channel. */
    private suspend fun forward(transport: DiscoveryTransport, n: Int) {
        val throttle = RequestThrottle(5)
        var library: Long? = null
        for (id in transport.chatIds()) if (transport.chat(id)?.title == "Cablegram library") library = id
        library ?: return log("no library channel")
        for (id in transport.chatIds()) {
            val chat = transport.chat(id) ?: continue
            if (id == library || (chat.type != TransportChatType.Channel && chat.type != TransportChatType.Group)) continue
            val ids = throttle.run { transport.search(id, false, 0, 20) }.videos.filter { it.canBeSaved }.take(n).map { it.messageId }
            if (ids.size < n) continue
            val started = System.currentTimeMillis()
            val results = throttle.run { transport.forward(library, id, ids.toLongArray()) }
            log("forwarded ${results.size}: sent=${results.count { it is ForwardResult.Sent }} failed=${results.count { it is ForwardResult.Failed }} in ${System.currentTimeMillis() - started}ms")
            return
        }
        log("no chat with $n saveable videos")
    }

    private companion object { val VIDEO_EXTENSIONS = listOf("mp4", "mkv", "mov", "avi", "webm", "m4v", "ts", "m2ts", "wmv", "flv", "3gp") }

    private fun log(message: String) { Log.i("DiscoverySpike", message) }
}
