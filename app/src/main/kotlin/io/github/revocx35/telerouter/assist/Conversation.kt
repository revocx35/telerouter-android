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
    fun connect()
    fun talk(): Boolean
    fun cancel(): Boolean
    fun utterance(text: String): Boolean
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

/** Speech recognition on the phone (Android's on-device recogniser in the app, a fake in tests). */
interface Recognizer {
    interface Listener {
        /** What has been understood so far (shown live). */
        fun partial(text: String)
        /** The final words; "" = nothing was said. */
        fun result(text: String)
        /** The recogniser can't work now (no language model, busy): telerouter's Whisper takes over. */
        fun failed()
    }

    interface Session {
        /** 16 kHz mono PCM16 LE from the shared microphone. */
        fun feed(pcm: ByteArray)
        fun cancel()
    }

    fun start(language: String, listener: Listener): Session
}

/** The phone's own voice for answers (Android's text-to-speech in the app). */
interface Voice {
    /** Queue [text]; [done] runs once it has been read (or dropped). */
    fun speak(text: String, done: () -> Unit)
    fun stop()
    fun release()
}

data class Message(val fromUser: Boolean, val text: String)

enum class Phase { CONNECTING, LISTENING, THINKING, SPEAKING, CALL, IDLE, ERROR, ENDED }

data class UiState(
    val phase: Phase = Phase.ENDED,
    val messages: List<Message> = emptyList(),
    val error: String? = null,
    /** The error needs the app: sign in again, or add the phone again. */
    val needsApp: Boolean = false,
    /** What the phone's recogniser has understood so far, while the person speaks. */
    val partial: String? = null,
)

/**
 * One conversation with telerouter, from the trigger (wake word, assistant gesture, tile) until it goes quiet.
 *
 * Two ways to hear the person:
 * - **On the phone** ([recognizer], telerouter 0.16+): the phone recognises speech itself, from the very first
 *   moment (no waiting for the connection), and sends only the words (`utterance`). Answers come as text and the
 *   phone reads them with its own [voice]. When telerouter listens again (a follow-up, an agent's question) the
 *   phone listens once it has finished reading. The audio is kept meanwhile, so when the recogniser fails (no
 *   language model, busy) or the server is older, the same audio goes to telerouter's Whisper instead.
 * - **telerouter's Whisper**: the phone plays its own start chime and records at once; the audio is kept while the
 *   connection opens and sent right after `talk` (cue=false), so nothing said after "Hey Jarvis" is lost. The
 *   microphone is only sent while telerouter listens (or during a call), not while it thinks or speaks.
 *
 * [LINGER_MS] after an answer the connection closes; tapping the mic within that time asks again on the same
 * connection.
 */
class Conversation(
    private val transportFactory: (Connection.Listener) -> Transport,
    private val mic: AudioIn,
    private val out: AudioOut,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val onEnded: () -> Unit = {},
    /** On-device speech recognition; null = telerouter's Whisper hears the audio. */
    private val recognizer: Recognizer? = null,
    /** The phone reads answers itself; null = telerouter's voice (or text only). */
    private val voice: Voice? = null,
    /** The language for [recognizer] (telerouter's, remembered from the last connection). */
    private val language: () -> String = { "" },
    private val onLanguage: (String) -> Unit = {},
) {
    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state

    private var transport: Transport? = null
    /** Audio kept for telerouter: while connecting, or while the phone's recogniser listens (its fallback). */
    private val preroll = java.io.ByteArrayOutputStream()
    @Volatile private var connected = false
    @Volatile private var sendMic = false
    @Volatile private var server = State.IDLE
    private var linger: Job? = null
    private var connectJob: Job? = null

    // -- speech on the phone -----------------------------------------------------------------------------------
    /** null until telerouter's config arrived: whether it takes utterances. */
    @Volatile private var serverTakesText: Boolean? = null
    /** The recogniser failed (or telerouter is too old): Whisper hears the rest of this conversation. */
    @Volatile private var recognizerOff = false
    @Volatile private var session: Recognizer.Session? = null
    /** Words waiting to be sent (the connection or telerouter isn't ready for them yet). */
    @Volatile private var pendingText: String? = null
    /** The recogniser gave up: the kept audio goes to telerouter (talk + audio) once it is idle. */
    @Volatile private var pendingAudio = false
    /** This turn's audio goes to telerouter (Whisper), not to the phone's recogniser. */
    @Volatile private var audioTurn = false
    /** The last thing the phone heard itself: telerouter echoes it as a transcript, shown only once. */
    @Volatile private var heardHere: String? = null
    @Volatile private var reading = 0
    /** telerouter listens again (follow-up, question) while the phone still reads: listen when done. */
    @Volatile private var listenAfterReading = false

    private val onDevice get() = recognizer != null && !recognizerOff && serverTakesText != false

    val active get() = _state.value.phase != Phase.ENDED

    /** Wake word / gesture / tap: start listening (a new connection, or the open one). */
    fun start() {
        linger?.cancel()
        val st = _state.value
        if (st.phase in listOf(Phase.LISTENING, Phase.CONNECTING, Phase.THINKING)) return
        if (st.phase == Phase.SPEAKING || st.phase == Phase.CALL) return       // use stop() to interrupt
        listen()
    }

    private fun listen() {
        linger?.cancel()
        out.chime(true)
        synchronized(preroll) { preroll.reset() }
        val here = onDevice
        _state.update { it.copy(phase = if (here) Phase.LISTENING else if (connected) it.phase else Phase.CONNECTING,
            error = null, needsApp = false, partial = null) }
        mic.attach(::onMic)
        if (here) startRecognizer()
        if (connected) {
            if (!here) beginTurn()
            return
        }
        if (connectJob?.isActive == true) return                // still connecting: it sends what waits
        val t = transportFactory(listener).also { transport = it }
        connectJob = scope.launch(io) {
            try {
                t.connect()
                connected = true
                if (!onDevice && session == null && pendingText == null) beginTurn() else flushPending()
            } catch (e: TelerouterError) {
                fail(e.message ?: "telerouter is not reachable", e.signIn || e.unauthorized)
            }
        }
    }

    private fun startRecognizer() {
        val rec = recognizer ?: return
        session?.cancel()
        session = rec.start(language(), object : Recognizer.Listener {
            override fun partial(text: String) {
                if (session != null) _state.update { it.copy(partial = text.ifBlank { null }) }
            }

            override fun result(text: String) = scope.launch { heard(text.trim()) }.let {}
            override fun failed() = scope.launch { recognizerFailed() }.let {}
        })
    }

    /** The phone's recogniser has the final words ("" = nothing said). */
    private fun heard(text: String) {
        if (session == null) return
        session = null
        _state.update { it.copy(partial = null) }
        if (text.isEmpty()) {
            mic.detach()
            synchronized(preroll) { preroll.reset() }
            if (server == State.LISTENING && connected) transport?.utterance("")   // telerouter waited: nothing said
            quiet()
            return
        }
        heardHere = text
        _state.update { it.copy(phase = Phase.THINKING, messages = (it.messages + Message(true, text)).takeLast(40)) }
        pendingText = text
        flushPending()
    }

    private fun recognizerFailed() {
        if (session == null) return
        session = null
        recognizerOff = true
        _state.update { it.copy(partial = null) }
        pendingAudio = true
        flushPending()
    }

    /** Send what waits for telerouter once it can take it: the words, or the kept audio (Whisper). */
    private fun flushPending() {
        if (!connected) return
        val t = transport ?: return
        val text = pendingText
        if (text != null) {
            val takes = serverTakesText ?: return                       // its config comes right after connecting
            if (!takes) {                                               // an older telerouter: let Whisper hear it
                pendingText = null
                recognizerOff = true
                _state.update { it.copy(messages = it.messages.dropLast(1)) }
                heardHere = null
                pendingAudio = true
            } else {
                when (server) {
                    State.IDLE, State.LISTENING -> { pendingText = null; t.utterance(text) }
                    State.THINKING, State.SPEAKING -> t.cancel()        // the old turn first; sent once idle
                    else -> {}
                }
                return
            }
        }
        if (pendingAudio) {
            when (server) {
                State.IDLE -> { pendingAudio = false; beginTurn() }
                State.LISTENING -> if (!audioTurn) t.utterance("")      // end its wait for words; then talk
                State.THINKING, State.SPEAKING -> t.cancel()
                else -> {}
            }
        }
    }

    private fun beginTurn() {
        val t = transport ?: return
        serverAudio = false
        audioTurn = true
        sendMic = true
        mic.attach(::onMic)
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
        val s = session
        if (s != null) {
            s.feed(pcm)
            synchronized(preroll) { if (preroll.size() < MAX_PREROLL) preroll.write(pcm) }
            return
        }
        if (!connected || !sendMic) {
            if (!connected || pendingAudio) synchronized(preroll) {
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
                out.flush()
                if (reading > 0) {                                    // the phone reads: stop and listen now
                    stopReading()
                    if (server == State.THINKING || server == State.SPEAKING) transport?.cancel()
                    if (onDevice) listen() else { restartWhenIdle = server != State.IDLE; if (!restartWhenIdle) listen() }
                } else {
                    restartWhenIdle = true
                    transport?.cancel()
                }
            }
            else -> {}                                        // already listening/thinking, or in a call
        }
    }

    @Volatile private var restartWhenIdle = false
    /** telerouter sent audio since this turn began. Its flush at the start of a turn (meant for its own queued audio)
     *  must not cut the phone's start chime. */
    @Volatile private var serverAudio = false

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
        session?.cancel(); session = null
        stopReading()
        pendingText = null; pendingAudio = false
        mic.detach()
        sendMic = false
        connected = false
        transport?.close()
        transport = null
        if (_state.value.phase != Phase.ENDED) {
            _state.update { it.copy(phase = Phase.ENDED, partial = null) }
            out.release()
            voice?.release()
            onEnded()
        }
    }

    private fun stopReading() {
        listenAfterReading = false
        if (reading > 0) {
            reading = 0
            voice?.stop()
        }
    }

    private fun fail(message: String, needsApp: Boolean = false) {
        mic.detach()
        session?.cancel(); session = null
        stopReading()
        pendingText = null; pendingAudio = false
        connected = false
        transport?.close()
        transport = null
        out.chime(false)
        _state.update { it.copy(phase = Phase.ERROR, error = message, needsApp = needsApp, partial = null) }
        linger?.cancel()
        linger = scope.launch { delay(ERROR_LINGER_MS); end() }
    }

    /** Nothing more is going on: wait a moment for a tap, then hang up. */
    private fun quiet() {
        if (session != null || reading > 0 || pendingText != null || pendingAudio) return
        if (server != State.IDLE && connected) return
        mic.detach()
        _state.update { it.copy(phase = Phase.IDLE) }
        linger?.cancel()
        linger = scope.launch { delay(LINGER_MS); end() }
    }

    private val listener = object : Connection.Listener {
        override fun onEvent(event: Protocol.Event) {
            when (event) {
                is Protocol.Event.StateChanged -> scope.launch { onServerState(event.state) }
                is Protocol.Event.Transcript -> scope.launch { onTranscript(event) }
                is Protocol.Event.Config -> scope.launch {
                    serverTakesText = Protocol.FEATURE_UTTERANCE in event.features
                    onLanguage(event.language)
                    flushPending()
                }
                Protocol.Event.Flush -> if (serverAudio) out.flush()
                else -> {}
            }
        }

        override fun onAudio(pcm: ByteArray) {
            serverAudio = true
            out.play(pcm)
        }

        override fun onClosed(error: TelerouterError?) {
            if (!connected) return
            connected = false
            scope.launch { if (error != null) fail(error.message ?: "the connection was lost", error.unauthorized) else end() }
        }
    }

    private fun onTranscript(t: Protocol.Event.Transcript) {
        if (t.fromUser && t.text == heardHere) {               // already shown: the phone heard it itself
            heardHere = null
            return
        }
        _state.update { it.copy(messages = (it.messages + Message(t.fromUser, t.text)).takeLast(40)) }
        val v = voice
        if (t.fromUser || v == null) return
        val say = t.say.ifBlank { t.text }
        reading++
        linger?.cancel()
        _state.update { it.copy(phase = Phase.SPEAKING) }
        v.speak(say) { scope.launch { readDone() } }
    }

    private fun readDone() {
        if (reading == 0) return
        if (--reading > 0) return
        if (listenAfterReading || (server == State.LISTENING && !audioTurn)) {
            listenAfterReading = false
            listen()
            return
        }
        when (server) {
            State.THINKING -> _state.update { it.copy(phase = Phase.THINKING) }
            else -> quiet()
        }
    }

    private fun onServerState(s: State) {
        val before = server
        server = s
        if (s != State.LISTENING) audioTurn = false
        sendMic = s == State.CALL || s == State.RINGING || (s == State.LISTENING && (audioTurn || !onDevice))
        if (s == State.CALL || s == State.RINGING) {
            session?.cancel(); session = null
            stopReading()
            mic.attach(::onMic)
        }
        // what the phone itself does (listening, reading) wins over the server's momentary states
        val busyHere = session != null || reading > 0
        val phase = when (s) {
            State.LISTENING -> Phase.LISTENING
            State.THINKING -> Phase.THINKING
            State.SPEAKING -> Phase.SPEAKING
            State.CALL, State.RINGING -> Phase.CALL
            State.IDLE -> Phase.IDLE
            State.OTHER -> _state.value.phase
        }
        if (!busyHere || phase == Phase.CALL) _state.update { it.copy(phase = phase) }
        linger?.cancel()
        if (pendingText != null || pendingAudio) {
            flushPending()
            return
        }
        if (s == State.LISTENING && !audioTurn && onDevice) {        // telerouter waits for the phone's words
            if (reading > 0) listenAfterReading = true
            else if (session == null) listen()
            return
        }
        if (s == State.LISTENING && !audioTurn && recognizer != null && session == null) {
            transport?.utterance("")                                  // the recogniser is off: nothing to give
            return
        }
        if (s == State.IDLE && restartWhenIdle) {
            restartWhenIdle = false
            scope.launch { start() }                          // the interrupted turn is over: listen now
            return
        }
        if (s == State.IDLE && before != State.IDLE) {
            if (busyHere) return
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
