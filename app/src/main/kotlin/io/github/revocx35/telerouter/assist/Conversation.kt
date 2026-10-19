package io.github.revocx35.telerouter.assist

import io.github.revocx35.telerouter.core.Connection
import io.github.revocx35.telerouter.core.Protocol
import io.github.revocx35.telerouter.core.Protocol.State
import io.github.revocx35.telerouter.core.TelerouterError
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** The connection as the conversation sees it (core's [Connection] in the app, a fake in tests). */
interface Transport {
    val viaInternet: Boolean
    fun connect()
    fun talk(): Boolean
    fun cancel(): Boolean
    fun sendAudio(pcm: ByteArray): Boolean
    fun close()
}

interface AudioIn {
    /** Start delivering 16 kHz mono PCM16 (little-endian bytes) to [sink]; stop with [detach]. */
    fun attach(sink: (ByteArray) -> Unit)
    fun detach()
}

interface AudioOut {
    fun play(pcm: ByteArray)
    fun flush()
    fun chime(listening: Boolean)
    fun release()
}

data class Message(val fromUser: Boolean, val text: String)

enum class Phase { CONNECTING, LISTENING, THINKING, SPEAKING, CALL, IDLE, ERROR, ENDED }

data class UiState(
    val phase: Phase = Phase.ENDED,
    val messages: List<Message> = emptyList(),
    val error: String? = null,
    val viaInternet: Boolean = false,
)

/**
 * One conversation with telerouter, from the trigger (wake word, assistant gesture, tile) until it goes quiet.
 *
 * The phone plays its own start chime and records at once; the audio is kept while the connection opens and
 * sent right after `talk` (cue=false), so nothing said after "Hey Jarvis" is lost. The microphone is only sent
 * while telerouter listens (or during a call), not while it thinks or speaks. [LINGER_MS] after an answer the
 * connection closes; tapping the mic within that time asks again on the same connection.
 */
class Conversation(
    private val transportFactory: (Connection.Listener) -> Transport,
    private val mic: AudioIn,
    private val out: AudioOut,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val onEnded: () -> Unit = {},
) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var transport: Transport? = null
    private val preroll = java.io.ByteArrayOutputStream()
    @Volatile private var connected = false
    @Volatile private var sendMic = false
    @Volatile private var server = State.IDLE
    private var linger: Job? = null
    private var connectJob: Job? = null

    val active get() = _state.value.phase != Phase.ENDED

    /** Wake word / gesture / tap: start listening (a new connection, or the open one). */
    fun start() {
        linger?.cancel()
        val st = _state.value
        if (st.phase in listOf(Phase.LISTENING, Phase.CONNECTING, Phase.THINKING)) return
        if (st.phase == Phase.SPEAKING || st.phase == Phase.CALL) return       // use stop() to interrupt
        out.chime(true)
        synchronized(preroll) { preroll.reset() }
        _state.update { it.copy(phase = Phase.CONNECTING, error = null) }
        mic.attach(::onMic)
        if (connected) {
            beginTurn()
            return
        }
        val t = transportFactory(listener).also { transport = it }
        connectJob = scope.launch(io) {
            try {
                t.connect()
                connected = true
                _state.update { it.copy(viaInternet = t.viaInternet) }
                beginTurn()
            } catch (e: TelerouterError) {
                fail(e.message ?: "telerouter is not reachable")
            }
        }
    }

    private fun beginTurn() {
        val t = transport ?: return
        sendMic = true
        _state.update { it.copy(phase = Phase.LISTENING) }
        t.talk()
        val buffered = synchronized(preroll) { preroll.toByteArray().also { preroll.reset() } }
        var i = 0
        while (i < buffered.size) {                     // the server takes up to 3x real time
            val n = minOf(FRAME_BYTES, buffered.size - i)
            t.sendAudio(buffered.copyOfRange(i, i + n))
            i += n
        }
    }

    private fun onMic(pcm: ByteArray) {
        if (!connected || !sendMic) {
            if (!connected) synchronized(preroll) {
                if (preroll.size() < MAX_PREROLL) preroll.write(pcm)
            }
            return
        }
        transport?.sendAudio(pcm)
    }

    /** "Hey Jarvis" while this conversation is open: ask again when it waits, interrupt the answer when it
     *  speaks (the server cancels the turn first; listening starts once it is idle). */
    fun wake() {
        when (_state.value.phase) {
            Phase.IDLE, Phase.ERROR, Phase.ENDED -> start()
            Phase.SPEAKING -> {
                restartWhenIdle = true
                out.flush()
                transport?.cancel()
            }
            else -> {}                                        // already listening/thinking, or in a call
        }
    }

    @Volatile private var restartWhenIdle = false

    /** The stop button: interrupt the answer, or end. */
    fun stop() {
        if (server == State.SPEAKING || server == State.THINKING || server == State.LISTENING) {
            transport?.cancel()
            out.flush()
        }
        end()
    }

    fun end() {
        linger?.cancel()
        connectJob?.cancel()
        mic.detach()
        sendMic = false
        connected = false
        transport?.close()
        transport = null
        if (_state.value.phase != Phase.ENDED) {
            _state.update { it.copy(phase = Phase.ENDED) }
            out.release()
            onEnded()
        }
    }

    private fun fail(message: String) {
        mic.detach()
        connected = false
        transport?.close()
        transport = null
        out.chime(false)
        _state.update { it.copy(phase = Phase.ERROR, error = message) }
        linger?.cancel()
        linger = scope.launch { delay(ERROR_LINGER_MS); end() }
    }

    private val listener = object : Connection.Listener {
        override fun onEvent(event: Protocol.Event) {
            when (event) {
                is Protocol.Event.StateChanged -> onServerState(event.state)
                is Protocol.Event.Transcript -> _state.update { it.copy(messages = (it.messages + Message(event.fromUser, event.text)).takeLast(40)) }
                Protocol.Event.Flush -> out.flush()
                else -> {}
            }
        }

        override fun onAudio(pcm: ByteArray) = out.play(pcm)

        override fun onClosed(error: TelerouterError?) {
            if (!connected) return
            connected = false
            if (error != null) fail(error.message ?: "the connection was lost") else end()
        }
    }

    private fun onServerState(s: State) {
        val before = server
        server = s
        sendMic = s == State.LISTENING || s == State.CALL || s == State.RINGING
        val phase = when (s) {
            State.LISTENING -> Phase.LISTENING
            State.THINKING -> Phase.THINKING
            State.SPEAKING -> Phase.SPEAKING
            State.CALL, State.RINGING -> Phase.CALL
            State.IDLE -> Phase.IDLE
            State.OTHER -> _state.value.phase
        }
        _state.update { it.copy(phase = phase) }
        linger?.cancel()
        if (s == State.IDLE && restartWhenIdle) {
            restartWhenIdle = false
            scope.launch { start() }                          // the interrupted turn is over: listen now
            return
        }
        if (s == State.IDLE && before != State.IDLE) {
            mic.detach()
            linger = scope.launch { delay(LINGER_MS); end() }
        }
    }

    companion object {
        const val FRAME_BYTES = 640                        // 20 ms
        const val MAX_PREROLL = 16000 * 2 * 10             // 10 s
        const val LINGER_MS = 8000L
        const val ERROR_LINGER_MS = 6000L
    }
}
