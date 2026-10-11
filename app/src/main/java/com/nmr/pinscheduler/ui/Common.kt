package com.nmr.pinscheduler.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.content.Context
import android.text.format.DateFormat
import android.widget.Toast
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.nmr.pinscheduler.api.PinterestApi
import com.nmr.pinscheduler.data.Board
import com.nmr.pinscheduler.data.Settings
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private val PinRed = Color(0xFFE60023)

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme())
        darkColorScheme(primary = Color(0xFFFF5A6E), secondary = Color(0xFFFF8A9A))
    else
        lightColorScheme(primary = PinRed, secondary = Color(0xFFB0001B))
    MaterialTheme(colorScheme = scheme, content = content)
}

fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

private val dateFmt = DateTimeFormatter.ofPattern("EEE d MMM yyyy")
private val dateTimeFmt = DateTimeFormatter.ofPattern("EEE d MMM, h:mm a")
private val timeFmt = DateTimeFormatter.ofPattern("h:mm a")

fun zone(): ZoneId = ZoneId.systemDefault()
fun formatDateTime(millis: Long): String = Instant.ofEpochMilli(millis).atZone(zone()).format(dateTimeFmt)
fun formatDate(d: LocalDate): String = d.format(dateFmt)
fun formatMinutes(m: Int): String = LocalTime.of(m / 60, m % 60).format(timeFmt)
fun toMillis(date: LocalDate, minutes: Int): Long =
    LocalDateTime.of(date, LocalTime.of(minutes / 60, minutes % 60)).atZone(zone()).toInstant().toEpochMilli()

fun pickDate(ctx: Context, initial: LocalDate, onPick: (LocalDate) -> Unit) {
    val dlg = DatePickerDialog(
        ctx, { _, y, m, d -> onPick(LocalDate.of(y, m + 1, d)) },
        initial.year, initial.monthValue - 1, initial.dayOfMonth
    )
    dlg.datePicker.minDate = System.currentTimeMillis() - 1000
    dlg.show()
}

fun pickTime(ctx: Context, initialMinutes: Int, onPick: (Int) -> Unit) {
    TimePickerDialog(
        ctx, { _, h, min -> onPick(h * 60 + min) },
        initialMinutes / 60, initialMinutes % 60, DateFormat.is24HourFormat(ctx)
    ).show()
}

/** Button that shows the chosen board and opens a menu of boards (with refresh / create). */
@Composable
fun BoardPicker(selected: Board?, onSelect: (Board) -> Unit, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val scope = rememberCoroutineScope()
    var boards by remember { mutableStateOf(settings.boards) }
    var open by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }

    fun refresh() {
        loading = true
        scope.launch {
            try {
                boards = PinterestApi(ctx).listBoards()
                if (boards.isEmpty()) toast(ctx, "No boards found. Create one from this menu.")
            } catch (e: Exception) {
                toast(ctx, e.message ?: "Could not load boards")
            } finally {
                loading = false
            }
        }
    }

    Box(modifier) {
        OutlinedButton(
            onClick = {
                open = true
                if (boards.isEmpty() && !loading) refresh()
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                when {
                    selected != null -> "Board: ${selected.name}"
                    loading -> "Loading boards…"
                    else -> "Choose a board"
                },
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f)
            )
            Icon(Icons.Default.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            boards.forEach { b ->
                DropdownMenuItem(text = { Text(b.name) }, onClick = {
                    onSelect(b); settings.lastBoardId = b.id; open = false
                })
            }
            if (boards.isNotEmpty()) HorizontalDivider()
            DropdownMenuItem(
                text = { Text(if (loading) "Loading…" else "Refresh boards") },
                leadingIcon = { Icon(Icons.Default.Refresh, null) },
                onClick = { refresh() }
            )
            DropdownMenuItem(
                text = { Text("Create new board") },
                leadingIcon = { Icon(Icons.Default.Add, null) },
                onClick = { open = false; creating = true }
            )
        }
    }

    if (creating) {
        var name by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { creating = false },
            title = { Text("New board") },
            text = {
                Column {
                    OutlinedTextField(name, { name = it }, label = { Text("Board name") }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    scope.launch {
                        try {
                            val b = PinterestApi(ctx).createBoard(name.trim())
                            boards = Settings(ctx).boards
                            onSelect(b); settings.lastBoardId = b.id
                            creating = false
                        } catch (e: Exception) {
                            toast(ctx, e.message ?: "Could not create board")
                        }
                    }
                }) { Text("Create") }
            },
            dismissButton = { TextButton(onClick = { creating = false }) { Text("Cancel") } }
        )
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 6.dp)
    )
}
