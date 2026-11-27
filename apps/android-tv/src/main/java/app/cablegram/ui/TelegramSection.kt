package app.cablegram.ui

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Button
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.OutlinedButton
import androidx.tv.material3.Text
import app.cablegram.discovery.*
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Per-account Keystore-encrypted discovery storage on the TV (spec 004 FR-004, 008 FR-008). */
class TvDiscoveryStorage(context: Context, private val accountId: String) : DiscoveryStorage {
    private val prefs = context.getSharedPreferences("cablegram_tv_discovery_$accountId", Context.MODE_PRIVATE)

    override fun read(): String? {
        val encoded = prefs.getString("doc", null) ?: return null
        return runCatching {
            val (iv, data) = encoded.split(":", limit = 2)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            String(cipher.doFinal(Base64.decode(data, Base64.NO_WRAP)), Charsets.UTF_8)
        }.getOrNull()
    }

    override fun write(json: String) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP)
        val data = Base64.encodeToString(cipher.doFinal(json.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        prefs.edit().putString("doc", "$iv:$data").apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
        runCatching { KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(alias()) }
    }

    private fun alias() = "cablegram_tv_disc_$accountId"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias(), null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(alias(), KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    private companion object {
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}

private enum class SectionScreen { Prompt, Scanning, Results, Home, Chat, Advanced }

private data class TvScanRow(val chat: DiscoveryChat, val count: VideoCount)

@Composable
fun TelegramSectionScreen(
    graph: DiscoveryGraph,
    hasLibraryChannel: Boolean,
    isFirstTime: Boolean,
    inLibraryUniqueIds: Set<String>,
    onFirstConsentAgreed: suspend () -> Unit,
    onPlayVideo: (DiscoveryVideo) -> Unit,
    onAddedToLibrary: suspend () -> Unit,
    onBackToMenu: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(graph.store.snapshot()) }
    var screen by remember {
        mutableStateOf(if (snapshot.choice.isNotEmpty()) SectionScreen.Home else SectionScreen.Prompt)
    }
    var showZero by remember { mutableStateOf(false) }
    var activeChat by remember { mutableStateOf<StoredChoice?>(null) }
    var videos by remember { mutableStateOf<List<DiscoveryVideo>>(emptyList()) }
    var statusMessage by remember { mutableStateOf<String?>(null) }
    var scanProgress by remember { mutableStateOf(0 to 0) }
    var scanResults by remember { mutableStateOf<List<TvScanRow>>(emptyList()) }
    var ticked by remember { mutableStateOf(snapshot.choice.map { it.chatId }.toSet()) }

    fun refreshSnapshot() {
        snapshot = graph.store.snapshot()
        ticked = snapshot.choice.map { it.chatId }.toSet()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0E1016))
            .padding(32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        when (screen) {
            SectionScreen.Prompt -> {
                Text("On this TV", style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.SemiBold)
                Text(
                    "Cablegram will look in your channels and groups on this TV for long videos. Nothing from other chats leaves this TV.",
                    color = Color(0xFFD0D4DC),
                )
                if (isFirstTime) {
                    Text(
                        "From now on, opening this account on this TV needs your OK in Telegram.",
                        color = Color(0xFF9BA3B4),
                    )
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Button(onClick = {
                        scope.launch {
                            onFirstConsentAgreed()
                            val permit = graph.controller.grantScan()
                            screen = SectionScreen.Scanning
                            val chats = graph.discovery.discoveryChats(permit, graph.store.switches()).toList()
                            scanProgress = 0 to chats.size
                            val rows = mutableListOf<TvScanRow>()
                            for ((idx, chat) in chats.withIndex()) {
                                val count = runCatching { graph.discovery.chatVideoCount(permit, chat.id) }.getOrNull() ?: continue
                                rows += TvScanRow(chat, count)
                                scanResults = rows.sortedByDescending { it.count.total }
                                scanProgress = (idx + 1) to chats.size
                            }
                            graph.controller.endScan()
                            refreshSnapshot()
                            screen = SectionScreen.Results
                        }
                    }) { Text("Look for videos") }
                    OutlinedButton(onClick = {
                        if (snapshot.choice.isNotEmpty()) screen = SectionScreen.Home else onBackToMenu()
                    }) { Text("Not now") }
                }
            }

            SectionScreen.Scanning -> {
                Text("Looking for videos on this TV...", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Text("${scanProgress.first} / ${scanProgress.second} chats", color = Color(0xFFD0D4DC))
                Button(onClick = {
                    graph.controller.endScan()
                    refreshSnapshot()
                    screen = SectionScreen.Results
                }) { Text("Stop") }
            }

            SectionScreen.Results -> {
                val allChats = scanResults.map { it.chat }
                val withVideos = scanResults.filter { it.count.total > 0 }
                val zeroVideos = scanResults.filter { it.count.total == 0 }
                val visible = if (showZero) scanResults else withVideos
                Text("Manage chats", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visible, key = { it.chat.id.raw }) { row ->
                        val checked = ticked.contains(row.chat.id.raw)
                        OutlinedButton(onClick = {
                            ticked = if (checked) ticked - row.chat.id.raw else ticked + row.chat.id.raw
                        }) {
                            val label = if (row.count.approximate) "about ${row.count.total} videos" else "${row.count.videos} videos"
                            Text("${if (checked) "[x]" else "[ ]"} ${row.chat.title} — $label")
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    if (!showZero && zeroVideos.isNotEmpty()) {
                        OutlinedButton(onClick = { showZero = true }) {
                            Text("Show ${zeroVideos.size} chats without videos")
                        }
                    }
                    Button(onClick = {
                        scope.launch {
                            graph.controller.useChats(allChats.filter { ticked.contains(it.id.raw) })
                            refreshSnapshot()
                            screen = if (snapshot.choice.isNotEmpty()) SectionScreen.Home else SectionScreen.Prompt
                        }
                    }) { Text("Done") }
                }
            }

            SectionScreen.Advanced -> {
                Text("Advanced settings", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (sec in listOf(300, 600, 900, 1800, 0)) {
                        val label = if (sec == 0) "Any" else "${sec / 60}m"
                        val selected = snapshot.minVideoSeconds == sec
                        if (selected) {
                            Button(onClick = { graph.controller.setMinVideoSeconds(sec); refreshSnapshot() }) { Text(label) }
                        } else {
                            OutlinedButton(onClick = { graph.controller.setMinVideoSeconds(sec); refreshSnapshot() }) { Text(label) }
                        }
                    }
                }
                val switches = graph.store.switches()
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            graph.controller.setSwitches(switches.copy(includePersonalChats = !switches.includePersonalChats))
                            refreshSnapshot()
                        }
                    }) { Text("Personal chats: ${if (switches.includePersonalChats) "On" else "Off"}") }

                    OutlinedButton(onClick = {
                        scope.launch {
                            graph.controller.setSwitches(switches.copy(includeSavedMessages = !switches.includeSavedMessages))
                            refreshSnapshot()
                        }
                    }) { Text("Saved Messages: ${if (switches.includeSavedMessages) "On" else "Off"}") }
                }
                Button(onClick = { screen = SectionScreen.Home }) { Text("Back") }
            }

            SectionScreen.Chat -> {
                val chat = activeChat
                Text(chat?.title ?: "Chat", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                if (statusMessage != null) {
                    Text(statusMessage!!, color = Color(0xFF9BE7FF))
                }
                LazyColumn(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(videos, key = { "${it.chatId.raw}:${it.messageId}" }) { v ->
                        val inLib = inLibraryUniqueIds.contains(v.uniqueId)
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Button(onClick = { onPlayVideo(v) }) {
                                Text("${v.title}${if (inLib) " · In library" else ""}")
                            }
                            if (!v.protectedContent) {
                                OutlinedButton(onClick = {
                                    if (!hasLibraryChannel) {
                                        statusMessage = "Connect Telegram in the Cablegram phone app to add videos to your library."
                                    } else {
                                        scope.launch {
                                            graph.discovery.forwardToLibrary(listOf(v))
                                            onAddedToLibrary()
                                            statusMessage = "Added \"${v.title}\" to library."
                                        }
                                    }
                                }) { Text("Add to library") }
                            }
                        }
                    }
                }
                Button(onClick = {
                    activeChat = null
                    statusMessage = null
                    screen = SectionScreen.Home
                }) { Text("Back to My Telegram chats") }
            }

            SectionScreen.Home -> {
                Text("My Telegram chats", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                LazyRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(snapshot.choice, key = { it.chatId }) { chat ->
                        Button(onClick = {
                            activeChat = chat
                            statusMessage = null
                            scope.launch {
                                videos = graph.discovery.videoPager(setOf(DiscoveryChatId(chat.chatId))).next(30)
                                screen = SectionScreen.Chat
                            }
                        }) { Text(chat.title) }
                    }
                    item {
                        OutlinedButton(onClick = { screen = SectionScreen.Prompt }) { Text("Look again") }
                    }
                    item {
                        OutlinedButton(onClick = { screen = SectionScreen.Results }) { Text("Manage chats") }
                    }
                    item {
                        OutlinedButton(onClick = { screen = SectionScreen.Advanced }) { Text("Advanced settings") }
                    }
                }
            }
        }
    }
}
