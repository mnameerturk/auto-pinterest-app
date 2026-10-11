@file:OptIn(ExperimentalMaterial3Api::class)

package com.nmr.pinscheduler.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nmr.pinscheduler.data.PinStatus
import com.nmr.pinscheduler.data.PinStore
import com.nmr.pinscheduler.data.ScheduledPin
import com.nmr.pinscheduler.data.Settings
import com.nmr.pinscheduler.work.Scheduler
import java.io.File

@Composable
fun QueueScreen(
    onNew: () -> Unit,
    onBulk: () -> Unit,
    onEdit: (String) -> Unit,
    onSettings: () -> Unit,
) {
    val ctx = LocalContext.current
    val pins by PinStore.pins.collectAsState()
    val connected = remember { Settings(ctx).isConnected }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    var confirmDelete by remember { mutableStateOf<ScheduledPin?>(null) }

    val upcoming = pins.filter { it.status == PinStatus.PENDING || it.status == PinStatus.POSTING }
    val posted = pins.filter { it.status == PinStatus.POSTED }.sortedByDescending { it.timeMillis }
    val failed = pins.filter { it.status == PinStatus.FAILED }
    val shown = when (tab) { 0 -> upcoming; 1 -> posted; else -> failed }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Pin Scheduler") },
                actions = {
                    if (tab == 1 && posted.isNotEmpty()) {
                        TextButton(onClick = { PinStore.clearPosted(ctx) }) { Text("Clear") }
                    }
                    IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") }
                }
            )
        },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExtendedFloatingActionButton(
                    onClick = onBulk,
                    icon = { Icon(Icons.Default.DateRange, null) },
                    text = { Text("Bulk schedule") },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
                ExtendedFloatingActionButton(
                    onClick = onNew,
                    icon = { Icon(Icons.Default.Add, null) },
                    text = { Text("New pin") }
                )
            }
        }
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!connected) {
                Card(
                    onClick = onSettings,
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    modifier = Modifier.fillMaxWidth().padding(12.dp)
                ) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Not connected to Pinterest", fontWeight = FontWeight.Bold)
                        Text("Tap here to open Settings and connect your account.")
                    }
                }
            }
            TabRow(selectedTabIndex = tab) {
                Tab(tab == 0, { tab = 0 }, text = { Text("Upcoming (${upcoming.size})") })
                Tab(tab == 1, { tab = 1 }, text = { Text("Posted (${posted.size})") })
                Tab(tab == 2, { tab = 2 }, text = { Text("Failed (${failed.size})") })
            }
            if (shown.isEmpty()) {
                Column(
                    Modifier.fillMaxSize().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        when (tab) {
                            0 -> "Nothing scheduled yet.\nTap New pin or Bulk schedule to start."
                            1 -> "No posted pins yet."
                            else -> "No failed pins."
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 160.dp)) {
                    items(shown, key = { it.id }) { pin ->
                        PinRow(
                            pin,
                            onEdit = { onEdit(pin.id) },
                            onDelete = { confirmDelete = pin },
                            onPostNow = {
                                val p = pin.copy(status = PinStatus.PENDING, error = null)
                                PinStore.upsert(ctx, p)
                                Scheduler.schedule(ctx, p.copy(timeMillis = System.currentTimeMillis()))
                                toast(ctx, "Posting now…")
                            },
                            onRetry = {
                                val p = pin.copy(status = PinStatus.PENDING, error = null)
                                PinStore.upsert(ctx, p)
                                Scheduler.schedule(ctx, p)
                                toast(ctx, if (p.timeMillis <= System.currentTimeMillis()) "Retrying now…" else "Rescheduled")
                            }
                        )
                        Spacer(Modifier.height(10.dp))
                    }
                }
            }
        }
    }

    confirmDelete?.let { pin ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete this pin?") },
            text = {
                Text(
                    if (pin.status == PinStatus.POSTED) "This only removes it from the app. It stays on Pinterest."
                    else "It will not be posted."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    Scheduler.cancel(ctx, pin.id)
                    PinStore.delete(ctx, pin.id)
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } }
        )
    }
}

@Composable
private fun PinRow(
    pin: ScheduledPin,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onPostNow: () -> Unit,
    onRetry: () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = File(pin.imagePath),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(width = 64.dp, height = 88.dp).clip(RoundedCornerShape(8.dp))
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    pin.title.ifBlank { "(no title)" }, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                Text(
                    formatDateTime(pin.timeMillis), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    pin.boardName, style = MaterialTheme.typography.bodySmall,
                    maxLines = 1, overflow = TextOverflow.Ellipsis
                )
                if (pin.status == PinStatus.POSTING) {
                    Text("Posting…", style = MaterialTheme.typography.bodySmall)
                }
                pin.error?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                        maxLines = 3, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            Column {
                when (pin.status) {
                    PinStatus.PENDING -> {
                        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit") }
                        IconButton(onClick = onPostNow) { Icon(Icons.Default.Send, "Post now") }
                    }
                    PinStatus.FAILED -> {
                        IconButton(onClick = onEdit) { Icon(Icons.Default.Edit, "Edit") }
                        IconButton(onClick = onRetry) { Icon(Icons.Default.Refresh, "Retry") }
                    }
                    else -> {}
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") }
            }
        }
    }
}
