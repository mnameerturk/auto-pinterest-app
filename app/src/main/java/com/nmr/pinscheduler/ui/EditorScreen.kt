@file:OptIn(ExperimentalMaterial3Api::class)

package com.nmr.pinscheduler.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.nmr.pinscheduler.data.Board
import com.nmr.pinscheduler.data.PinStatus
import com.nmr.pinscheduler.data.PinStore
import com.nmr.pinscheduler.data.ScheduledPin
import com.nmr.pinscheduler.data.Settings
import com.nmr.pinscheduler.work.Scheduler
import java.io.File
import java.time.Instant
import java.util.UUID

@Composable
fun EditorScreen(pinId: String?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val existing = remember(pinId) { pinId?.let { PinStore.get(ctx, it) } }

    var imagePath by remember { mutableStateOf(existing?.imagePath) }
    var pickedUri by remember { mutableStateOf<Uri?>(null) }
    var title by remember { mutableStateOf(existing?.title ?: "") }
    var description by remember { mutableStateOf(existing?.description ?: "") }
    var link by remember { mutableStateOf(existing?.link ?: "") }
    var altText by remember { mutableStateOf(existing?.altText ?: "") }
    var board by remember {
        mutableStateOf(
            existing?.let { Board(it.boardId, it.boardName) }
                ?: settings.boards.firstOrNull { it.id == settings.lastBoardId }
        )
    }
    val initial = remember {
        existing?.let { Instant.ofEpochMilli(it.timeMillis).atZone(zone()).toLocalDateTime() }
            ?: java.time.LocalDateTime.now().plusHours(1).withMinute(0)
    }
    var date by remember { mutableStateOf(initial.toLocalDate()) }
    var minutes by remember { mutableIntStateOf(initial.hour * 60 + initial.minute) }
    var saving by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) pickedUri = uri
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (existing == null) "New pin" else "Edit pin") },
                navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { pad ->
        Column(
            Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp)
        ) {
            Box(
                Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(12.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .clickable {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                contentAlignment = Alignment.Center
            ) {
                val model: Any? = pickedUri ?: imagePath?.let { File(it) }
                if (model != null) {
                    AsyncImage(model, null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
                } else {
                    Text("Tap to choose an image", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            SectionLabel("Pin details")
            OutlinedTextField(
                title, { title = it.take(100) }, label = { Text("Title (${title.length}/100)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                description, { description = it.take(500) },
                label = { Text("Description (${description.length}/500)") },
                modifier = Modifier.fillMaxWidth(), minLines = 3
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                link, { link = it.trim() }, label = { Text("Destination link (optional)") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri)
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                altText, { altText = it.take(500) }, label = { Text("Alt text (optional)") },
                modifier = Modifier.fillMaxWidth()
            )

            SectionLabel("Board")
            BoardPicker(board, { board = it })

            SectionLabel("When to post")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickDate(ctx, date) { date = it } }, modifier = Modifier.weight(1f)) {
                    Text(formatDate(date))
                }
                OutlinedButton(onClick = { pickTime(ctx, minutes) { minutes = it } }, modifier = Modifier.weight(1f)) {
                    Text(formatMinutes(minutes))
                }
            }
            val whenMillis = toMillis(date, minutes)
            if (whenMillis <= System.currentTimeMillis()) {
                Text(
                    "This time has passed, so the pin will post right away.",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            Spacer(Modifier.height(24.dp))
            Button(
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                onClick = {
                    val b = board
                    when {
                        pickedUri == null && imagePath == null -> toast(ctx, "Choose an image first")
                        b == null -> toast(ctx, "Choose a board")
                        link.isNotBlank() && !link.startsWith("http") -> toast(ctx, "Link must start with http:// or https://")
                        else -> {
                            saving = true
                            try {
                                val path = pickedUri?.let { PinStore.importImage(ctx, it) } ?: imagePath!!
                                val pin = ScheduledPin(
                                    id = existing?.id ?: UUID.randomUUID().toString(),
                                    imagePath = path,
                                    title = title.trim(),
                                    description = description.trim(),
                                    link = link,
                                    altText = altText.trim(),
                                    boardId = b.id,
                                    boardName = b.name,
                                    timeMillis = whenMillis,
                                    status = PinStatus.PENDING,
                                )
                                // Replaced image: remove the old copy.
                                if (existing != null && pickedUri != null && existing.imagePath != path) {
                                    File(existing.imagePath).delete()
                                }
                                PinStore.upsert(ctx, pin)
                                Scheduler.schedule(ctx, pin)
                                toast(ctx, "Scheduled for ${formatDateTime(pin.timeMillis)}")
                                onDone()
                            } catch (e: Exception) {
                                toast(ctx, e.message ?: "Could not save")
                            } finally {
                                saving = false
                            }
                        }
                    }
                }
            ) { Text(if (existing == null) "Schedule pin" else "Save changes") }
        }
    }
}

