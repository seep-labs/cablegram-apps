package app.cablegram.phone

import android.content.res.Resources
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.cablegram.discovery.ChatKind
import app.cablegram.discovery.DiscoveryChat
import app.cablegram.discovery.DiscoveryChatId
import app.cablegram.discovery.DiscoveryGraph
import app.cablegram.discovery.SCAN_PARALLEL_CHATS
import app.cablegram.discovery.isUnchangedSinceLastLook
import app.cablegram.discovery.StoredScanRow
import app.cablegram.telegram.TelegramState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout

// Phone only (spec 008). The screens never touch Telegram directly: every read goes through DiscoveryGraph.

/** Everything the Find videos screen shows; the scan itself runs here, never in a composable. */
internal class FindVideosModel(private val graph: DiscoveryGraph, private val scope: CoroutineScope) {
    enum class Phase { Asking, Scanning, Done, Failed, Managing }

    var phase by mutableStateOf(Phase.Asking); private set
    var checked by mutableStateOf(0); private set
    var total by mutableStateOf(0); private set
    var pausedSeconds by mutableStateOf<Int?>(null); private set
    /** Chats the last scan did not read again because nothing new was posted in them. */
    var unchanged by mutableStateOf(0); private set
    var showEmpty by mutableStateOf(false)
    /** Part of a chat name the owner typed; the list shows only chats whose name contains it. */
    var query by mutableStateOf("")
    fun matches(title: String) = query.isBlank() || title.contains(query.trim(), ignoreCase = true)
    val rows = mutableStateListOf<StoredScanRow>().also { it += graph.store.snapshot().scan }
    /** Every box starts unticked; only a saved choice is ticked when the list is reopened (FR-008). */
    val ticked = mutableStateListOf<Long>().also { it += graph.store.choice() }
    val hasChoice get() = graph.store.choice().isNotEmpty()
    private var job: Job? = null

    /** **Manage chats** reads only what is already stored on the phone, so it asks for nothing. */
    fun manage() { phase = Phase.Managing }

    fun toggle(chatId: Long) { if (!ticked.remove(chatId)) ticked += chatId }

    /** **Use N chats**: saves the choice and purges whatever was unticked. */
    fun useChats(onSaved: () -> Unit) {
        val chosen = rows.filter { it.chatId in ticked }.map { DiscoveryChat(DiscoveryChatId(it.chatId), it.kind, it.title, it.protectedContent) }
        scope.launch {
            graph.controller.useChats(chosen)
            onSaved()
        }
    }

    /** Only the prompt's **Look for videos** calls this, and it asks the controller for a fresh permit each time (FR-003). */
    fun look() {
        val permit = graph.controller.grantScan()
        phase = Phase.Scanning
        pausedSeconds = null
        unchanged = 0
        job = scope.launch {
            val pauses = launch { graph.throttle.pauses.collect { pausedSeconds = it } }
            try {
                PhoneTelegram.existing()?.let { s -> withTimeout(30_000) { s.state.first { it is TelegramState.Ready } } }
                val resume = graph.store.snapshot().let { !it.scanCompleted && it.scanned.isNotEmpty() }
                if (!resume) { graph.store.restartScan() }
                val done = graph.store.snapshot().scanned.toSet()
                val chats = mutableListOf<DiscoveryChat>()
                graph.discovery.discoveryChats(permit, graph.store.switches()).collect { chats += it; app.cablegram.phone.diagnostics.SentryScrubber.rememberName(it.title) }
                total = chats.size
                checked = chats.count { it.id.raw in done }
                val atOnce = Semaphore(SCAN_PARALLEL_CHATS)
                val before = graph.store.snapshot().scan.associateBy { it.chatId }
                coroutineScope {
                    chats.filter { it.id.raw !in done }.map { chat ->
                        launch {
                            // Quiet chats keep the counts of the last look: no request, so dead channels cost nothing.
                            val known = before[chat.id.raw]
                            if (isUnchangedSinceLastLook(known, chat)) {
                                graph.store.addScanRow(known!!.copy(title = chat.title, protectedContent = chat.protectedContent))
                                unchanged++
                                checked++
                                return@launch
                            }
                            atOnce.withPermit {
                                val count = graph.discovery.chatVideoCount(permit, chat.id)
                                val row = StoredScanRow(chat.id.raw, chat.kind, chat.title, chat.protectedContent, count.videos, count.files, System.currentTimeMillis(), chat.lastMessageId)
                                graph.store.addScanRow(row)
                                rows.removeAll { it.chatId == row.chatId }
                                rows += row
                                checked++
                                pausedSeconds = null
                            }
                        }
                    }.joinAll()
                }
                // Chats no longer listed leave the results (a finished scan refreshes in place).
                val listed = chats.map { it.id.raw }.toSet()
                rows.removeAll { it.chatId !in listed }
                graph.store.finishScan(true)
                phase = Phase.Done
            } catch (e: CancellationException) {
                phase = Phase.Done
                throw e
            } catch (e: Exception) {
                phase = Phase.Failed
            } finally {
                pauses.cancel()
                graph.controller.endScan()
            }
        }
    }

    fun stop() { job?.cancel() }
    fun dispose() { job?.cancel(); graph.controller.endScan() }
    fun decline() { phase = Phase.Done }
}

@Composable
internal fun FindVideosDialog(graph: DiscoveryGraph, viewModel: PhoneViewModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val model = remember(graph) { FindVideosModel(graph, scope) }
    DisposableEffect(model) { onDispose { model.dispose() } }
    var picking by remember { mutableStateOf(false) }
    if (picking) {
        PickerDialog(graph, viewModel, onClose = onClose)
        return
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().maestroRoot(), color = MaterialTheme.colorScheme.background) {
            FindVideosScreen(model, onClose, onPick = { picking = true })
        }
    }
}

@Composable
private fun FindVideosScreen(model: FindVideosModel, onClose: () -> Unit, onPick: () -> Unit) {
    val ui = LocalContext.current.resources
    Column(Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(ui.getString(R.string.discovery_title), color = Color.White, fontSize = 22.sp)
        if (model.phase == FindVideosModel.Phase.Asking || model.phase == FindVideosModel.Phase.Failed) {
            OnThisPhonePrompt(
                failed = model.phase == FindVideosModel.Phase.Failed,
                onLook = model::look,
                onNotNow = { model.decline(); onClose() },
            )
            if (model.phase == FindVideosModel.Phase.Asking && model.hasChoice) {
                OutlinedButton(onClick = onPick, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).maestro(MaestroIds.DISCOVERY_PICK)) {
                    Text(ui.getString(R.string.discovery_pick))
                }
                OutlinedButton(onClick = model::manage, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).maestro(MaestroIds.DISCOVERY_MANAGE)) {
                    Text(ui.getString(R.string.discovery_manage))
                }
            }
        }
        if (model.phase == FindVideosModel.Phase.Scanning || model.phase == FindVideosModel.Phase.Done) {
            ScanProgress(model, onClose)
        }
        if (model.phase == FindVideosModel.Phase.Scanning || model.phase == FindVideosModel.Phase.Done || model.phase == FindVideosModel.Phase.Managing) {
            OutlinedTextField(
                value = model.query,
                onValueChange = { model.query = it },
                singleLine = true,
                placeholder = { Text(ui.getString(R.string.discovery_search_hint)) },
                modifier = Modifier.fillMaxWidth().maestro(MaestroIds.DISCOVERY_SEARCH),
            )
        }
        // Until the owner says "Look for videos", nothing from an earlier scan is shown either.
        if (model.phase == FindVideosModel.Phase.Scanning || model.phase == FindVideosModel.Phase.Done || model.phase == FindVideosModel.Phase.Managing) {
            Box(Modifier.weight(1f)) { ScanResults(model, ui) }
            if (model.phase != FindVideosModel.Phase.Scanning) {
                Button(
                    onClick = { model.useChats { if (model.ticked.isNotEmpty()) onPick() else onClose() } },
                    enabled = model.ticked.isNotEmpty() || model.hasChoice,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 56.dp).heightIn(min = 52.dp).maestro(MaestroIds.DISCOVERY_USE),
                ) { Text(ui.getQuantityString(R.plurals.discovery_use_chats, model.ticked.size, model.ticked.size)) }
            }
        }
    }
}

/** The permission prompt (FR-003, US4): shown before every scan, with no "don't ask again". */
@Composable
internal fun OnThisPhonePrompt(failed: Boolean, onLook: () -> Unit, onNotNow: () -> Unit) {
    val ui = LocalContext.current.resources
    Column(Modifier.maestro(MaestroIds.DISCOVERY_PROMPT), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(ui.getString(R.string.discovery_notice_title), color = Color.White, fontSize = 18.sp)
        Text(ui.getString(R.string.discovery_notice_body), color = VlcMuted)
        Text(ui.getString(R.string.discovery_ask_title), color = Color.White, fontSize = 16.sp)
        Text(ui.getString(R.string.discovery_ask_body), color = VlcMuted)
        if (failed) Text(ui.getString(R.string.discovery_failed), color = VlcOrange)
        Button(onClick = onLook, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).maestro(MaestroIds.DISCOVERY_LOOK)) {
            Text(ui.getString(R.string.discovery_look))
        }
        OutlinedButton(onClick = onNotNow, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).maestro(MaestroIds.DISCOVERY_NOT_NOW)) {
            Text(ui.getString(R.string.discovery_not_now))
        }
    }
}

@Composable
private fun ScanProgress(model: FindVideosModel, onClose: () -> Unit) {
    val ui = LocalContext.current.resources
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                if (model.total == 0 && model.phase == FindVideosModel.Phase.Scanning) ui.getString(R.string.discovery_listing)
                else ui.getString(R.string.discovery_checked, model.checked, model.total),
                color = VlcMuted,
                modifier = Modifier.maestro(MaestroIds.DISCOVERY_PROGRESS),
            )
            model.pausedSeconds?.let { Text(ui.getString(R.string.discovery_paused, it), color = VlcOrange, fontSize = 12.sp) }
            if (model.unchanged > 0) Text(ui.getQuantityString(R.plurals.discovery_unchanged, model.unchanged, model.unchanged), color = VlcMuted, fontSize = 12.sp)
        }
        if (model.phase == FindVideosModel.Phase.Scanning) {
            TextButton(onClick = model::stop, modifier = Modifier.maestro(MaestroIds.DISCOVERY_STOP)) { Text(ui.getString(R.string.discovery_stop)) }
        } else {
            TextButton(onClick = onClose) { Text(ui.getString(android.R.string.ok)) }
        }
    }
}

@Composable
private fun ScanResults(model: FindVideosModel, ui: Resources) {
    val matching = model.rows.filter { model.matches(it.title) }
    val withVideos = matching.filter { it.videos + it.files > 0 }.sortedByDescending { it.videos + it.files }
    val empty = matching.filter { it.videos + it.files == 0 }
    val searching = model.query.isNotBlank()
    LazyColumn(Modifier.fillMaxWidth().maestro(MaestroIds.DISCOVERY_RESULTS), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (searching && matching.isEmpty()) item { Text(ui.getString(R.string.discovery_no_match), color = VlcMuted) }
        items(withVideos, key = { it.chatId }) { ScanRow(it, it.chatId in model.ticked, { model.toggle(it.chatId) }, ui) }
        // While searching, every match is listed, with or without videos.
        if (searching) items(empty, key = { it.chatId }) { ScanRow(it, it.chatId in model.ticked, { model.toggle(it.chatId) }, ui) }
        else if (empty.isNotEmpty()) {
            item {
                TextButton(onClick = { model.showEmpty = !model.showEmpty }) {
                    Text(if (model.showEmpty) ui.getString(R.string.discovery_hide_empty) else ui.getString(R.string.discovery_show_empty, empty.size))
                }
            }
            if (model.showEmpty) items(empty, key = { it.chatId }) { ScanRow(it, it.chatId in model.ticked, { model.toggle(it.chatId) }, ui) }
        }
    }
}

@Composable
private fun ScanRow(row: StoredScanRow, ticked: Boolean, onToggle: () -> Unit, ui: Resources) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
    Checkbox(checked = ticked, onCheckedChange = { onToggle() }, modifier = Modifier.maestro(MaestroIds.DISCOVERY_CHAT_CHECK))
    Column(Modifier.weight(1f)) {
        Text(row.title, color = Color.White, maxLines = 1)
        val kind = ui.getString(
            when (row.kind) {
                ChatKind.Channel -> R.string.discovery_kind_channel
                ChatKind.Group -> R.string.discovery_kind_group
                ChatKind.Personal -> R.string.discovery_kind_personal
                ChatKind.SavedMessages -> R.string.discovery_kind_saved
            },
        )
        val count = when {
            row.files > 0 && row.videos == 0 -> ui.getQuantityString(R.plurals.discovery_files_maybe, row.files, row.files)
            row.files > 0 -> ui.getQuantityString(R.plurals.discovery_videos_about, row.videos + row.files, row.videos + row.files)
            else -> ui.getQuantityString(R.plurals.discovery_videos, row.videos, row.videos)
        }
        Text("$kind · $count" + if (row.protectedContent) " · " + ui.getString(R.string.discovery_protected) else "", color = VlcMuted, fontSize = 12.sp)
    }
    }
}
