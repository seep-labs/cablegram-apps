package app.cablegram.phone

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

private fun LibrarySort.labelRes(): Int = when (this) {
    LibrarySort.RecentlyAdded -> R.string.ui_recently_added_96ec5d
    LibrarySort.LastWatched -> R.string.library_sort_last_watched
    LibrarySort.TitleAZ -> R.string.library_sort_title
    LibrarySort.Year -> R.string.library_sort_year
    LibrarySort.SizeLargest -> R.string.library_sort_size
}

private fun LibraryLocation.labelRes(): Int = when (this) {
    LibraryLocation.OnPhone -> R.string.library_where_phone
    LibraryLocation.Cloud -> R.string.library_where_cloud
    LibraryLocation.Telegram -> R.string.library_where_telegram
    LibraryLocation.Unavailable -> R.string.library_where_unavailable
}

private fun WatchedFilter.labelRes(): Int = when (this) {
    WatchedFilter.Any -> R.string.library_watched_any
    WatchedFilter.Unwatched -> R.string.library_filter_unwatched
    WatchedFilter.Started -> R.string.library_watched_started
    WatchedFilter.Watched -> R.string.library_watched_done
}

/** Everything the "Clear all" button resets: all filters, but not the sort. */
fun LibraryQuery.cleared(): LibraryQuery = LibraryQuery(sort = sort)

/**
 * Type chips, the More sheet, removable pills for the filters hidden in it, the title count and the sort menu. It sits above
 * the grid and is pinned to the top of the screen once it scrolls out of view.
 */
@Composable
internal fun LibraryFilterBar(
    query: LibraryQuery,
    count: Int,
    collections: List<UserCollection>,
    onChange: (LibraryQuery) -> Unit,
    onOpenMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ui = LocalContext.current.resources
    var sortOpen by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                Triple(LibraryTypeFilter.All, "all", R.string.ui_all_a52ace),
                Triple(LibraryTypeFilter.Movies, "movies", R.string.ui_movies_dff924),
                Triple(LibraryTypeFilter.Shows, "shows", R.string.ui_tv_shows_222906),
            ).forEach { (type, key, label) ->
                FilterChip(
                    selected = query.type == type, onClick = { onChange(query.copy(type = type)) }, label = { Text(ui.getString(label)) },
                    modifier = Modifier.maestro("library_filter_$key"),
                )
            }
            listOf(
                Triple(WatchedFilter.Unwatched, "unwatched", R.string.library_filter_unwatched),
                Triple(WatchedFilter.Started, "in_progress", R.string.library_filter_in_progress),
            ).forEach { (watched, key, label) ->
                FilterChip(
                    selected = query.watched == watched,
                    onClick = { onChange(query.copy(watched = if (query.watched == watched) WatchedFilter.Any else watched)) },
                    label = { Text(ui.getString(label)) },
                    modifier = Modifier.maestro("library_filter_$key"),
                )
            }
            val more = query.moreFilterCount
            FilterChip(
                selected = more > 0, onClick = onOpenMore,
                label = { Text(if (more > 0) ui.getString(R.string.library_filter_more_active, more) else ui.getString(R.string.library_filter_more)) },
                modifier = Modifier.maestro("library_filter_more"),
            )
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                ui.getQuantityString(R.plurals.library_title_count, count, count),
                color = VlcMuted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.maestro("library_count"),
            )
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()).padding(start = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                query.locations.sortedBy { it.ordinal }.forEach { location ->
                    ActiveFilterPill(ui.getString(location.labelRes())) { onChange(query.copy(locations = query.locations - location)) }
                }
                if (query.watched == WatchedFilter.Watched) {
                    ActiveFilterPill(ui.getString(R.string.library_watched_done)) { onChange(query.copy(watched = WatchedFilter.Any)) }
                }
                collections.filter { it.id in query.collectionIds }.forEach { collection ->
                    ActiveFilterPill(collection.name) { onChange(query.copy(collectionIds = query.collectionIds - collection.id)) }
                }
            }
            Column {
                TextButton(onClick = { sortOpen = true }, modifier = Modifier.maestro("library_sort")) {
                    Icon(Icons.Default.SwapVert, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(ui.getString(R.string.library_sort_button, ui.getString(query.sort.labelRes())), style = MaterialTheme.typography.labelMedium)
                }
                DropdownMenu(expanded = sortOpen, onDismissRequest = { sortOpen = false }) {
                    LibrarySort.entries.forEach { sort ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(selected = query.sort == sort, role = Role.RadioButton) { sortOpen = false; onChange(query.copy(sort = sort)) }
                                .heightIn(min = 44.dp)
                                .padding(end = 16.dp)
                                .maestro("library_sort_${sort.name}"),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = query.sort == sort, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
                            Text(ui.getString(sort.labelRes()), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActiveFilterPill(label: String, onRemove: () -> Unit) {
    val ui = LocalContext.current.resources
    InputChip(
        selected = true, onClick = onRemove, label = { Text(label, style = MaterialTheme.typography.labelMedium) },
        trailingIcon = { Icon(Icons.Default.Close, ui.getString(R.string.library_filter_remove, label), Modifier.size(16.dp)) },
    )
}

/** Location, watched state and collection filters, applied as they are tapped; "Show N" just closes the sheet. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
internal fun LibraryFilterSheet(
    query: LibraryQuery,
    count: Int,
    collections: List<UserCollection>,
    onChange: (LibraryQuery) -> Unit,
    onDismiss: () -> Unit,
) {
    val ui = LocalContext.current.resources
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = VlcPanel) {
        Column(Modifier.maestroRoot().padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
        // The sections scroll on a small screen; Clear all and Show stay in view below them.
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(ui.getString(R.string.library_where_heading), color = VlcMuted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 4.dp))
            LibraryLocation.entries.forEach { location ->
                SheetCheckRow(ui.getString(location.labelRes()), location in query.locations, "library_where_${location.name}") { on ->
                    onChange(query.copy(locations = if (on) query.locations + location else query.locations - location))
                }
            }
            Text(ui.getString(R.string.library_progress_heading), color = VlcMuted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                WatchedFilter.entries.forEach { watched ->
                    Row(
                        Modifier.selectable(selected = query.watched == watched, role = Role.RadioButton) { onChange(query.copy(watched = watched)) }
                            .heightIn(min = 48.dp).maestro("library_watched_${watched.name}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = query.watched == watched, onClick = null, modifier = Modifier.padding(horizontal = 8.dp))
                        Text(ui.getString(watched.labelRes()), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 8.dp))
                    }
                }
            }
            if (collections.isNotEmpty()) {
                Text(ui.getString(R.string.library_collection_heading), color = VlcMuted, style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 12.dp))
                collections.forEach { collection ->
                    SheetCheckRow(collection.name, collection.id in query.collectionIds, "library_collection_${collection.id}") { on ->
                        onChange(query.copy(collectionIds = if (on) query.collectionIds + collection.id else query.collectionIds - collection.id))
                    }
                }
            }
        }
            Row(Modifier.fillMaxWidth().padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(onClick = { onChange(query.cleared()) }, modifier = Modifier.weight(1f).heightIn(min = 48.dp).maestro("library_filter_clear")) {
                    Text(ui.getString(R.string.library_filter_clear_all), color = Color.White)
                }
                Button(
                    onClick = onDismiss, colors = ButtonDefaults.buttonColors(containerColor = VlcOrange),
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp).maestro("library_filter_show"),
                ) { Text(ui.getString(R.string.library_filter_show, count), color = VlcBlack) }
            }
        }
    }
}

@Composable
private fun SheetCheckRow(label: String, checked: Boolean, id: String, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().toggleable(value = checked, role = Role.Checkbox, onValueChange = onChange).heightIn(min = 48.dp).maestro(id),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = checked, onCheckedChange = null, modifier = Modifier.padding(end = 12.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}
