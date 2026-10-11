@file:OptIn(ExperimentalMaterial3Api::class)

package com.nmr.pinscheduler.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.nmr.pinscheduler.api.PinterestApi
import com.nmr.pinscheduler.data.Settings
import kotlinx.coroutines.launch
import java.util.UUID

@Composable
fun SettingsScreen(refreshKey: Int, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val settings = remember { Settings(ctx) }
    val scope = rememberCoroutineScope()

    // Bumped to re-read settings after login completes or values change.
    var version by remember { mutableIntStateOf(0) }
    val key = version + refreshKey
    val connected = remember(key) { settings.isConnected }
    val username = remember(key) { settings.username }

    var appId by remember { mutableStateOf(settings.appId) }
    var appSecret by remember { mutableStateOf(settings.appSecret) }
    var manualToken by remember { mutableStateOf("") }
    var sandbox by remember { mutableStateOf(settings.sandbox) }
    var checking by remember { mutableStateOf(false) }

    fun check() {
        checking = true
        scope.launch {
            try {
                val u = PinterestApi(ctx).fetchUsername()
                toast(ctx, "Connected as @$u")
            } catch (e: Exception) {
                toast(ctx, e.message ?: "Connection check failed")
            } finally {
                checking = false
                version++
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.Default.ArrowBack, "Back") } }
            )
        }
    ) { pad ->
        Column(Modifier.padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(16.dp)) {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        if (connected) "Connected" + (if (username.isNotBlank()) " as @$username" else "")
                        else "Not connected",
                        fontWeight = FontWeight.Bold
                    )
                    if (connected) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { check() }, enabled = !checking) {
                                Text(if (checking) "Checking…" else "Check connection")
                            }
                            TextButton(onClick = { settings.disconnect(); version++ }) { Text("Disconnect") }
                        }
                    }
                }
            }

            SectionLabel("Pinterest access mode")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Trial mode (sandbox)", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Keep this ON while your Pinterest developer app has Trial access: pins go to " +
                            "Pinterest's sandbox and only you can see them. Turn it OFF once Pinterest " +
                            "approves Standard access, so pins go live.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = sandbox, onCheckedChange = {
                    sandbox = it
                    settings.sandbox = it
                    settings.boards = emptyList() // sandbox and live have different boards
                })
            }

            SectionLabel("Connect with Pinterest login (recommended)")
            Text(
                "Enter your app's ID and secret from developers.pinterest.com → My apps. " +
                    "Add the redirect URI below to that app first.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                appId, { appId = it }, label = { Text("App ID") },
                singleLine = true, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                appSecret, { appSecret = it }, label = { Text("App secret key") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation()
            )
            Spacer(Modifier.height(8.dp))
            Text("Redirect URI:", style = MaterialTheme.typography.bodySmall)
            Text(PinterestApi.REDIRECT_URI, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold)
            TextButton(onClick = { copy(ctx, PinterestApi.REDIRECT_URI) }) { Text("Copy redirect URI") }
            Button(
                enabled = appId.isNotBlank() && appSecret.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
                onClick = {
                    settings.appId = appId
                    settings.appSecret = appSecret
                    val state = UUID.randomUUID().toString()
                    settings.oauthState = state
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(PinterestApi.authorizeUrl(appId.trim(), state)))
                    )
                }
            ) { Text("Connect with Pinterest") }

            SectionLabel("Or paste an access token")
            Text(
                "Quick option for testing: generate a token for your own account on your app's page " +
                    "at developers.pinterest.com (scopes: boards:read, boards:write, pins:read, pins:write, " +
                    "user_accounts:read). Pasted tokens can't auto-renew.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                manualToken, { manualToken = it.trim() }, label = { Text("Access token") },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation()
            )
            OutlinedButton(
                enabled = manualToken.isNotBlank(),
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                onClick = {
                    settings.accessToken = manualToken
                    settings.refreshToken = ""
                    settings.tokenExpiresAt = 0L
                    manualToken = ""
                    check()
                }
            ) { Text("Save token") }

            SectionLabel("Reliable timing")
            Text(
                "Android may delay background work to save battery. For on-time posts, open App info → " +
                    "Battery and choose Unrestricted.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                onClick = {
                    ctx.startActivity(
                        Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                    )
                }
            ) { Text("Open App info") }

            SectionLabel("Privacy")
            TextButton(onClick = {
                ctx.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse("https://mnameerturk.github.io/auto-pinterest-app/privacy.html"))
                )
            }) { Text("Privacy policy") }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("redirect", text))
    toast(ctx, "Copied")
}
