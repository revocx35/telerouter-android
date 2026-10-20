package io.github.revocx35.telerouter.assist

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.core.content.ContextCompat
import io.github.revocx35.telerouter.BuildConfig
import io.github.revocx35.telerouter.audio.Chime
import io.github.revocx35.telerouter.audio.Mic
import io.github.revocx35.telerouter.audio.Player
import io.github.revocx35.telerouter.audio.toLeBytes
import io.github.revocx35.telerouter.core.Connection
import io.github.revocx35.telerouter.core.Protocol
import io.github.revocx35.telerouter.core.ServerConfig
import io.github.revocx35.telerouter.core.TelerouterError
import io.github.revocx35.telerouter.data.Net
import io.github.revocx35.telerouter.data.Store
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.launch

/**
 * The app's one assistant: starts conversations (from the wake word, the assistant gesture, the tile or a tap)
 * and shows them in [AssistActivity]. It lives in the app process, so a conversation the wake word started in
 * the background keeps the microphone audio from the very first moment.
 */
class Assistant(private val context: Context, private val store: Store) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val audio = context.getSystemService(AudioManager::class.java)
    private var focus: AudioFocusRequest? = null
    private val _current = MutableStateFlow<Conversation?>(null)
    val current: StateFlow<Conversation?> = _current

    /** Set by [AssistService] while it listens for the wake word: paused during a conversation. */
    var wakeWord: io.github.revocx35.telerouter.wake.WakeWord? = null

    val hasMic get() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** Start (or continue) a conversation and show it. Returns false when the app is not set up yet.
     *  [byWake]: "Hey Jarvis" said again while a conversation is open asks again, or interrupts the answer. */
    fun begin(showSheet: Boolean = true, byWake: Boolean = false): Boolean {
        val cfg = store.server() ?: return false
        val open = _current.value?.takeIf { it.active }
        val conv = open ?: newConversation(cfg)
        requestFocus()
        if (hasMic) {
            if (open != null && byWake) conv.wake() else conv.start()
        }
        if (showSheet) {
            context.startActivity(Intent(context, AssistActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        }
        return true
    }

    /** Spoken answers are on but the media volume (which they play at) is off. */
    val answersMuted get() = store.spokenAnswers && audio.getStreamVolume(AudioManager.STREAM_MUSIC) == 0

    private fun newConversation(cfg: ServerConfig): Conversation {
        val player = lazy { Player() }
        val speech = store.spokenAnswers
        val out = object : AudioOut {
            override fun play(pcm: ByteArray) { if (speech) player.value.play(pcm) }
            override fun flush() { if (player.isInitialized()) player.value.flush() }
            override fun chime(listening: Boolean) = player.value.play(if (listening) Chime.listening() else Chime.error())
            override fun release() { if (player.isInitialized()) player.value.release() }
        }
        val conv = Conversation(
            transportFactory = { l -> ConnectionTransport(Connection(cfg, BuildConfig.VERSION_NAME, Remember(l, store), Net.client,
                Net.api(cfg.baseUrl)::renewSignIn, speech), store) },
            mic = MicIn(),
            out = out,
            scope = scope,
            io = Dispatchers.IO,
            onEnded = ::ended,
        )
        _current.value = conv
        // the wake word counts while the conversation waits (after an answer, an error) or speaks (to interrupt)
        scope.launch {
            conv.state.takeWhile { it.phase != Phase.ENDED }.collect { st ->
                wakeWord?.paused = st.phase in listOf(Phase.CONNECTING, Phase.LISTENING, Phase.THINKING, Phase.CALL)
            }
        }
        return conv
    }

    private fun ended() {
        abandonFocus()
        wakeWord?.resume()
    }

    private fun requestFocus() {
        if (focus != null) return
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .build()
        audio.requestAudioFocus(req)
        focus = req
    }

    private fun abandonFocus() {
        focus?.let { audio.abandonAudioFocusRequest(it) }
        focus = null
    }
}

/** Keeps what telerouter says about this device (which one it is; that it was removed). */
private class ConnectionTransport(private val c: Connection, private val store: Store) : Transport {
    override fun connect() = try {
        c.connect()
    } catch (e: TelerouterError) {
        if (e.unauthorized) store.dropDevice()               // added again at the next sign-in in the app
        throw e
    }
    override fun talk() = c.talk()
    override fun cancel() = c.cancel()
    override fun sendAudio(pcm: ByteArray) = c.sendAudio(pcm)
    override fun close() = c.close()
}

private class Remember(private val l: Connection.Listener, private val store: Store) : Connection.Listener by l {
    override fun onEvent(event: Protocol.Event) {
        if (event is Protocol.Event.Config) store.device(event.clientId, event.name)
        l.onEvent(event)
    }

    override fun onClosed(error: TelerouterError?) {
        if (error?.unauthorized == true) store.dropDevice()
        l.onClosed(error)
    }
}

/** The shared microphone in 20 ms frames (telerouter's frame size). */
private class MicIn : AudioIn {
    @Volatile private var sink: ((ByteArray) -> Unit)? = null
    private val frame = ShortArray(320)
    private var have = 0
    private val listener = Mic.Listener { samples, count ->
        val s = sink ?: return@Listener
        var i = 0
        while (i < count) {
            val n = minOf(count - i, frame.size - have)
            System.arraycopy(samples, i, frame, have, n)
            have += n; i += n
            if (have == frame.size) {
                s(frame.toLeBytes()); have = 0
            }
        }
    }

    override fun attach(sink: (ByteArray) -> Unit) {
        have = 0
        this.sink = sink
        Mic.add(listener)
    }

    override fun detach() {
        Mic.remove(listener)
        sink = null
    }
}
