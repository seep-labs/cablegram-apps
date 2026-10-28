package app.cablegram.phone

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
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
import app.cablegram.discovery.DiscoveryGraph
import app.cablegram.discovery.DiscoverySwitches
import kotlinx.coroutines.launch

// Phone only (spec 008, US5). Both switches start off. Turning one on starts nothing; turning one off purges at once.

private enum class Kind { Personal, Saved }

/** Shortest-video choices in seconds; 0 is any length. */
private val MIN_LENGTHS = listOf(300, 600, 900, 1800, 0)

/** **Advanced settings** under Telegram: a collapsible section with the two switches. */
@Composable
internal fun DiscoveryAdvanced(graph: DiscoveryGraph) {
    val ui = LocalContext.current.resources
    val scope = rememberCoroutineScope()
    var open by remember { mutableStateOf(false) }
    var switches by remember { mutableStateOf(graph.store.switches()) }
    var confirming by remember { mutableStateOf<Kind?>(null) }
    var minSeconds by remember { mutableStateOf(graph.store.minVideoSeconds()) }

    // Every change starts from what is stored now: the bots switch lives on another screen and may have changed since this one opened.
    fun apply(change: (DiscoverySwitches) -> DiscoverySwitches) {
        val next = change(graph.store.switches())
        switches = next
        scope.launch { graph.controller.setSwitches(next) }
    }

    OutlinedButton(
        onClick = { open = !open; switches = graph.store.switches() },
        modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).maestro(MaestroIds.DISCOVERY_ADVANCED),
    ) { Text(ui.getString(R.string.discovery_advanced)) }
    if (open) {
        SwitchRow(
            ui.getString(R.string.advanced_personal_title), ui.getString(R.string.advanced_personal_desc), switches.includePersonalChats,
            MaestroIds.ADVANCED_PERSONAL,
        ) { on -> if (on) confirming = Kind.Personal else apply { it.copy(includePersonalChats = false) } }
        SwitchRow(
            ui.getString(R.string.advanced_saved_title), ui.getString(R.string.advanced_saved_desc), switches.includeSavedMessages,
            MaestroIds.ADVANCED_SAVED,
        ) { on -> if (on) confirming = Kind.Saved else apply { it.copy(includeSavedMessages = false) } }
        Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(ui.getString(R.string.advanced_min_title), color = Color.White)
            Text(ui.getString(R.string.advanced_min_desc), color = VlcMuted, fontSize = 12.sp)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (seconds in MIN_LENGTHS) {
                    FilterChip(
                        selected = minSeconds == seconds,
                        onClick = { minSeconds = seconds; graph.controller.setMinVideoSeconds(seconds) },
                        label = { Text(if (seconds == 0) ui.getString(R.string.advanced_min_any) else ui.getString(R.string.advanced_min_option, seconds / 60)) },
                        modifier = Modifier.maestro(MaestroIds.ADVANCED_MIN_PREFIX + seconds),
                    )
                }
            }
        }
    }
    confirming?.let { kind ->
        AlertDialog(
            onDismissRequest = { confirming = null },
            title = { Text(ui.getString(R.string.advanced_confirm_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(ui.getString(R.string.discovery_notice_body))
                    Text(ui.getString(if (kind == Kind.Personal) R.string.advanced_confirm_personal else R.string.advanced_confirm_saved))
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        apply { if (kind == Kind.Personal) it.copy(includePersonalChats = true) else it.copy(includeSavedMessages = true) }
                        confirming = null
                    },
                    modifier = Modifier.maestro(MaestroIds.ADVANCED_CONFIRM),
                ) { Text(ui.getString(R.string.advanced_turn_on)) }
            },
            dismissButton = { TextButton(onClick = { confirming = null }) { Text(ui.getString(R.string.picker_cancel)) } },
            modifier = Modifier.maestroRoot(),
        )
    }
}

@Composable
private fun SwitchRow(title: String, description: String, checked: Boolean, id: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White)
            Text(description, color = VlcMuted, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.maestro(id))
    }
}
