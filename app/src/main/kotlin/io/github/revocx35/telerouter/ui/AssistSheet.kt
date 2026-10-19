package io.github.revocx35.telerouter.ui

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import io.github.revocx35.telerouter.R
import io.github.revocx35.telerouter.assist.Message
import io.github.revocx35.telerouter.assist.Phase
import io.github.revocx35.telerouter.assist.UiState

fun phaseText(p: Phase, viaInternet: Boolean): String = when (p) {
    Phase.CONNECTING -> "Connecting…"
    Phase.LISTENING -> "Listening…"
    Phase.THINKING -> "Thinking…"
    Phase.SPEAKING -> "Answering"
    Phase.CALL -> "In a call"
    Phase.IDLE -> "Tap the mic to ask again"
    Phase.ERROR -> "Couldn't reach telerouter"
    Phase.ENDED -> "Tap the mic to ask"
} + if (viaInternet && p != Phase.ERROR && p != Phase.ENDED) " · over the internet" else ""

/** The conversation as a bottom sheet over whatever is on screen. */
@Composable
fun AssistSheet(state: UiState, name: String, onMic: () -> Unit, onStop: () -> Unit, onDismiss: () -> Unit, extra: String? = null) {
    Box(Modifier.fillMaxSize().clickable(onClick = onDismiss), contentAlignment = Alignment.BottomCenter) {
        Surface(
            Modifier.fillMaxWidth().widthIn(max = 640.dp).clickable(enabled = false) {},
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(Modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
                Box(Modifier.align(Alignment.CenterHorizontally).size(36.dp, 4.dp).clip(CircleShape)
                    .background(MaterialTheme.colorScheme.surfaceVariant))
                Spacer(Modifier.size(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    TextButton(onClick = onStop) { Text(if (state.phase in listOf(Phase.SPEAKING, Phase.THINKING, Phase.LISTENING)) "Stop" else "Close") }
                }
                Messages(state.messages)
                if (state.error != null) {
                    Text(state.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp))
                }
                if (extra != null) {
                    Text(extra, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(vertical = 8.dp))
                }
                Spacer(Modifier.size(8.dp))
                MicButton(state.phase, onMic, Modifier.align(Alignment.CenterHorizontally))
                Text(phaseText(state.phase, state.viaInternet), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 6.dp, bottom = 4.dp))
            }
        }
    }
}

@Composable
private fun Messages(messages: List<Message>) {
    val list = rememberLazyListState()
    LaunchedEffect(messages.size) { if (messages.isNotEmpty()) list.animateScrollToItem(messages.size - 1) }
    LazyColumn(Modifier.fillMaxWidth().heightIn(max = 320.dp), state = list, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items(messages) { m ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = if (m.fromUser) Arrangement.End else Arrangement.Start) {
                Text(
                    m.text,
                    modifier = Modifier.widthIn(max = 300.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (m.fromUser) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    color = if (m.fromUser) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
    }
}

@Composable
private fun MicButton(phase: Phase, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val s by pulse.animateFloat(1f, 1.12f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "s")
    val live = phase == Phase.LISTENING || phase == Phase.CONNECTING
    Box(
        modifier.size(72.dp).scale(if (live) s else 1f).clip(CircleShape)
            .background(if (live) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer)
            .clickable(onClick = onClick).semantics { contentDescription = "Talk to Jarvis" },
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(R.drawable.ic_mic), contentDescription = null,
            tint = if (live) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(32.dp))
    }
}
