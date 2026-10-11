package com.nmr.pinscheduler.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import java.io.File
import java.util.UUID

/** Keeps the schedule queue in a JSON file inside the app's private storage. */
object PinStore {
    private val lock = Any()
    private val _pins = MutableStateFlow<List<ScheduledPin>>(emptyList())
    val pins: StateFlow<List<ScheduledPin>> = _pins
    private var loaded = false

    private fun file(ctx: Context) = File(ctx.filesDir, "pins.json")

    fun load(ctx: Context) = synchronized(lock) {
        if (loaded) return@synchronized
        val f = file(ctx)
        val list = if (f.exists()) {
            runCatching {
                val arr = JSONArray(f.readText())
                (0 until arr.length()).map { ScheduledPin.fromJson(arr.getJSONObject(it)) }
            }.getOrDefault(emptyList())
        } else emptyList()
        _pins.value = list.sortedBy { it.timeMillis }
        loaded = true
    }

    private fun save(ctx: Context, list: List<ScheduledPin>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        val tmp = File(ctx.filesDir, "pins.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file(ctx))
        _pins.value = list.sortedBy { it.timeMillis }
    }

    fun get(ctx: Context, id: String): ScheduledPin? {
        load(ctx)
        return _pins.value.firstOrNull { it.id == id }
    }

    fun upsert(ctx: Context, pin: ScheduledPin) = synchronized(lock) {
        load(ctx)
        val list = _pins.value.filterNot { it.id == pin.id } + pin
        save(ctx, list)
    }

    fun update(ctx: Context, id: String, change: (ScheduledPin) -> ScheduledPin) = synchronized(lock) {
        load(ctx)
        val list = _pins.value.map { if (it.id == id) change(it) else it }
        save(ctx, list)
    }

    fun delete(ctx: Context, id: String) = synchronized(lock) {
        load(ctx)
        val pin = _pins.value.firstOrNull { it.id == id }
        val list = _pins.value.filterNot { it.id == id }
        save(ctx, list)
        // Remove the image only if no other pin uses it.
        if (pin != null && list.none { it.imagePath == pin.imagePath }) {
            File(pin.imagePath).delete()
        }
    }

    fun clearPosted(ctx: Context) = synchronized(lock) {
        load(ctx)
        val (posted, rest) = _pins.value.partition { it.status == PinStatus.POSTED }
        save(ctx, rest)
        posted.forEach { p -> if (rest.none { it.imagePath == p.imagePath }) File(p.imagePath).delete() }
    }

    /** Copies a picked image into private storage (downscaled JPEG) so it survives until posting. */
    fun importImage(ctx: Context, uri: Uri): String {
        val dir = File(ctx.filesDir, "images").apply { mkdirs() }
        val out = File(dir, UUID.randomUUID().toString() + ".jpg")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        val maxSide = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxSide / (sample * 2) >= 2000) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: throw IllegalStateException("Could not read that image")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        bmp.recycle()
        return out.absolutePath
    }
}
