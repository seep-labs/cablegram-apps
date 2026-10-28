package app.cablegram.phone

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import app.cablegram.discovery.ChatKind
import app.cablegram.discovery.DiscoveryChatId
import app.cablegram.discovery.DiscoveryGraph
import app.cablegram.discovery.DiscoveryVideo
import app.cablegram.discovery.ForwardOutcome
import app.cablegram.discovery.TelegramDiscovery
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Phone only (spec 008, US3). Reads messages only from the chats the owner chose, downloads thumbnails only,
// and never plays a video. Adding forwards into the library channel through TelegramDiscovery.

/** What the picker needs from the app, so the model stays free of the view model. */
internal class PickerHooks(
    val tombstones: suspend () -> Map<String, String>?,
    val restore: suspend (String) -> Boolean,
    val afterAdd: () -> Unit,
)

private fun key(v: DiscoveryVideo) = v.chatId.raw to v.messageId
private fun libraryKey(v: DiscoveryVideo) = "tgfile:${v.uniqueId}"

internal class PickerModel(private val graph: DiscoveryGraph, private val hooks: PickerHooks, private val scope: CoroutineScope) {
    sealed interface Adding {
        data object Idle : Adding
        data class Working(val done: Int, val total: Int) : Adding
        data class Finished(val added: Int, val failed: List<DiscoveryVideo>) : Adding
    }

    var chats by mutableStateOf(graph.store.snapshot().choice); private set
    /** Chats dropped from the choice because the owner left them or lost access (one-line note). */
    var dropped by mutableStateOf(0); private set
    var filter by mutableStateOf<Long?>(null); private set
    val videos = mutableStateListOf<DiscoveryVideo>()
    val thumbs = mutableStateMapOf<Pair<Long, Long>, String>()
    val selected = mutableStateListOf<Pair<Long, Long>>()
    val addedKeys = mutableStateListOf<String>()
    var loading by mutableStateOf(false); private set
    var exhausted by mutableStateOf(false); private set
    var tombstones by mutableStateOf<Map<String, String>>(emptyMap()); private set
    var adding by mutableStateOf<Adding>(Adding.Idle); private set
    var confirmReadd by mutableStateOf<List<DiscoveryVideo>?>(null); private set

    private var pager: TelegramDiscovery.VideoPager? = null
    private var loadJob: Job? = null

    init {
        scope.launch { hooks.tombstones()?.let { tombstones = it } }
        scope.launch {
            // A chat the owner left, or lost access to, leaves the choice before anything is read from it.
            dropped = runCatching { graph.controller.dropUnreadable { graph.discovery.isReadable(it) } }.getOrDefault(0)
            chats = graph.store.snapshot().choice
            show(null)
        }
    }

    fun show(chatId: Long?) {
        loadJob?.cancel()
        filter = chatId
        videos.clear(); selected.clear(); exhausted = false
        val ids = (if (chatId == null) chats.map { it.chatId } else listOf(chatId)).map { DiscoveryChatId(it) }.toSet()
        pager = if (ids.isEmpty()) null else graph.discovery.videoPager(ids)
        more()
    }

    /** The next 50 videos, newest first (FR-009). */
    fun more() {
        val p = pager ?: run { exhausted = true; return }
        if (loading) return
        loading = true
        loadJob = scope.launch {
            try {
                val page = p.next(PAGE)
                videos += page
                page.forEach { app.cablegram.phone.diagnostics.SentryScrubber.rememberName(it.title) }
                exhausted = p.exhausted
                for (v in page) launch { graph.discovery.thumbnail(v)?.let { thumbs[key(v)] = it } }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                exhausted = true
            } finally {
                loading = false
            }
        }
    }

    fun stateOf(v: DiscoveryVideo, inLibrary: Set<String>): CellState = when {
        libraryKey(v) in addedKeys || libraryKey(v) in inLibrary -> CellState.InLibrary
        tombstones[libraryKey(v)] == "deleting" -> CellState.Deleting
        v.protectedContent -> CellState.Protected
        else -> CellState.Open
    }

    fun toggle(v: DiscoveryVideo, inLibrary: Set<String>) {
        if (stateOf(v, inLibrary) == CellState.Deleting || stateOf(v, inLibrary) == CellState.Protected || stateOf(v, inLibrary) == CellState.InLibrary) return
        if (!selected.remove(key(v))) selected += key(v)
    }

    /** **Add N to library**. Titles hidden or deleted earlier need the owner's say-so first. */
    fun add() {
        val picked = videos.filter { key(it) in selected }
        val again = picked.filter { tombstones.containsKey(libraryKey(it)) }
        if (again.isNotEmpty() && confirmReadd == null) { confirmReadd = again; return }
        send(picked)
    }

    fun confirmAgain() {
        val again = confirmReadd.orEmpty()
        confirmReadd = null
        scope.launch {
            val restored = again.filter { hooks.restore(libraryKey(it)) }
            if (restored.isNotEmpty()) tombstones = tombstones - restored.map { libraryKey(it) }.toSet()
            send(videos.filter { key(it) in selected && !(tombstones.containsKey(libraryKey(it))) })
        }
    }

    fun cancelAgain() { confirmReadd = null }

    fun retry() {
        val failed = (adding as? Adding.Finished)?.failed.orEmpty().filter { !it.protectedContent }
        if (failed.isNotEmpty()) send(failed)
    }

    private fun send(picked: List<DiscoveryVideo>) {
        if (picked.isEmpty()) return
        adding = Adding.Working(0, picked.size)
        scope.launch {
            val outcomes = try { graph.discovery.forwardToLibrary(picked) } catch (e: Exception) { picked.map { ForwardOutcome.Failed(it, e.javaClass.simpleName) } }
            val ok = outcomes.filterIsInstance<ForwardOutcome.Added>().map { it.video }
            val failed = outcomes.filterIsInstance<ForwardOutcome.Failed>().map { it.video }
            addedKeys += ok.map { libraryKey(it) }
            selected.removeAll(ok.map { key(it) }.toSet())
            adding = Adding.Finished(ok.size, failed)
            if (ok.isNotEmpty()) hooks.afterAdd()
        }
    }

    fun dismissResult() { adding = Adding.Idle }

    fun dispose() { loadJob?.cancel() }

    enum class CellState { Open, InLibrary, Deleting, Protected }

    private companion object { const val PAGE = 50 }
}

@Composable
internal fun PickerDialog(graph: DiscoveryGraph, viewModel: PhoneViewModel, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val hooks = remember(viewModel) {
        PickerHooks(viewModel::telegramTombstoneStates, viewModel::restoreTelegramTitleByKey, viewModel::afterDiscoveryAdd)
    }
    val model = remember(graph) { PickerModel(graph, hooks, scope) }
    DisposableEffect(model) { onDispose { model.dispose() } }
    val inLibrary = remember(viewModel.items) { viewModel.items.mapNotNull { it.telegramFileKey }.toSet() }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().maestroRoot(), color = MaterialTheme.colorScheme.background) {
            PickerScreen(model, inLibrary, onClose)
        }
    }
}

@Composable
private fun PickerScreen(model: PickerModel, inLibrary: Set<String>, onClose: () -> Unit) {
    val ui = LocalContext.current.resources
    Column(Modifier.fillMaxSize().navigationBarsPadding().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(ui.getString(R.string.picker_title), color = Color.White, fontSize = 22.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onClose, modifier = Modifier.maestro(MaestroIds.PICKER_DONE)) { Text(ui.getString(R.string.picker_done)) }
        }
        if (model.dropped > 0) Text(ui.getQuantityString(R.plurals.picker_dropped, model.dropped, model.dropped), color = VlcOrange, fontSize = 13.sp)
        PrivacyBanner(personal = model.chats.any { it.kind == ChatKind.Personal || it.kind == ChatKind.SavedMessages }, ui)
        if (model.chats.size > 1) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { FilterChip(selected = model.filter == null, onClick = { model.show(null) }, label = { Text(ui.getString(R.string.picker_all)) }) }
                items(model.chats, key = { it.chatId }) { c ->
                    FilterChip(selected = model.filter == c.chatId, onClick = { model.show(c.chatId) }, label = { Text(c.title, maxLines = 1) })
                }
            }
        }
        Box(Modifier.weight(1f)) {
            if (model.videos.isEmpty() && !model.loading) {
                Text(ui.getString(R.string.picker_empty), color = VlcMuted)
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize().maestro(MaestroIds.PICKER_GRID),
            ) {
                items(model.videos, key = { it.chatId.raw to it.messageId }) { v -> Cell(model, v, inLibrary, ui) }
                if (!model.exhausted) {
                    item(span = { GridItemSpan(2) }) {
                        OutlinedButton(
                            onClick = model::more, enabled = !model.loading,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).maestro(MaestroIds.PICKER_MORE),
                        ) { Text(ui.getString(R.string.picker_more)) }
                    }
                }
            }
        }
        PickerBottom(model, ui)
    }
    model.confirmReadd?.let { again ->
        AlertDialog(
            onDismissRequest = model::cancelAgain,
            title = { Text(ui.getString(R.string.picker_readd_title)) },
            text = { Text(ui.getString(R.string.picker_readd_body, again.size)) },
            confirmButton = { TextButton(onClick = model::confirmAgain, modifier = Modifier.maestro(MaestroIds.PICKER_READD_CONFIRM)) { Text(ui.getString(R.string.picker_readd_confirm)) } },
            dismissButton = { TextButton(onClick = model::cancelAgain) { Text(ui.getString(R.string.picker_cancel)) } },
            modifier = Modifier.maestroRoot(),
        )
    }
}

@Composable
private fun PickerBottom(model: PickerModel, ui: android.content.res.Resources) {
    val adding = model.adding
    when (adding) {
        is PickerModel.Adding.Working -> Text(ui.getString(R.string.picker_adding, adding.done, adding.total), color = VlcMuted, modifier = Modifier.maestro(MaestroIds.PICKER_STATUS))
        is PickerModel.Adding.Finished -> Column(Modifier.maestro(MaestroIds.PICKER_STATUS), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (adding.added > 0) Text(ui.getQuantityString(R.plurals.picker_added, adding.added, adding.added), color = Color.White)
            if (adding.failed.isNotEmpty()) {
                Text(ui.getString(R.string.picker_failed, adding.failed.size), color = VlcOrange)
                OutlinedButton(onClick = model::retry, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).maestro(MaestroIds.PICKER_TRY_AGAIN)) {
                    Text(ui.getString(R.string.picker_try_again))
                }
            }
        }
        PickerModel.Adding.Idle -> Unit
    }
    val n = model.selected.size
    Button(
        onClick = model::add,
        enabled = n > 0 && adding !is PickerModel.Adding.Working,
        modifier = Modifier.fillMaxWidth().padding(bottom = 56.dp).heightIn(min = 52.dp).maestro(MaestroIds.PICKER_ADD),
    ) { Text(ui.getQuantityString(R.plurals.picker_add, n, n)) }
}

@Composable
private fun Cell(model: PickerModel, v: DiscoveryVideo, inLibrary: Set<String>, ui: android.content.res.Resources) {
    val state = model.stateOf(v, inLibrary)
    val ticked = key(v) in model.selected
    val path = model.thumbs[key(v)]
    val bitmap by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path) {
        value = path?.let { withContext(Dispatchers.IO) { decodePosterBitmap(it)?.asImageBitmap() } }
    }
    Column(
        Modifier.fillMaxWidth().clickable(enabled = state == PickerModel.CellState.Open) { model.toggle(v, inLibrary) }.maestro(MaestroIds.PICKER_ITEM),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(10.dp)).background(Color(0xFF1C1E24))) {
            bitmap?.let { Image(it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
            if (state == PickerModel.CellState.Open) {
                Checkbox(checked = ticked, onCheckedChange = { model.toggle(v, inLibrary) }, modifier = Modifier.align(Alignment.TopStart))
            }
            val badge = when (state) {
                PickerModel.CellState.InLibrary -> ui.getString(R.string.picker_in_library)
                PickerModel.CellState.Deleting -> ui.getString(R.string.picker_deleting)
                PickerModel.CellState.Protected -> ui.getString(R.string.discovery_protected)
                PickerModel.CellState.Open -> null
            }
            badge?.let {
                Text(
                    it, color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomStart).padding(6.dp).background(Color(0xCC000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp)
                        .then(if (state == PickerModel.CellState.InLibrary) Modifier.maestro(MaestroIds.PICKER_IN_LIBRARY) else Modifier),
                )
            }
            if (v.durationSeconds > 0) {
                Text(
                    duration(v.durationSeconds), color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).background(Color(0xCC000000), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
                )
            }
        }
        Text(v.title.ifBlank { "—" }, color = if (state == PickerModel.CellState.Open) Color.White else VlcMuted, maxLines = 2, fontSize = 13.sp)
    }
}

private fun duration(seconds: Int): String {
    val h = seconds / 3600
    val m = seconds % 3600 / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

/** US4: shown every time the picker opens, above the grid, with no way to dismiss it (FR-011). */
@Composable
private fun PrivacyBanner(personal: Boolean, ui: android.content.res.Resources) {
    var how by remember { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().background(Color(0xFF1C1E24), RoundedCornerShape(12.dp)).padding(12.dp).maestro(MaestroIds.PICKER_BANNER),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(ui.getString(if (personal) R.string.picker_banner_personal else R.string.picker_banner), color = Color.White, fontSize = 13.sp)
        TextButton(onClick = { how = true }, modifier = Modifier.maestro(MaestroIds.PICKER_HOW)) { Text(ui.getString(R.string.picker_how)) }
    }
    if (how) {
        val uri = androidx.compose.ui.platform.LocalUriHandler.current
        AlertDialog(
            onDismissRequest = { how = false },
            title = { Text(ui.getString(R.string.how_title)) },
            text = { Text(ui.getString(R.string.how_body), modifier = Modifier.maestro(MaestroIds.PICKER_HOW_PAGE)) },
            confirmButton = { TextButton(onClick = { uri.openUri(SURFACE_TEST_URL) }) { Text(ui.getString(R.string.how_link)) } },
            dismissButton = { TextButton(onClick = { how = false }) { Text(ui.getString(R.string.picker_done)) } },
            modifier = Modifier.maestroRoot(),
        )
    }
}

private const val SURFACE_TEST_URL =
    "https://github.com/seep-labs/cablegram-apps/blob/main/apps/android-phone/src/test/java/app/cablegram/telegram/TelegramApiSurfaceTest.kt"
