package com.nmr.pinscheduler.data

import org.json.JSONObject

enum class PinStatus { PENDING, POSTING, POSTED, FAILED }

data class Board(val id: String, val name: String)

data class ScheduledPin(
    val id: String,
    val imagePath: String,
    val title: String,
    val description: String,
    val link: String,
    val altText: String,
    val boardId: String,
    val boardName: String,
    val timeMillis: Long,
    val status: PinStatus = PinStatus.PENDING,
    val error: String? = null,
    val pinterestId: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("imagePath", imagePath)
        put("title", title)
        put("description", description)
        put("link", link)
        put("altText", altText)
        put("boardId", boardId)
        put("boardName", boardName)
        put("timeMillis", timeMillis)
        put("status", status.name)
        put("error", error ?: "")
        put("pinterestId", pinterestId ?: "")
    }

    companion object {
        fun fromJson(o: JSONObject) = ScheduledPin(
            id = o.getString("id"),
            imagePath = o.getString("imagePath"),
            title = o.optString("title"),
            description = o.optString("description"),
            link = o.optString("link"),
            altText = o.optString("altText"),
            boardId = o.optString("boardId"),
            boardName = o.optString("boardName"),
            timeMillis = o.getLong("timeMillis"),
            status = runCatching { PinStatus.valueOf(o.optString("status")) }.getOrDefault(PinStatus.PENDING),
            error = o.optString("error").ifBlank { null },
            pinterestId = o.optString("pinterestId").ifBlank { null },
        )
    }
}
