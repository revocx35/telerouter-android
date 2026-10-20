package io.github.revocx35.telerouter.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.revocx35.telerouter.BuildConfig
import io.github.revocx35.telerouter.R

// ------------------------------------------------------------------------------------------------ first start
data class ConnectState(val address: String = "", val busy: Boolean = false, val error: String? = null)

/** First start: the address you open telerouter with in the browser. The sign-in itself happens on its own pages. */
@Composable
fun ConnectScreen(s: ConnectState, onAddress: (String) -> Unit, onContinue: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Spacer(Modifier.size(24.dp))
        Text("telerouter", style = MaterialTheme.typography.headlineMedium)
        Text("Your telerouter on the phone: the whole dashboard, and Jarvis as your assistant instead of Google Assistant.",
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        OutlinedTextField(
            s.address, onAddress, Modifier.fillMaxWidth(), label = { Text("telerouter's address") },
            placeholder = { Text("telerouter.example.com") }, singleLine = true, enabled = !s.busy,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onContinue() }),
        )
        Hint("The address you open telerouter with in the browser. Next you sign in as you do there: your login page " +
            "(e.g. Authentik) and telerouter's. This phone then adds itself as a speaker; no setup code needed.")
        if (s.error != null) Text(s.error, color = MaterialTheme.colorScheme.error)
        Button(onClick = onContinue, enabled = s.address.isNotBlank() && !s.busy, modifier = Modifier.fillMaxWidth()) {
            Text(if (s.busy) "Checking…" else "Continue")
        }
    }
}

// ------------------------------------------------------------------------------------------------ this phone
/** The phone's own settings (the assistant): what telerouter's web UI can't set. */
data class PhoneState(
    val base: String = "",
    val device: String? = null,        // the speaker this phone is in telerouter, null until it added itself
    val justAdded: Boolean = false,
    val isAssistant: Boolean = false,
    val wakeWord: Boolean = false,
    val hasMic: Boolean = false,
    val spokenAnswers: Boolean = true,
    val wakeScreen: Boolean = true,
    val confirmSignOut: Boolean = false,
)

class PhoneActions(
    val onBack: () -> Unit = {}, val onTalk: () -> Unit = {}, val onMakeAssistant: () -> Unit = {},
    val onWake: (Boolean) -> Unit = {}, val onMic: () -> Unit = {}, val onSpoken: (Boolean) -> Unit = {},
    val onScreen: (Boolean) -> Unit = {}, val onSignOut: () -> Unit = {}, val onConfirmSignOut: (Boolean) -> Unit = {},
    val onChangeServer: () -> Unit = {},
)

@Composable
fun PhoneScreen(s: PhoneState, a: PhoneActions) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            Row(Modifier.fillMaxWidth().padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = a.onBack) { Icon(painterResource(R.drawable.ic_back), contentDescription = "Back to the dashboard") }
                Text("This phone", style = MaterialTheme.typography.titleLarge)
            }
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                DeviceCard(s, a)
                AssistantCard(s, a)
                WakeCard(s, a)
                AnswersCard(s, a)
                AccountCard(s, a)
            }
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().widthIn(max = 720.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Hint(text: String) = Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Toggle(text: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Hint(text) }
        Spacer(Modifier.size(8.dp))
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}

private fun host(url: String) = url.removePrefix("https://")

@Composable
private fun DeviceCard(s: PhoneState, a: PhoneActions) = Section(if (s.justAdded) "Added to telerouter" else "Jarvis") {
    if (s.device == null) {
        Hint("This phone isn't a telerouter speaker yet. It adds itself once you are signed in to the dashboard.")
    } else {
        Hint((if (s.justAdded) "This phone is now the speaker “${s.device}” in telerouter. Set up the assistant below. " else
            "This phone is the speaker “${s.device}”. ") + "It connects to ${host(s.base)} only while you talk to Jarvis.")
        if (!s.hasMic) {
            Hint("telerouter needs the microphone to hear you.")
            OutlinedButton(onClick = a.onMic) { Text("Allow the microphone") }
        }
        Button(onClick = a.onTalk, modifier = Modifier.fillMaxWidth()) { Text("Talk to Jarvis") }
    }
}

@Composable
private fun AssistantCard(s: PhoneState, a: PhoneActions) = Section("Replace Google Assistant") {
    if (s.isAssistant) {
        Hint("telerouter is your digital assistant: hold the power or home button, or swipe up from a bottom corner, and Jarvis listens.")
    } else {
        Hint("Make telerouter the digital assistant app (Default apps → Digital assistant app → telerouter). Then the assistant button and gesture open Jarvis, also on the lock screen.")
        Button(onClick = a.onMakeAssistant) { Text("Open default apps") }
    }
}

@Composable
private fun WakeCard(s: PhoneState, a: PhoneActions) = Section("“Hey Jarvis”") {
    Toggle("Listen for “Hey Jarvis” all the time. The wake word is recognised on the phone (microWakeWord, as in Home Assistant's app), so nothing is sent until you say it; Android shows a notification while it listens.",
        s.wakeWord, a.onWake, enabled = s.isAssistant && s.device != null)
    if (!s.isAssistant) Hint("Make telerouter the digital assistant first: Android lets only the assistant listen in the background cheaply.")
    Toggle("Turn the screen on: “Hey Jarvis” lights the screen and shows the conversation, also over the lock screen (it stays locked).",
        s.wakeScreen, a.onScreen)
}

@Composable
private fun AnswersCard(s: PhoneState, a: PhoneActions) = Section("Answers") {
    Toggle("Spoken answers: Jarvis reads its answers aloud in telerouter's voice, at the media volume. Off: answers only appear as text.",
        s.spokenAnswers, a.onSpoken)
}

@Composable
private fun AccountCard(s: PhoneState, a: PhoneActions) = Section("Account") {
    Hint("Signed in at ${host(s.base)}. Signing out removes this phone from telerouter's speakers and forgets the sign-in on the phone.")
    if (s.confirmSignOut) {
        Hint("Sign out and remove this phone from telerouter?")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = a.onSignOut) { Text("Sign out") }
            OutlinedButton(onClick = { a.onConfirmSignOut(false) }) { Text("Cancel") }
        }
    } else {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { a.onConfirmSignOut(true) }) { Text("Sign out", color = MaterialTheme.colorScheme.error) }
            TextButton(onClick = a.onChangeServer) { Text("Use another address") }
        }
    }
    Hint("telerouter for Android ${BuildConfig.VERSION_NAME}. Wake word engine and the “Hey Jarvis” model from Home Assistant's companion app and microWakeWord (Apache-2.0).")
}

// ------------------------------------------------------------------------------------------------ dashboard errors
/** Shown over the dashboard when telerouter's address doesn't load. */
@Composable
fun DashboardError(message: String, onRetry: () -> Unit, onPhone: () -> Unit, onChangeServer: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Spacer(Modifier.size(24.dp))
            Text("Can't open telerouter", style = MaterialTheme.typography.titleLarge)
            Text(message, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Button(onClick = onRetry, modifier = Modifier.fillMaxWidth()) { Text("Try again") }
            OutlinedButton(onClick = onPhone, modifier = Modifier.fillMaxWidth()) { Text("This phone") }
            TextButton(onClick = onChangeServer) { Text("Use another address") }
        }
    }
}
