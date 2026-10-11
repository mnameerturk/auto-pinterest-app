package com.nmr.pinscheduler.api

import android.content.Context
import android.util.Base64
import com.nmr.pinscheduler.data.Board
import com.nmr.pinscheduler.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class ApiException(val code: Int, message: String) : Exception(message) {
    /** Worth retrying later (rate limit or Pinterest server trouble). */
    val transient get() = code == 429 || code >= 500
}

class PinterestApi(private val ctx: Context) {
    private val settings = Settings(ctx)

    companion object {
        const val REDIRECT_URI = "https://mnameerturk.github.io/auto-pinterest-app/callback.html"
        const val SCOPES = "boards:read,boards:write,pins:read,pins:write,user_accounts:read"
        private const val PROD = "https://api.pinterest.com/v5"
        private const val SANDBOX = "https://api-sandbox.pinterest.com/v5"

        fun authorizeUrl(appId: String, state: String): String =
            "https://www.pinterest.com/oauth/?client_id=" + enc(appId) +
                "&redirect_uri=" + enc(REDIRECT_URI) +
                "&response_type=code&scope=" + enc(SCOPES) +
                "&state=" + enc(state)

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    }

    private val base get() = if (settings.sandbox) SANDBOX else PROD

    // ---------- OAuth ----------

    suspend fun exchangeCode(code: String) = tokenRequest(
        "grant_type=authorization_code&code=${enc(code)}&redirect_uri=${enc(REDIRECT_URI)}"
    )

    private suspend fun refresh(): Boolean {
        if (settings.refreshToken.isBlank() || settings.appId.isBlank() || settings.appSecret.isBlank()) return false
        return runCatching {
            tokenRequest("grant_type=refresh_token&refresh_token=${enc(settings.refreshToken)}")
        }.isSuccess
    }

    private suspend fun tokenRequest(form: String) = withContext(Dispatchers.IO) {
        val conn = URL("$PROD/oauth/token").openConnection() as HttpURLConnection
        try {
            val basic = Base64.encodeToString(
                "${settings.appId}:${settings.appSecret}".toByteArray(), Base64.NO_WRAP
            )
            conn.requestMethod = "POST"
            conn.connectTimeout = 20000
            conn.readTimeout = 30000
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Basic $basic")
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.outputStream.use { it.write(form.toByteArray()) }
            val json = readJson(conn)
            settings.accessToken = json.getString("access_token")
            json.optString("refresh_token").takeIf { it.isNotBlank() }?.let { settings.refreshToken = it }
            val expiresIn = json.optLong("expires_in", 0L)
            settings.tokenExpiresAt = if (expiresIn > 0) System.currentTimeMillis() + expiresIn * 1000 else 0L
        } finally {
            conn.disconnect()
        }
    }

    // ---------- API calls ----------

    private suspend fun call(method: String, path: String, body: JSONObject? = null, retried: Boolean = false): JSONObject {
        if (settings.accessToken.isBlank()) throw ApiException(401, "Not connected to Pinterest. Open Settings to connect.")
        // Refresh a little before expiry.
        if (!retried && settings.tokenExpiresAt in 1..(System.currentTimeMillis() + 5 * 60_000)) refresh()
        return try {
            withContext(Dispatchers.IO) {
                val conn = URL(base + path).openConnection() as HttpURLConnection
                try {
                    conn.requestMethod = method
                    conn.connectTimeout = 20000
                    conn.readTimeout = 60000
                    conn.setRequestProperty("Authorization", "Bearer ${settings.accessToken}")
                    conn.setRequestProperty("Accept", "application/json")
                    if (body != null) {
                        conn.doOutput = true
                        conn.setRequestProperty("Content-Type", "application/json")
                        conn.outputStream.use { it.write(body.toString().toByteArray()) }
                    }
                    readJson(conn)
                } finally {
                    conn.disconnect()
                }
            }
        } catch (e: ApiException) {
            if (e.code == 401 && !retried && refresh()) call(method, path, body, true) else throw e
        }
    }

    private fun readJson(conn: HttpURLConnection): JSONObject {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() } ?: ""
        if (code !in 200..299) {
            val msg = runCatching { JSONObject(text).optString("message") }.getOrNull()
                ?.takeIf { it.isNotBlank() } ?: text.take(300).ifBlank { "HTTP $code" }
            throw ApiException(code, "Pinterest error $code: $msg")
        }
        return if (text.isBlank()) JSONObject() else JSONObject(text)
    }

    suspend fun fetchUsername(): String {
        val json = call("GET", "/user_account")
        return json.optString("username").also { settings.username = it }
    }

    suspend fun listBoards(): List<Board> {
        val out = mutableListOf<Board>()
        var bookmark: String? = null
        do {
            val q = "/boards?page_size=100" + (bookmark?.let { "&bookmark=" + enc(it) } ?: "")
            val json = call("GET", q)
            val items = json.optJSONArray("items")
            if (items != null) for (i in 0 until items.length()) {
                val b = items.getJSONObject(i)
                out += Board(b.getString("id"), b.optString("name"))
            }
            bookmark = json.optString("bookmark").takeIf { it.isNotBlank() && it != "null" }
        } while (bookmark != null && out.size < 1000)
        settings.boards = out
        return out
    }

    suspend fun createBoard(name: String): Board {
        val json = call("POST", "/boards", JSONObject().put("name", name))
        val board = Board(json.getString("id"), json.optString("name", name))
        settings.boards = settings.boards + board
        return board
    }

    /** Publishes a pin and returns its Pinterest id. */
    suspend fun createPin(
        boardId: String, imagePath: String, title: String, description: String, link: String, altText: String,
    ): String {
        val bytes = withContext(Dispatchers.IO) { File(imagePath).readBytes() }
        val media = JSONObject()
            .put("source_type", "image_base64")
            .put("content_type", "image/jpeg")
            .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
        val body = JSONObject().put("board_id", boardId).put("media_source", media)
        if (title.isNotBlank()) body.put("title", title.take(100))
        if (description.isNotBlank()) body.put("description", description.take(500))
        if (link.isNotBlank()) body.put("link", link)
        if (altText.isNotBlank()) body.put("alt_text", altText.take(500))
        return call("POST", "/pins", body).getString("id")
    }
}
