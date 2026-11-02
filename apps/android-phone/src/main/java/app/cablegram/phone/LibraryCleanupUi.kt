package app.cablegram.phone

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/** Yellow: a connection would bring the title back (Telegram, cloud storage). Red: it is gone from this phone. */
val ProblemYellow = Color(0xFFFFC107)
val ProblemRed = Color(0xFFFF5252)

fun problemColor(problem: LibraryProblem): Color = if (problem.needsConnection) ProblemYellow else ProblemRed

/** A round "!" on a poster that cannot play from this phone. */
@Composable
fun ProblemMark(problem: LibraryProblem, modifier: Modifier = Modifier) {
    val ui = LocalContext.current.resources
    Box(modifier.size(24.dp).background(Color(0xBF000000), CircleShape).maestro("problem_mark_${problem.identifier}")) {
        Icon(Icons.Default.Error, contentDescription = libraryProblemLabel(problem, ui), tint = problemColor(problem), modifier = Modifier.fillMaxSize())
    }
}

/** "Clean up your library": the titles that cannot play from this phone, by why. Selecting hands them to the multi-select bar (Remove has Undo). */
@Composable
fun CleanupDialog(viewModel: PhoneViewModel) {
    if (!viewModel.cleanupOpen) return
    val ui = LocalContext.current.resources
    val groups = viewModel.cleanupProblems
    val total = groups.sumOf { it.items.size }
    fun close() { viewModel.cleanupOpen = false }
    fun select(items: List<LibraryItem>) { viewModel.selectAll(items.map { it.id }); close() }
    fun connect(problem: LibraryProblem) {
        close()
        when (problem) {
            LibraryProblem.TelegramNotConnected -> { viewModel.tab = PhoneTab.Browse; viewModel.openTelegramSheet() }
            LibraryProblem.CloudNotConnected -> viewModel.tab = PhoneTab.Storage
            else -> Unit
        }
    }
    AlertDialog(
        onDismissRequest = ::close,
        title = { Text(ui.getString(R.string.cleanup_title)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (groups.isEmpty()) {
                    Text(ui.getString(R.string.cleanup_nothing), color = VlcMuted)
                } else {
                    Text(ui.getString(R.string.cleanup_intro), color = VlcMuted)
                    Button(
                        onClick = { select(groups.flatMap { it.items }) },
                        colors = ButtonDefaults.buttonColors(containerColor = VlcOrange),
                        modifier = Modifier.fillMaxWidth().maestro("cleanup_select_all"),
                    ) { Text(ui.getString(R.string.cleanup_select_all, total), color = VlcBlack) }
                    groups.forEach { group ->
                        Card(colors = CardDefaults.cardColors(containerColor = VlcPanel), modifier = Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Icon(Icons.Default.Error, null, tint = problemColor(group.problem), modifier = Modifier.size(20.dp))
                                    Text(
                                        when (group.problem) {
                                            LibraryProblem.TelegramNotConnected -> ui.getString(R.string.cleanup_group_telegram)
                                            LibraryProblem.CloudNotConnected -> ui.getString(R.string.cleanup_group_cloud)
                                            else -> libraryProblemLabel(group.problem, ui)
                                        },
                                        color = Color.White,
                                        style = MaterialTheme.typography.titleSmall,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(group.items.size.toString(), color = VlcMuted, style = MaterialTheme.typography.titleSmall)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    OutlinedButton(onClick = { select(group.items) }, modifier = Modifier.maestro("cleanup_select_${group.problem.identifier}")) {
                                        Text(ui.getString(R.string.cleanup_select), color = VlcOrange)
                                    }
                                    if (group.problem.needsConnection) {
                                        Button(
                                            onClick = { connect(group.problem) },
                                            colors = ButtonDefaults.buttonColors(containerColor = VlcOrange),
                                            modifier = Modifier.maestro("cleanup_connect_${group.problem.identifier}"),
                                        ) { Text(ui.getString(R.string.cleanup_connect), color = VlcBlack) }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = ::close) { Text(ui.getString(R.string.ui_close_7d9eb7)) } },
        modifier = Modifier.maestroRoot(),
    )
}

/** A TV is waiting for Telegram but this phone has no Telegram session to approve it with: say so, with the way to fix it. */
@Composable
fun TelegramConnectCard(viewModel: PhoneViewModel, modifier: Modifier = Modifier) {
    val ui = LocalContext.current.resources
    val request by PhoneTelegram.connectNeededFor.collectAsState()
    val waiting = request ?: return
    if (!viewModel.telegramNeedsConnection) return
    Card(
        colors = CardDefaults.cardColors(containerColor = VlcPanel),
        border = BorderStroke(2.dp, ProblemYellow),
        shape = RoundedCornerShape(12.dp),
        modifier = modifier.fillMaxWidth().maestro("telegram_connect_needed_card"),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Error, null, tint = ProblemYellow, modifier = Modifier.size(22.dp))
                Text(ui.getString(R.string.telegram_connect_needed_title), color = Color.White, style = MaterialTheme.typography.titleMedium)
            }
            Text(ui.getString(R.string.telegram_connect_needed_body, waiting.tvName), color = VlcMuted, style = MaterialTheme.typography.bodySmall)
            Button(
                onClick = { viewModel.tab = PhoneTab.Browse; viewModel.openTelegramSheet() },
                colors = ButtonDefaults.buttonColors(containerColor = VlcOrange),
                modifier = Modifier.maestro("telegram_connect_needed_button"),
            ) { Text(ui.getString(R.string.ui_connect_telegram_00c799), color = VlcBlack) }
        }
    }
}
