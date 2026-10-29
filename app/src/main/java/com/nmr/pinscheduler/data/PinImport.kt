package com.nmr.pinscheduler.data

import android.content.Context
import android.net.Uri
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.util.zip.ZipInputStream
import kotlin.random.Random

/** One pin read from a day file (dayNN.json), with its image already unpacked to a temp file. */
data class ImportPin(
    val key: String,          // the pin's id in the file, e.g. d04p1
    val day: Int,
    val title: String,
    val description: String,
    val link: String,
    val altText: String,
    val boardName: String,
    val timeMillis: Long,
    val image: File?,
    val imageName: String,
) {
    /** Id used in the app's queue, so importing the same file twice replaces instead of duplicating. */
    val queueId get() = "import-$key"
}

/**
 * Reads the pins zip made for the Chrome extension (folders day04 … day30, each with dayNN.json + images),
 * a single day's zip, or a zip with everything at the top level.
 */
object PinImport {
    private val imageExt = setOf("jpg", "jpeg", "png", "webp")

    fun read(ctx: Context, uri: Uri): List<ImportPin> {
        val dir = File(ctx.cacheDir, "import").apply { deleteRecursively(); mkdirs() }
        val jsons = mutableListOf<String>()
        val images = mutableMapOf<String, File>()   // lowercased file name -> unpacked file

        val name = (uri.lastPathSegment ?: "").lowercase()
        val input = ctx.contentResolver.openInputStream(uri) ?: throw IllegalStateException("Could not open that file")
        val head = input.use { s -> ByteArray(2).also { s.read(it) } }
        val isZip = head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()

        if (isZip) {
            ctx.contentResolver.openInputStream(uri)!!.use { raw ->
                ZipInputStream(raw.buffered()).use { zip ->
                    var e = zip.nextEntry
                    while (e != null) {
                        if (!e.isDirectory) {
                            val base = e.name.substringAfterLast('/').substringAfterLast('\\')
                            val ext = base.substringAfterLast('.', "").lowercase()
                            when {
                                base.startsWith(".") || e.name.contains("__MACOSX") -> {}
                                ext == "json" -> jsons += zip.readBytes().toString(Charsets.UTF_8)
                                ext in imageExt -> {
                                    val out = File(dir, "${images.size}_$base")
                                    out.outputStream().use { zip.copyTo(it) }
                                    images[base.lowercase()] = out
                                }
                            }
                        }
                        e = zip.nextEntry
                    }
                }
            }
        } else {
            // A single dayNN.json on its own: titles and times come in, but there are no images.
            jsons += ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
            if (!name.endsWith(".json") && !jsons.first().trimStart().startsWith("{")) {
                throw IllegalStateException("Pick the pins .zip file (or a dayNN.json)")
            }
        }

        val out = mutableListOf<ImportPin>()
        for (text in jsons) {
            val o = runCatching { JSONObject(text) }.getOrNull() ?: continue
            val pins = o.optJSONArray("pins") ?: continue
            val day = o.optInt("day", 0)
            for (i in 0 until pins.length()) {
                val p = pins.getJSONObject(i)
                val at = p.optString("at")
                if (at.isBlank()) continue
                val time = runCatching { Instant.parse(at).toEpochMilli() }.getOrNull() ?: continue
                val imageName = p.optString("image")
                out += ImportPin(
                    key = p.optString("id").ifBlank { "d${day}p${i + 1}" },
                    day = day,
                    title = p.optString("title").take(100),
                    description = pickDescription(p),
                    link = p.optString("link"),
                    altText = p.optString("alt").take(500),
                    boardName = p.optString("board"),
                    timeMillis = time,
                    image = images[imageName.lowercase()],
                    imageName = imageName,
                )
            }
        }
        if (out.isEmpty()) throw IllegalStateException("No pins with times found in that file")
        return out.distinctBy { it.key }.sortedBy { it.timeMillis }
    }

    /** Same as the Chrome extension: one of the description variants + up to 3 of the hashtags. */
    private fun pickDescription(p: JSONObject): String {
        val variants = p.optJSONArray("descriptions")
        val list = if (variants != null && variants.length() > 0)
            (0 until variants.length()).map { variants.getString(it) }
        else listOf(p.optString("description"))
        var d = list[Random.nextInt(list.size)]
        val tags = p.optJSONArray("hashtags")
        if (tags != null && tags.length() > 0) {
            val picked = (0 until tags.length()).map { tags.getString(it) }.shuffled().take(3)
            d += "\n" + picked.joinToString(" ") { if (it.startsWith("#")) it else "#$it" }
        }
        return d.take(500)
    }

    /** Loose match so "Ages 3–7" (en dash) still matches "Ages 3-7", ignoring case and punctuation. */
    fun norm(s: String) = s.lowercase().replace(Regex("[^a-z0-9]+"), " ").trim()

    fun findBoard(boards: List<Board>, name: String): Board? =
        boards.firstOrNull { it.name == name } ?: boards.firstOrNull { norm(it.name) == norm(name) }
}
