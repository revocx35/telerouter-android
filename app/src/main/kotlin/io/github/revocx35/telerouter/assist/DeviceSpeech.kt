package io.github.revocx35.telerouter.assist

import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.annotation.RequiresApi
import io.github.revocx35.telerouter.audio.RATE
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

private const val TAG = "telerouter.speech"

/** BCP-47 tag for telerouter's language ("en", "" = auto): the phone's own locale when it is that language. */
fun speechLocale(language: String, device: Locale = Locale.getDefault()): Locale {
    val lang = language.trim().lowercase()
    if (lang.isEmpty() || lang == device.language) return device
    return Locale.forLanguageTag(lang)
}

/**
 * The installed on-device language to recognise telerouter's [language] with: the phone's own variant when it is
 * installed (en-US for "en" on an American phone), else any installed variant of that language; null = none.
 */
fun pickLanguagePack(language: String, installed: List<String>, device: Locale = Locale.getDefault()): String? {
    val want = speechLocale(language, device)
    installed.firstOrNull { it.equals(want.toLanguageTag(), ignoreCase = true) }?.let { return it }
    return installed.firstOrNull { Locale.forLanguageTag(it).language == want.language }
}

/**
 * Whether the on-device recogniser has telerouter's language installed. Without its language pack the recogniser
 * takes ~20 s to fail (seen on an API 37 emulator), far slower than Whisper, so the app uses it only once the pack
 * is there, and asks Android to download it otherwise. [done] gets the installed tag, or null. Main thread.
 */
@RequiresApi(33)
fun checkLanguagePack(context: Context, language: String, done: (String?) -> Unit) {
    val main = Handler(Looper.getMainLooper())
    val sr = try {
        SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
    } catch (e: Exception) {
        done(null); return
    }
    val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, speechLocale(language).toLanguageTag())
    var answered = false
    fun answer(tag: String?) {
        if (answered) return
        answered = true
        main.post { try { sr.destroy() } catch (_: Exception) {} }
        done(tag)
    }
    main.postDelayed({ answer(null) }, 10_000)
    try {
        sr.checkRecognitionSupport(intent, context.mainExecutor, object : android.speech.RecognitionSupportCallback {
            override fun onSupportResult(support: android.speech.RecognitionSupport) {
                val tag = pickLanguagePack(language, support.installedOnDeviceLanguages)
                if (tag == null) {
                    Log.i(TAG, "on-device language not installed yet: asking Android to download it")
                    try { sr.triggerModelDownload(intent) } catch (_: Exception) {}
                }
                answer(tag)
            }

            override fun onError(error: Int) {
                Log.w(TAG, "on-device language check failed: $error")
                answer(null)
            }
        })
    } catch (e: Exception) {
        answer(null)
    }
}

/** On-device recognition can hear the app's microphone (Android 13+, a recogniser that runs on the phone). */
fun onDeviceRecognition(context: Context): Boolean =
    Build.VERSION.SDK_INT >= 33 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

/**
 * Android's **on-device** speech recognition (the one Google Assistant uses on the phone; never the network
 * recogniser, so the audio stays on the phone). It hears the app's shared microphone through a pipe
 * (`EXTRA_AUDIO_SOURCE`, Android 13+), so it gets the words said right after "Hey Jarvis" while it starts, and
 * doesn't fight the wake word for the microphone. Callbacks run on the main thread.
 */
@RequiresApi(33)
class DeviceRecognizer(private val context: Context) : Recognizer {
    private val main = Handler(Looper.getMainLooper())

    override fun start(language: String, listener: Recognizer.Listener): Recognizer.Session = PipeSession(language, listener)

    private inner class PipeSession(language: String, private val l: Recognizer.Listener) : Recognizer.Session, RecognitionListener {
        private val pipe = ParcelFileDescriptor.createPipe()
        private val sink = ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])
        private val writer = Executors.newSingleThreadExecutor { Thread(it, "telerouter-stt") }
        @Volatile private var done = false
        private var sr: SpeechRecognizer? = null
        private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)                 // an installed tag (pickLanguagePack)
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            .putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, RATE)
            // hints only (Conversation ends the request itself once the words stop changing)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)

        private val giveUp = Runnable { sr?.stopListening() }

        init {
            main.post {
                if (done) return@post
                try {
                    sr = SpeechRecognizer.createOnDeviceSpeechRecognizer(context).also {
                        it.setRecognitionListener(this)
                        it.startListening(intent)
                    }
                    main.postDelayed(giveUp, MAX_LISTEN_MS)
                } catch (e: Exception) {
                    Log.w(TAG, "on-device recognition didn't start: ${e.javaClass.simpleName}")
                    finish { l.failed() }
                }
            }
        }

        override fun feed(pcm: ByteArray) {
            if (done) return
            writer.execute {
                try {
                    sink.write(pcm)
                } catch (_: IOException) {}                    // the recogniser closed its end: it is done
            }
        }

        /** Stop listening: the recogniser reports what it has (onResults) right away. */
        override fun finish() {
            if (done) return
            main.post { if (!done) sr?.stopListening() }
            main.postDelayed(endOfAudio, 1500)
        }

        /** Still no result after stopListening: the end of the piped audio makes it finish. */
        private val endOfAudio = Runnable {
            if (!done) writer.execute { try { sink.close() } catch (_: IOException) {} }
        }

        override fun cancel() = finish(null)

        private fun finish(report: (() -> Unit)?) {
            if (done) return
            done = true
            main.removeCallbacks(giveUp)
            main.removeCallbacks(endOfAudio)
            main.post {
                sr?.let { try { it.cancel(); it.destroy() } catch (_: Exception) {} }
                sr = null
            }
            writer.execute {
                try { sink.close() } catch (_: IOException) {}
                try { pipe[0].close() } catch (_: IOException) {}
            }
            writer.shutdown()
            report?.invoke()
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (done) return
            first(partialResults)?.let(l::partial)
        }

        override fun onResults(results: Bundle?) {
            val text = first(results) ?: ""
            finish { l.result(text) }
        }

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> finish { l.result("") }
                else -> {
                    Log.w(TAG, "on-device recognition error $error")
                    if (error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE) {
                        // fetch the language for next time; Whisper hears this one
                        try { sr?.triggerModelDownload(intent) } catch (_: Exception) {}
                    }
                    finish { l.failed() }
                }
            }
        }

        private fun first(b: Bundle?) = b?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    companion object {
        /** The longest one request may take (the recogniser ends earlier on its own after a pause). */
        const val MAX_LISTEN_MS = 30_000L
    }
}

/**
 * The phone's text-to-speech voice for answers, at the media volume like telerouter's voice was. It starts when
 * the conversation does, so it is ready by the time the first answer comes.
 */
class DeviceVoice(context: Context, private val language: () -> String) : Voice {
    private val main = Handler(Looper.getMainLooper())
    private val done = ConcurrentHashMap<String, () -> Unit>()
    private val waiting = mutableListOf<Pair<String, () -> Unit>>()
    @Volatile private var ready: Boolean? = null           // null = still starting, false = no TTS engine
    private var n = 0
    private val tts: TextToSpeech = TextToSpeech(context.applicationContext) { status -> main.post { started(status) } }

    private fun started(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready == true) {
            tts.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            val loc = speechLocale(language())
            if (tts.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) tts.language = loc
            tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onDone(utteranceId: String?) = finished(utteranceId)
                @Deprecated("Deprecated in Java")
                override fun onError(utteranceId: String?) = finished(utteranceId)
                override fun onStop(utteranceId: String?, interrupted: Boolean) = finished(utteranceId)
            })
        } else {
            Log.w(TAG, "no text-to-speech engine")
        }
        val queued = waiting.toList()
        waiting.clear()
        for ((text, cb) in queued) speak(text, cb)
    }

    private fun finished(id: String?) {
        val cb = id?.let { done.remove(it) } ?: return
        main.post(cb)
    }

    override fun speak(text: String, done: () -> Unit) = main.post {
        when (ready) {
            null -> waiting += text to done
            false -> done()                                  // text only, then
            true -> {
                val id = "tr-${n++}"
                this.done[id] = done
                for (part in chunks(text, TextToSpeech.getMaxSpeechInputLength())) {
                    tts.speak(part.second, TextToSpeech.QUEUE_ADD, null, if (part.first) id else "$id-part")
                }
            }
        }
    }.let {}

    override fun stop() {
        done.clear()
        main.post { waiting.clear(); if (ready == true) tts.stop() }
    }

    override fun release() {
        stop()
        main.post { tts.shutdown() }
    }

    /** Pieces the engine takes; the last one carries the id that reports "done". */
    private fun chunks(text: String, max: Int): List<Pair<Boolean, String>> {
        val parts = text.chunked(maxOf(1, max - 1))
        return parts.mapIndexed { i, p -> (i == parts.lastIndex) to p }
    }
}
