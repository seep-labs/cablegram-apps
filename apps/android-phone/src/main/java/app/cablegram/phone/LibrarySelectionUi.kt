package app.cablegram.phone

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

private val Danger = Color(0xFFFF8A80)

/** What a poster does while titles are being selected: its titles, and whether tapping selects instead of opening. */
internal class CardSelection(val ids: List<String>, val active: Boolean, val selected: Boolean, val toggle: () -> Unit)

/** The titles "Select all" picks: the opened series' episodes, or everything the library grid lists. */
private fun PhoneViewModel.selectableIds(): List<String> =
    shownSeries?.episodes?.map { it.id } ?: libraryEntries.flatMap { it.matchedItems() }.map { it.id }

/**
 * The selection bars, any banners and the sheets. It lives above the library and the series page, so one selection works on
 * both.
 */
@Composable
internal fun LibrarySelectionLayer(viewModel: PhoneViewModel) {
    val selecting = viewModel.selecting
    Box(Modifier.fillMaxSize()) {
        if (selecting) SelectionTopBar(viewModel, Modifier.align(Alignment.TopCenter))
        Column(Modifier.align(Alignment.BottomCenter).padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            BulkBanners(viewModel)
            if (selecting) SelectionActionBar(viewModel)
        }
    }
    when (viewModel.bulkSheet) {
        PhoneViewModel.BulkSheet.AddTo -> AddToSheet(viewModel)
        PhoneViewModel.BulkSheet.FreeUp -> FreeUpSheet(viewModel)
        PhoneViewModel.BulkSheet.Remove -> RemoveSheet(viewModel)
        PhoneViewModel.BulkSheet.None -> {}
    }
}

@Composable
private fun SelectionTopBar(viewModel: PhoneViewModel, modifier: Modifier) {
    val ui = LocalContext.current.resources
    val selected = viewModel.selectedItems
    val all = viewModel.selectableIds()
    Surface(modifier.fillMaxWidth(), color = VlcPanel, shadowElevation = 4.dp) {
        Row(Modifier.padding(horizontal = 4.dp).heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = viewModel::clearSelection, modifier = Modifier.maestro("library_selection_close")) {
                Icon(Icons.Default.Close, ui.getString(R.string.library_exit_selection), tint = Color.White)
            }
            Text(
                ui.getString(R.string.library_selected_count, selected.size), color = Color.White, style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f).maestro("library_selected_count").semantics {
                    contentDescription = ui.getString(R.string.library_selected_announce, selected.size, all.size.coerceAtLeast(selected.size))
                    liveRegion = LiveRegionMode.Polite
                },
            )
            // A single title keeps its own menu (Play, Save to cloud, Edit details…), since long-press now selects.
            selected.singleOrNull()?.let { only ->
                Box {
                    IconButton(onClick = { viewModel.actionMenuId = SELECTION_MENU_ID }) { Icon(Icons.Default.MoreVert, null, tint = Color.White) }
                    ItemActionMenu(viewModel, only, viewModel.actionMenuId == SELECTION_MENU_ID)
                }
            }
            TextButton(onClick = { viewModel.selectAll(all) }, modifier = Modifier.maestro("library_select_all")) {
                Text(ui.getString(R.string.library_select_all), color = VlcOrange)
            }
        }
    }
}

internal const val SELECTION_MENU_ID = "selection-menu"

@Composable
private fun SelectionActionBar(viewModel: PhoneViewModel) {
    val ui = LocalContext.current.resources
    Surface(Modifier.fillMaxWidth(), color = VlcPanel, shape = RoundedCornerShape(16.dp), shadowElevation = 4.dp) {
        Row(Modifier.padding(horizontal = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            ActionBarButton(Icons.Default.Folder, ui.getString(R.string.library_action_add_to), "library_action_add_to") { viewModel.bulkSheet = PhoneViewModel.BulkSheet.AddTo }
            ActionBarButton(Icons.Default.Phone, ui.getString(R.string.library_action_free_up), "library_action_free_up") { viewModel.bulkSheet = PhoneViewModel.BulkSheet.FreeUp }
            ActionBarButton(Icons.Default.Delete, ui.getString(R.string.library_action_remove), "library_action_remove", Danger) { viewModel.bulkSheet = PhoneViewModel.BulkSheet.Remove }
        }
    }
}

@Composable
private fun ActionBarButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, id: String, tint: Color = VlcOrange, onClick: () -> Unit) {
    TextButton(onClick = onClick, modifier = Modifier.heightIn(min = 52.dp).maestro(id)) {
        Icon(icon, null, Modifier.size(18.dp), tint = tint)
        Spacer(Modifier.width(6.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.labelLarge, maxLines = 1)
    }
}

@Composable
private fun BulkBanners(viewModel: PhoneViewModel) {
    val ui = LocalContext.current.resources
    viewModel.bulkProgress?.let { message ->
        Banner(Modifier.maestro("library_bulk_progress")) {
            CircularProgressIndicator(Modifier.size(20.dp), color = VlcOrange, strokeWidth = 2.dp)
            Spacer(Modifier.width(12.dp))
            Text(message, color = Color.White, modifier = Modifier.weight(1f))
        }
    }
    viewModel.undoRemoval?.let { pending ->
        Banner(Modifier.maestro("library_undo_banner")) {
            Text(ui.getQuantityString(R.plurals.library_removed_count, pending.items.size, pending.items.size), color = Color.White, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = viewModel::undoBulkRemoval, modifier = Modifier.maestro("library_undo")) { Text(ui.getString(R.string.library_undo), color = VlcOrange) }
        }
    }
    viewModel.bulkFailure?.let { failure ->
        Banner(Modifier.maestro("library_bulk_failure")) {
            Text(ui.getString(R.string.library_bulk_result, failure.removed, failure.items.size), color = Color.White, modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite })
            TextButton(onClick = viewModel::retryBulkRemoval, modifier = Modifier.maestro("library_retry")) { Text(ui.getString(R.string.library_retry), color = VlcOrange) }
            IconButton(onClick = viewModel::dismissBulkFailure) { Icon(Icons.Default.Close, ui.getString(R.string.ui_cancel_19766e), tint = VlcMuted) }
        }
    }
}

@Composable
private fun Banner(modifier: Modifier, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = Color(0xFF2A2D35), shape = RoundedCornerShape(12.dp), shadowElevation = 4.dp) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp).heightIn(min = 44.dp), verticalAlignment = Alignment.CenterVertically, content = content)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SheetFrame(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = VlcPanel) {
        Column(Modifier.maestroRoot().padding(horizontal = 20.dp).padding(bottom = 20.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun AddToSheet(viewModel: PhoneViewModel) {
    val ui = LocalContext.current.resources
    var newName by remember { mutableStateOf("") }
    // While the grid is filtered by collections, the same titles can be taken out of them again.
    val filtered = viewModel.collections.filter { it.id in viewModel.libraryFilters.collectionIds }
    SheetFrame({ viewModel.bulkSheet = PhoneViewModel.BulkSheet.None }) {
        Text(ui.getString(R.string.library_add_to_title), style = MaterialTheme.typography.titleMedium, color = Color.White)
        viewModel.collections.forEach { collection ->
            TextButton(onClick = { viewModel.bulkAddToCollection(collection) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).maestro("library_add_to_${collection.id}")) {
                Text(collection.name, color = Color.White, modifier = Modifier.weight(1f))
            }
        }
        filtered.forEach { collection ->
            TextButton(onClick = { viewModel.bulkRemoveFromCollection(collection) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).maestro("library_remove_from_${collection.id}")) {
                Text(ui.getString(R.string.library_remove_from_collection, collection.name), color = Danger, modifier = Modifier.weight(1f))
            }
        }
        OutlinedTextField(
            value = newName, onValueChange = { newName = it }, singleLine = true, modifier = Modifier.fillMaxWidth().maestro("library_add_to_new_name"),
            label = { Text(ui.getString(R.string.ui_collection_name_922214)) }, shape = RoundedCornerShape(16.dp),
        )
        Button(
            onClick = { viewModel.bulkAddToNewCollection(newName) }, enabled = newName.isNotBlank(),
            colors = ButtonDefaults.buttonColors(containerColor = VlcOrange), modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).maestro("library_add_to_new"),
        ) { Text(ui.getString(R.string.ui_create_collection_69152b), color = VlcBlack) }
    }
}

@Composable
private fun FreeUpSheet(viewModel: PhoneViewModel) {
    val ui = LocalContext.current.resources
    val summary = summarizeFreeUp(viewModel.selectedItems)
    val total = summary.eligible.size + summary.skipped.size
    SheetFrame({ viewModel.bulkSheet = PhoneViewModel.BulkSheet.None }) {
        Text(ui.getString(R.string.library_free_up_title), style = MaterialTheme.typography.titleMedium, color = Color.White)
        if (summary.eligible.isEmpty()) {
            Text(ui.getString(R.string.library_free_up_none), color = VlcMuted)
        } else {
            Text(ui.getString(R.string.library_free_up_summary, summary.eligible.size, total, formatBytes(summary.bytes)), color = VlcMuted)
        }
        if (summary.skipped.isNotEmpty() && summary.eligible.isNotEmpty()) {
            Text(ui.getString(R.string.library_free_up_skipped, summary.skipped.size), color = VlcMuted)
        }
        summary.skipped.take(6).forEach { Text("• ${it.title}", color = VlcMuted, style = MaterialTheme.typography.bodySmall) }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { viewModel.bulkSheet = PhoneViewModel.BulkSheet.None }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(ui.getString(R.string.ui_cancel_19766e), color = Color.White) }
            Button(
                onClick = viewModel::confirmBulkFreeUp, enabled = summary.eligible.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = VlcOrange), modifier = Modifier.weight(1f).heightIn(min = 48.dp).maestro("library_free_up_confirm"),
            ) { Text(ui.getString(R.string.library_action_free_up), color = VlcBlack) }
        }
    }
}

@Composable
private fun RemoveSheet(viewModel: PhoneViewModel) {
    val ui = LocalContext.current.resources
    val summary = summarizeRemoval(viewModel.selectedItems)
    var mode by remember { mutableStateOf(RemovalMode.RemoveFromLibrary) }
    SheetFrame({ viewModel.bulkSheet = PhoneViewModel.BulkSheet.None }) {
        Text(ui.getQuantityString(R.plurals.library_remove_title_count, summary.total, summary.total), style = MaterialTheme.typography.titleMedium, color = Color.White)
        if (summary.phoneItems > 0) Text(ui.getQuantityString(R.plurals.library_summary_phone, summary.phoneItems, summary.phoneItems), color = VlcMuted)
        if (summary.telegramItems > 0) Text(ui.getQuantityString(R.plurals.library_summary_telegram, summary.telegramItems, summary.telegramItems), color = VlcMuted)
        RemovalChoice(mode == RemovalMode.RemoveFromLibrary, ui.getString(R.string.ui_remove_from_library_8e7161), { mode = RemovalMode.RemoveFromLibrary }, "library_remove_mode_keep") {
            Text(ui.getString(R.string.library_remove_keep_desc), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
        }
        RemovalChoice(mode == RemovalMode.DeleteFiles, ui.getString(R.string.library_remove_delete_label), { mode = RemovalMode.DeleteFiles }, "library_remove_mode_delete") {
            if (summary.phoneItems > 0) Text(ui.getString(R.string.library_remove_delete_phone, summary.phoneItems, formatBytes(summary.phoneBytes)), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
            if (summary.telegramItems > 0) Text(ui.getString(R.string.library_remove_delete_telegram, summary.telegramItems), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
            Text(ui.getString(R.string.library_remove_irreversible), color = Danger, style = MaterialTheme.typography.bodySmall)
        }
        Row(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { viewModel.bulkSheet = PhoneViewModel.BulkSheet.None }, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text(ui.getString(R.string.ui_cancel_19766e), color = Color.White) }
            Button(
                onClick = { viewModel.confirmBulkRemove(mode) },
                colors = ButtonDefaults.buttonColors(containerColor = if (mode == RemovalMode.DeleteFiles) Danger else VlcOrange),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp).maestro("library_remove_confirm"),
            ) { Text(ui.getString(R.string.ui_remove_c3812f), color = VlcBlack) }
        }
    }
}

@Composable
private fun RemovalChoice(selected: Boolean, label: String, onSelect: () -> Unit, id: String, details: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onSelect).padding(vertical = 8.dp).maestro(id),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(end = 12.dp, top = 2.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, color = Color.White, style = MaterialTheme.typography.bodyLarge)
            details()
        }
    }
}
