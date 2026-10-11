@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.nmr.pinscheduler.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nmr.pinscheduler.data.Board
import com.nmr.pinscheduler.data.PinStatus
import com.nmr.pinscheduler.data.PinStore
import com.nmr.pinscheduler.data.ScheduledPin
import com.nmr.pinscheduler.data.Settings
import com.nmr.pinscheduler.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

/** Spreads images over days and time slots, skipping times that have passed. */
fun planTimes(count: Int, start: LocalDate, slots: List<Int>, everyDays: Int): List<Long> {
    if (slots.isEmpty() || count == 0) return emptyList()
    val now = System.currentTimeMillis() + 60_000
    val out = mutableListOf<Long>()
    var day = start
    var guard = 0
    while (out.size < count && guard < 5000) {
        for (m in slots.sorted()) {
            val t = toMillis(day, m)
            if (t > now) out += t
            if (out.size == count) break
        }
        day = day.plusDays(everyDays.toLong())
        guard++
    }
    return out
}

@Composable
fun BulkScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val scope = rememberCoroutineScope()

    var uris by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var startDate by remember { mutableStateOf(LocalDate.now()) }
    var slots by remember { mutableStateOf(settings.timeSlots) }
    var everyDays by remember { mutableIntStateOf(1) }
    var board by remember { mutableStateOf<Board?>(settings.boards.firstOrNull { it.id == settings.lastBoardId }) }
    var title by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var editingSlot by remember { mutableStateOf<Int?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(50)) { picked ->
        if (picked.isNotEmpty()) uris = (uris + picked).distinct()
    }

    val times = planTimes(uris.size, startDate, slots, everyDays)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Bulk schedule") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Text(
                "Pick several images, choose the posting times for each day, and the app spreads them out, " +
                    "one per time slot, starting from the date you choose.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SectionLabel("1. Images (${uris.size})")
            OutlinedButton(
                onClick = { picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                modifier = Modifier.fillMaxWidth()
            ) { Icon(Icons.Default.Add, null); Spacer(Modifier.width(6.dp)); Text("Add images") }

            SectionLabel("2. Posting times each day")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                slots.forEach { m ->
                    InputChip(
                        selected = false,
                        onClick = { editingSlot = m },
                        label = { Text(formatMinutes(m)) },
                        trailingIcon = { Icon(Icons.Default.Close, "Remove", Modifier.size(18.dp)) }
                    )
                }
                AssistChip(
                    onClick = { pickTime(ctx, 12 * 60) { new -> slots = (slots + new).distinct().sorted() } },
                    label = { Text("Add time") },
                    leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(18.dp)) }
                )
            }
            Text(
                "Tap a time to change or remove it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            SectionLabel("3. Start date and frequency")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickDate(ctx, startDate) { startDate = it } }, modifier = Modifier.weight(1f)) {
                    Text("From ${formatDate(startDate)}")
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Post every", Modifier.padding(end = 8.dp))
                OutlinedButton(onClick = { if (everyDays > 1) everyDays-- }) { Text("−") }
                Text(
                    if (everyDays == 1) "day" else "$everyDays days",
                    Modifier.padding(horizontal = 12.dp), fontWeight = FontWeight.SemiBold
                )
                OutlinedButton(onClick = { if (everyDays < 30) everyDays++ }) { Text("+") }
            }

            SectionLabel("4. Board and details (shared by all)")
            BoardPicker(board, { board = it })
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                title, { title = it.take(100) }, label = { Text("Title (optional)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                description, { description = it.take(500) }, label = { Text("Description (optional)") },
                modifier = Modifier.fillMaxWidth(), minLines = 2
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                link, { link = it.trim() }, label = { Text("Destination link (optional)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )

            if (uris.isNotEmpty()) {
                SectionLabel("Preview")
                uris.forEachIndexed { i, uri ->
                    Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            AsyncImage(
                                uri, null, contentScale = ContentScale.Crop,
                                modifier = Modifier.size(52.dp).clip(RoundedCornerShape(6.dp))
                            )
                            Spacer(Modifier.width(12.dp))
                            Text(
                                times.getOrNull(i)?.let { formatDateTime(it) } ?: "Add a time slot",
                                Modifier.weight(1f)
                            )
                            IconButton(onClick = { uris = uris.filterIndexed { j, _ -> j != i } }) {
                                Icon(Icons.Default.Close, "Remove image")
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Button(
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = {
                    val b = board
                    when {
                        uris.isEmpty() -> toast(ctx, "Add some images first")
                        slots.isEmpty() -> toast(ctx, "Add at least one posting time")
                        b == null -> toast(ctx, "Choose a board")
                        link.isNotBlank() && !link.startsWith("http") -> toast(ctx, "Link must start with http:// or https://")
                        else -> {
                            saving = true
                            settings.timeSlots = slots
                            scope.launch {
                                try {
                                    val pins = withContext(Dispatchers.IO) {
                                        uris.mapIndexed { i, uri ->
                                            ScheduledPin(
                                                id = UUID.randomUUID().toString(),
                                                imagePath = PinStore.importImage(ctx, uri),
                                                title = title.trim(),
                                                description = description.trim(),
                                                link = link,
                                                altText = "",
                                                boardId = b.id,
                                                boardName = b.name,
                                                timeMillis = times[i],
                                                status = PinStatus.PENDING,
                                            )
                                        }
                                    }
                                    pins.forEach { PinStore.upsert(ctx, it); Scheduler.schedule(ctx, it) }
                                    toast(ctx, "Scheduled ${pins.size} pins, last one ${formatDateTime(pins.last().timeMillis)}")
                                    onDone()
                                } catch (e: Exception) {
                                    toast(ctx, e.message ?: "Could not schedule")
                                } finally {
                                    saving = false
                                }
                            }
                        }
                    }
                }
            ) {
                if (saving) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                else Text("Schedule ${uris.size} pins")
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    editingSlot?.let { m ->
        AlertDialog(
            onDismissRequest = { editingSlot = null },
            title = { Text(formatMinutes(m)) },
            text = { Text("Change this posting time or remove it?") },
            confirmButton = {
                TextButton(onClick = {
                    editingSlot = null
                    pickTime(ctx, m) { new -> slots = (slots - m + new).distinct().sorted() }
                }) { Text("Change") }
            },
            dismissButton = {
                TextButton(onClick = { slots = slots - m; editingSlot = null }) { Text("Remove") }
            }
        )
    }
}
