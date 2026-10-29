package com.nmr.pinscheduler

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.nmr.pinscheduler.api.PinterestApi
import com.nmr.pinscheduler.data.PinStore
import com.nmr.pinscheduler.data.Settings
import com.nmr.pinscheduler.ui.AppTheme
import com.nmr.pinscheduler.ui.BulkScreen
import com.nmr.pinscheduler.ui.EditorScreen
import com.nmr.pinscheduler.ui.ImportScreen
import com.nmr.pinscheduler.ui.QueueScreen
import com.nmr.pinscheduler.ui.SettingsScreen
import com.nmr.pinscheduler.ui.toast
import com.nmr.pinscheduler.work.Notifier
import com.nmr.pinscheduler.work.Scheduler
import kotlinx.coroutines.launch

sealed class Screen {
    data object Queue : Screen()
    data class Editor(val pinId: String?) : Screen()
    data object Bulk : Screen()
    data object Import : Screen()
    data object Settings : Screen()
}

class MainActivity : ComponentActivity() {
    private var screen by mutableStateOf<Screen>(Screen.Queue)
    private var settingsRefresh by mutableIntStateOf(0)

    private val notifPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PinStore.load(this)
        Notifier.init(this)
        Scheduler.ensureAll(this)
        if (Build.VERSION.SDK_INT >= 33) notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        handleOAuth(intent)

        setContent {
            AppTheme {
                val current = screen
                BackHandler(enabled = current != Screen.Queue) { screen = Screen.Queue }
                when (current) {
                    Screen.Queue -> QueueScreen(
                        onNew = { screen = Screen.Editor(null) },
                        onBulk = { screen = Screen.Bulk },
                        onImport = { screen = Screen.Import },
                        onEdit = { screen = Screen.Editor(it) },
                        onSettings = { screen = Screen.Settings },
                    )
                    is Screen.Editor -> EditorScreen(current.pinId) { screen = Screen.Queue }
                    Screen.Bulk -> BulkScreen { screen = Screen.Queue }
                    Screen.Import -> ImportScreen { screen = Screen.Queue }
                    Screen.Settings -> SettingsScreen(settingsRefresh) { screen = Screen.Queue }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleOAuth(intent)
    }

    /** Finishes Pinterest login when the browser sends us back to pinscheduler://oauth. */
    private fun handleOAuth(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != "pinscheduler" || data.host != "oauth") return
        intent.data = null
        val settings = Settings(this)
        val error = data.getQueryParameter("error")
        val code = data.getQueryParameter("code")
        val state = data.getQueryParameter("state")
        screen = Screen.Settings
        when {
            error != null -> toast(this, "Pinterest login cancelled: $error")
            code == null -> toast(this, "Pinterest login did not return a code")
            state != settings.oauthState -> toast(this, "Login check failed. Please try connecting again.")
            else -> lifecycleScope.launch {
                try {
                    val api = PinterestApi(this@MainActivity)
                    api.exchangeCode(code)
                    val user = runCatching { api.fetchUsername() }.getOrDefault("")
                    settings.oauthState = ""
                    toast(this@MainActivity, "Connected to Pinterest" + if (user.isNotBlank()) " as @$user" else "")
                } catch (e: Exception) {
                    toast(this@MainActivity, e.message ?: "Login failed")
                } finally {
                    settingsRefresh++
                }
            }
        }
    }
}
