package io.github.revocx35.telerouter.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import io.github.revocx35.telerouter.BuildConfig
import io.github.revocx35.telerouter.app
import io.github.revocx35.telerouter.assist.AssistActivity
import io.github.revocx35.telerouter.assist.AssistService
import io.github.revocx35.telerouter.core.Enroll
import io.github.revocx35.telerouter.core.ServerConfig
import io.github.revocx35.telerouter.core.SetupLink
import io.github.revocx35.telerouter.core.Trust
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Screen state, kept small so the screens can be rendered in tests. */
data class MainState(
    val server: ServerConfig? = null,
    val isAssistant: Boolean = false,
    val wakeWord: Boolean = false,
    val hasMic: Boolean = false,
    val link: String = "",
    val busy: Boolean = false,
    val error: String? = null,
    val pending: Pair<SetupLink, String>? = null,      // checked link + CA fingerprint, waiting for "Connect"
)

class MainActivity : ComponentActivity() {
    private var ui by mutableStateOf(MainState())
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private val askForWake = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) setWake(true) else refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            TelerouterTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainScreen(ui, Actions(
                        onLink = { ui = ui.copy(link = it, error = null) },
                        onPaste = ::pasteLink,
                        onCheck = { check(ui.link) },
                        onConnect = ::connect,
                        onCancel = { ui = ui.copy(pending = null) },
                        onTalk = { startActivity(Intent(this, AssistActivity::class.java).putExtra(AssistActivity.EXTRA_START, true)) },
                        onMakeAssistant = ::openAssistantSettings,
                        onWake = ::setWake,
                        onMic = { askMic.launch(Manifest.permission.RECORD_AUDIO) },
                        onForget = ::forget,
                    ))
                }
            }
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun handle(intent: Intent?) {
        val data = intent?.data?.toString() ?: return
        if (data.startsWith("telerouter://")) {
            ui = ui.copy(link = data)
            check(data)
        }
    }

    private fun refresh() {
        val s = app.store
        ui = ui.copy(server = s.server(), isAssistant = AssistService.isAssistant(this), wakeWord = s.wakeWord,
            hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }

    private fun pasteLink() {
        val clip = getSystemService(ClipboardManager::class.java).primaryClip
        val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString() ?: ""
        ui = ui.copy(link = text.trim(), error = null)
    }

    /** Parse the link and fetch the server's CA; the person then confirms the connection. */
    private fun check(text: String) {
        val link = try {
            SetupLink.parse(text)
        } catch (e: IllegalArgumentException) {
            ui = ui.copy(error = e.message); return
        }
        ui = ui.copy(busy = true, error = null)
        lifecycleScope.launch {
            try {
                val ca = withContext(Dispatchers.IO) { Trust.fetchPinnedCa(link.server, link.caSha256) }
                ui = ui.copy(busy = false, pending = link to ca)
            } catch (e: Exception) {
                ui = ui.copy(busy = false, error = when (e) {
                    is Trust.TrustError -> e.message
                    else -> "Can't reach ${link.server}. Is the phone on your home network? (${e.message})"
                })
            }
        }
    }

    private fun connect() {
        val (link, ca) = ui.pending ?: return
        ui = ui.copy(busy = true)
        lifecycleScope.launch {
            try {
                val cfg = withContext(Dispatchers.IO) { Enroll.enroll(link, ca) }
                app.store.saveServer(cfg)
                ui = ui.copy(busy = false, pending = null, link = "")
                refresh()
            } catch (e: Exception) {
                ui = ui.copy(busy = false, pending = null, error = e.message ?: "Setup failed")
            }
        }
    }

    private fun openAssistantSettings() {
        val intents = listOf(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
        for (i in intents) {
            try {
                startActivity(i); return
            } catch (_: Exception) {}
        }
    }

    private fun setWake(on: Boolean) {
        if (on) {
            val need = buildList {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Manifest.permission.RECORD_AUDIO in need) {
                askForWake.launch(need.toTypedArray()); return
            }
            if (need.isNotEmpty()) askForWake.launch(need.toTypedArray())
        }
        app.store.wakeWord = on
        AssistService.applySetting(this)
        refresh()
    }

    private fun forget() {
        app.store.forget()
        AssistService.applySetting(this)
        refresh()
    }
}

class Actions(
    val onLink: (String) -> Unit = {}, val onPaste: () -> Unit = {}, val onCheck: () -> Unit = {},
    val onConnect: () -> Unit = {}, val onCancel: () -> Unit = {}, val onTalk: () -> Unit = {},
    val onMakeAssistant: () -> Unit = {}, val onWake: (Boolean) -> Unit = {}, val onMic: () -> Unit = {},
    val onForget: () -> Unit = {},
)

@Composable
fun MainScreen(s: MainState, a: Actions) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("telerouter", style = MaterialTheme.typography.headlineMedium)
        Text("Your own assistant: Jarvis through telerouter, instead of Google Assistant.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (s.server == null) SetupCard(s, a) else {
            StatusCard(s, a)
            AssistantCard(s, a)
            WakeCard(s, a)
            AboutCard(a)
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun SetupCard(s: MainState, a: Actions) = Section("Set up") {
    val p = s.pending
    if (p == null) {
        Hint("In telerouter (on your home network): Speakers → Add device, give the phone a name, then tap “Open in the telerouter app” on this phone, or copy the link and paste it here.")
        OutlinedTextField(s.link, a.onLink, Modifier.fillMaxWidth(), label = { Text("Setup link") }, singleLine = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = a.onPaste) { Text("Paste") }
            Button(onClick = a.onCheck, enabled = s.link.isNotBlank() && !s.busy) { Text(if (s.busy) "Checking…" else "Continue") }
        }
    } else {
        Hint("telerouter at ${p.first.server} presented the certificate named in the link:")
        Text(Trust.pretty(p.first.caSha256), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        if (p.first.publicUrl != null) Hint("Away from home the phone uses ${p.first.publicUrl}.")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = a.onCancel) { Text("Cancel") }
            Button(onClick = a.onConnect, enabled = !s.busy) { Text(if (s.busy) "Connecting…" else "Connect") }
        }
    }
    if (s.error != null) Text(s.error, color = MaterialTheme.colorScheme.error)
}

@Composable
private fun StatusCard(s: MainState, a: Actions) = Section("Jarvis") {
    val cfg = s.server!!
    Hint("This phone is the speaker “${cfg.name}” of telerouter at ${cfg.lanUrl}" +
        (cfg.publicUrl?.let { ", and $it away from home." } ?: ". Away from home it needs your VPN."))
    if (!s.hasMic) {
        Hint("telerouter needs the microphone to hear you.")
        OutlinedButton(onClick = a.onMic) { Text("Allow the microphone") }
    }
    Button(onClick = a.onTalk, modifier = Modifier.fillMaxWidth()) { Text("Talk to Jarvis") }
}

@Composable
private fun AssistantCard(s: MainState, a: Actions) = Section("Replace Google Assistant") {
    if (s.isAssistant) {
        Hint("telerouter is your digital assistant: hold the power or home button, or swipe up from a bottom corner, and Jarvis listens.")
    } else {
        Hint("Make telerouter the digital assistant app (Default apps → Digital assistant app → telerouter). Then the assistant button and gesture open Jarvis, also on the lock screen.")
        Button(onClick = a.onMakeAssistant) { Text("Open default apps") }
    }
}

@Composable
private fun WakeCard(s: MainState, a: Actions) = Section("“Hey Jarvis”") {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Hint("Listen for “Hey Jarvis” all the time. The wake word is recognised on the phone (microWakeWord, as in Home Assistant's app), so nothing is sent until you say it; Android shows a notification while it listens.")
        }
        Spacer(Modifier.size(8.dp))
        Switch(checked = s.wakeWord, onCheckedChange = a.onWake, enabled = s.isAssistant)
    }
    if (!s.isAssistant) Hint("Make telerouter the digital assistant first: Android lets only the assistant listen in the background cheaply.")
}

@Composable
private fun AboutCard(a: Actions) = Section("About") {
    Hint("telerouter ${BuildConfig.VERSION_NAME}. Wake word engine and the “Hey Jarvis” model from Home Assistant's companion app and microWakeWord (Apache-2.0).")
    TextButton(onClick = a.onForget) { Text("Forget this server", color = MaterialTheme.colorScheme.error) }
}
