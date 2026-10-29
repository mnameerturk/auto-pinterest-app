@file:OptIn(ExperimentalMaterial3Api::class)

package com.nmr.pinscheduler.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.nmr.pinscheduler.api.PinterestApi
import com.nmr.pinscheduler.data.Board
import com.nmr.pinscheduler.data.ImportPin
import com.nmr.pinscheduler.data.PinImport
import com.nmr.pinscheduler.data.PinStatus
import com.nmr.pinscheduler.data.PinStore
import com.nmr.pinscheduler.data.ScheduledPin
import com.nmr.pinscheduler.data.Settings
import com.nmr.pinscheduler.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Imports the day files made for the Chrome extension (.zip with dayNN.json + images), keeping their exact times. */
@Composable
fun ImportScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val scope = rememberCoroutineScope()
    val queue by PinStore.pins.collectAsState()

    var pins by remember { mutableStateOf<List<ImportPin>>(emptyList()) }
    var fileName by remember { mutableStateOf("") }
    var reading by remember { mutableStateOf(false) }
    var boards by remember { mutableStateOf(settings.boards) }
    var boardsBusy by remember { mutableStateOf(false) }
    var selectedDays by remember { mutableStateOf<Set<Int>>(emptySet()) }
    var saving by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf("") }

    fun loadBoards() {
        boardsBusy = true
        scope.launch {
            try { boards = PinterestApi(ctx).listBoards() }
            catch (e: Exception) { toast(ctx, e.message ?: "Could not load boards") }
            finally { boardsBusy = false }
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        reading = true
        scope.launch {
            try {
                val read = withContext(Dispatchers.IO) { PinImport.read(ctx, uri) }
                pins = read
                fileName = uri.lastPathSegment?.substringAfterLast('/') ?: "file"
                val now = System.currentTimeMillis()
                // Pre-select every day that still has pins in the future.
                selectedDays = read.filter { it.timeMillis > now }.map { it.day }.toSet()
                if (boards.isEmpty() && settings.isConnected) loadBoards()
            } catch (e: Exception) {
                toast(ctx, e.message ?: "Could not read that file")
            } finally {
                reading = false
            }
        }
    }

    val now = System.currentTimeMillis() + 60_000
    val existing = queue.associateBy { it.id }
    fun alreadyDone(p: ImportPin) = existing[p.queueId]?.status.let { it == PinStatus.POSTED || it == PinStatus.POSTING }
    val toSchedule = pins.filter {
        it.day in selectedDays && it.timeMillis > now && it.image != null && !alreadyDone(it)
    }
    val boardNames = pins.map { it.boardName }.distinct().sorted()
    val missingBoards = boardNames.filter { PinImport.findBoard(boards, it) == null }
    val blockedByBoard = toSchedule.count { PinImport.findBoard(boards, it.boardName) == null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import pins file") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                "Pick the pins .zip (the one with day04 … day30 folders, or a single day's zip). " +
                    "Every pin keeps its own title, description, link, alt text, board and exact time from the file.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            OutlinedButton(
                enabled = !reading && !saving,
                onClick = { picker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/json", "application/octet-stream")) },
                modifier = Modifier.fillMaxWidth().height(52.dp)
            ) {
                if (reading) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp); Spacer(Modifier.width(10.dp)); Text("Reading…") }
                else Text(if (pins.isEmpty()) "Choose pins file" else "Choose a different file")
            }

            if (pins.isNotEmpty()) {
                val noImage = pins.count { it.image == null }
                Text(
                    "$fileName: ${pins.size} pins in ${pins.map { it.day }.distinct().size} days" +
                        if (noImage > 0) " · $noImage without an image (skipped)" else "",
                    Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall
                )

                // ---------- boards ----------
                SectionLabel("1. Boards")
                if (!settings.isConnected) {
                    Text("Connect to Pinterest in Settings first.", color = MaterialTheme.colorScheme.error)
                } else {
                    boardNames.forEach { n ->
                        val ok = PinImport.findBoard(boards, n) != null
                        Text(
                            (if (ok) "✓ " else "✗ ") + n,
                            color = if (ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Row(Modifier.padding(top = 6.dp)) {
                        TextButton(enabled = !boardsBusy, onClick = { loadBoards() }) {
                            Text(if (boardsBusy) "Loading…" else "Refresh boards")
                        }
                        if (missingBoards.isNotEmpty()) TextButton(enabled = !boardsBusy, onClick = {
                            boardsBusy = true
                            scope.launch {
                                try {
                                    val api = PinterestApi(ctx)
                                    missingBoards.forEach { api.createBoard(it) }
                                    boards = api.listBoards()
                                    toast(ctx, "Created ${missingBoards.size} boards")
                                } catch (e: Exception) {
                                    toast(ctx, e.message ?: "Could not create boards")
                                } finally { boardsBusy = false }
                            }
                        }) { Text("Create ${missingBoards.size} missing") }
                    }
                }

                // ---------- days ----------
                SectionLabel("2. Days to schedule")
                Text(
                    "Times that have already passed are skipped. Untick days you already scheduled another way.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row {
                    TextButton(onClick = { selectedDays = pins.filter { it.timeMillis > now }.map { it.day }.toSet() }) { Text("All upcoming") }
                    TextButton(onClick = { selectedDays = emptySet() }) { Text("None") }
                }
                pins.groupBy { it.day }.toSortedMap().forEach { (day, list) ->
                    val future = list.count { it.timeMillis > now }
                    val done = list.count { alreadyDone(it) }
                    Card(
                        Modifier.fillMaxWidth().padding(bottom = 6.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = if (future == 0) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .5f)
                            else MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(Modifier.padding(horizontal = 4.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = day in selectedDays, enabled = future > 0,
                                onCheckedChange = { selectedDays = if (it) selectedDays + day else selectedDays - day }
                            )
                            Column(Modifier.weight(1f)) {
                                Text("Day $day", fontWeight = FontWeight.SemiBold)
                                Text(
                                    "${formatDateTime(list.first().timeMillis)} → ${formatDateTime(list.last().timeMillis)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    when {
                                        future == 0 -> "All ${list.size} times have passed"
                                        future < list.size -> "$future of ${list.size} still upcoming"
                                        else -> "${list.size} pins"
                                    } + if (done > 0) " · $done already posted" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }

                // ---------- go ----------
                Spacer(Modifier.height(16.dp))
                if (blockedByBoard > 0) Text(
                    "$blockedByBoard pins use a board that isn't on your account yet. Create the missing boards above.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall
                )
                if (progress.isNotBlank()) Text(progress, style = MaterialTheme.typography.bodySmall)
                Button(
                    enabled = !saving && toSchedule.isNotEmpty() && blockedByBoard == 0 && settings.isConnected,
                    modifier = Modifier.fillMaxWidth().height(52.dp).padding(top = 4.dp),
                    onClick = {
                        saving = true
                        val list = toSchedule
                        val bs: List<Board> = boards
                        scope.launch {
                            try {
                                var n = 0
                                for (p in list) {
                                    val b = PinImport.findBoard(bs, p.boardName)!!
                                    val path = withContext(Dispatchers.IO) { PinStore.importImage(ctx, Uri.fromFile(p.image!!)) }
                                    // Replace an earlier import of the same pin (and its image) if it hasn't posted.
                                    existing[p.queueId]?.let { old -> Scheduler.cancel(ctx, old.id); PinStore.delete(ctx, old.id) }
                                    val pin = ScheduledPin(
                                        id = p.queueId, imagePath = path, title = p.title, description = p.description,
                                        link = p.link, altText = p.altText, boardId = b.id, boardName = b.name,
                                        timeMillis = p.timeMillis, status = PinStatus.PENDING,
                                    )
                                    PinStore.upsert(ctx, pin)
                                    Scheduler.schedule(ctx, pin)
                                    n++
                                    progress = "Scheduled $n of ${list.size}…"
                                }
                                toast(ctx, "Scheduled $n pins, last one ${formatDateTime(list.last().timeMillis)}")
                                onDone()
                            } catch (e: Exception) {
                                toast(ctx, e.message ?: "Could not schedule")
                            } finally {
                                saving = false
                            }
                        }
                    }
                ) {
                    if (saving) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    else Text("Schedule ${toSchedule.size} pins")
                }
                Text(
                    "Times show in your phone's time zone. The phone posts each pin itself at its time, " +
                        "so keep it on with internet (a few minutes' delay is normal when it's asleep).",
                    Modifier.padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}
