package com.nmr.pinscheduler.data

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

class Settings(ctx: Context) {
    private val p: SharedPreferences = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var accessToken: String
        get() = p.getString("accessToken", "") ?: ""
        set(v) = p.edit().putString("accessToken", v).apply()
    var refreshToken: String
        get() = p.getString("refreshToken", "") ?: ""
        set(v) = p.edit().putString("refreshToken", v).apply()
    var tokenExpiresAt: Long
        get() = p.getLong("tokenExpiresAt", 0L)
        set(v) = p.edit().putLong("tokenExpiresAt", v).apply()
    var appId: String
        get() = p.getString("appId", "") ?: ""
        set(v) = p.edit().putString("appId", v.trim()).apply()
    var appSecret: String
        get() = p.getString("appSecret", "") ?: ""
        set(v) = p.edit().putString("appSecret", v.trim()).apply()
    var oauthState: String
        get() = p.getString("oauthState", "") ?: ""
        set(v) = p.edit().putString("oauthState", v).apply()
    var sandbox: Boolean
        get() = p.getBoolean("sandbox", true)
        set(v) = p.edit().putBoolean("sandbox", v).apply()
    var username: String
        get() = p.getString("username", "") ?: ""
        set(v) = p.edit().putString("username", v).apply()
    var lastBoardId: String
        get() = p.getString("lastBoardId", "") ?: ""
        set(v) = p.edit().putString("lastBoardId", v).apply()

    /** Time slots for bulk scheduling, as minutes after midnight. */
    var timeSlots: List<Int>
        get() = (p.getString("timeSlots", "540,840,1200") ?: "540,840,1200")
            .split(",").mapNotNull { it.trim().toIntOrNull() }.sorted()
        set(v) = p.edit().putString("timeSlots", v.distinct().sorted().joinToString(",")).apply()

    var boards: List<Board>
        get() = runCatching {
            val arr = JSONArray(p.getString("boards", "[]"))
            (0 until arr.length()).map { arr.getJSONObject(it).let { o -> Board(o.getString("id"), o.getString("name")) } }
        }.getOrDefault(emptyList())
        set(v) {
            val arr = JSONArray()
            v.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name)) }
            p.edit().putString("boards", arr.toString()).apply()
        }

    val isConnected get() = accessToken.isNotBlank()

    fun disconnect() {
        p.edit().remove("accessToken").remove("refreshToken").remove("tokenExpiresAt")
            .remove("username").remove("boards").apply()
    }
}
