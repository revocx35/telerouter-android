package io.github.revocx35.telerouter.audio

import android.annotation.SuppressLint
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.sin

const val RATE = 16000
private const val TAG = "telerouter.audio"

/**
 * One microphone for the whole app: the wake word and the conversation both listen to it, so the words said
 * right after "Hey Jarvis" are not lost while the conversation starts (Android lets only one recorder run
 * before API 29 anyway). Recording runs while at least one listener is attached, in 10 ms chunks of 16 kHz
 * mono PCM16. Listeners run on the recording thread and must be quick.
 */
object Mic {
    fun interface Listener {
        fun onAudio(samples: ShortArray, count: Int)
    }

    private val listeners = CopyOnWriteArrayList<Listener>()
    @Volatile private var thread: Thread? = null

    @Synchronized
    fun add(l: Listener) {
        listeners.addIfAbsent(l)
        if (thread == null) {
            thread = Thread(::run, "telerouter-mic").apply { priority = Thread.MAX_PRIORITY; start() }
        }
    }

    @Synchronized
    fun remove(l: Listener) {
        listeners.remove(l)
    }

    @SuppressLint("MissingPermission")    // callers check RECORD_AUDIO first
    private fun run() {
        var rec: AudioRecord? = null
        try {
            var rate = RATE
            rec = record(RATE) ?: record(48000).also { rate = 48000 }
            if (rec == null) {
                Log.e(TAG, "no microphone")
                return
            }
            val step = rate / 100                 // 10 ms
            val buf = ShortArray(step)
            val out = ShortArray(RATE / 100)
            rec.startRecording()
            while (listeners.isNotEmpty()) {
                val n = rec.read(buf, 0, step)
                if (n <= 0) {
                    if (n < 0) Log.w(TAG, "microphone read failed: $n")
                    continue
                }
                val samples: ShortArray
                val count: Int
                if (rate == RATE) {
                    samples = buf; count = n
                } else {
                    count = downsample3(buf, n, out); samples = out
                }
                for (l in listeners) l.onAudio(samples, count)
            }
        } catch (e: Exception) {
            Log.e(TAG, "microphone failed", e)
        } finally {
            try { rec?.stop() } catch (_: Exception) {}
            rec?.release()
            synchronized(this) {
                thread = null
                if (listeners.isNotEmpty()) add(listeners.first())     // someone attached while we stopped
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun record(rate: Int): AudioRecord? {
        val min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) return null
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT, maxOf(min, rate / 5 * 2))
        if (r.state != AudioRecord.STATE_INITIALIZED) {
            r.release(); return null
        }
        return r
    }
}

/** 48 kHz -> 16 kHz by averaging each group of three samples (a crude but adequate low-pass for speech). */
fun downsample3(src: ShortArray, n: Int, dst: ShortArray): Int {
    val m = minOf(n / 3, dst.size)
    for (i in 0 until m) dst[i] = ((src[3 * i] + src[3 * i + 1] + src[3 * i + 2]) / 3).toShort()
    return m
}

fun ShortArray.toLeBytes(count: Int = size): ByteArray {
    val b = ByteArray(count * 2)
    for (i in 0 until count) {
        val v = this[i].toInt()
        b[2 * i] = v.toByte(); b[2 * i + 1] = (v shr 8).toByte()
    }
    return b
}

/** Plays what telerouter sends (16 kHz mono PCM16) as the assistant's voice. Writes happen on one thread. */
class Player {
    private val track = AudioTrack.Builder()
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
        .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setBufferSizeInBytes(RATE * 2)                      // 1 s; the server sends at most ~250 ms ahead
        .setTransferMode(AudioTrack.MODE_STREAM)
        .build()
    private val writer = Executors.newSingleThreadExecutor { Thread(it, "telerouter-play") }
    @Volatile private var generation = 0

    init { track.play() }

    fun play(pcm: ByteArray) {
        val g = generation
        writer.execute { if (g == generation) track.write(pcm, 0, pcm.size) }
    }

    /** Drop everything queued (barge-in, a call ended). */
    fun flush() {
        generation++
        writer.execute {
            track.pause(); track.flush(); track.play()
        }
    }

    fun release() {
        generation++
        writer.execute { track.stop(); track.release() }
        writer.shutdown()
    }
}

/** The phone's own sounds: a rising two-tone "I'm listening" and a falling "something went wrong". */
object Chime {
    fun listening(): ByteArray = tones(listOf(880.0 to 0.07, 1320.0 to 0.09))
    fun error(): ByteArray = tones(listOf(660.0 to 0.12, 440.0 to 0.16))

    fun tones(parts: List<Pair<Double, Double>>, volume: Double = 0.35): ByteArray {
        val samples = ArrayList<Short>()
        for ((freq, secs) in parts) {
            val n = (RATE * secs).toInt()
            val fade = RATE / 200                                  // 5 ms ramps, no clicks
            for (i in 0 until n) {
                val env = minOf(1.0, i / fade.toDouble(), (n - i) / fade.toDouble())
                samples.add((sin(2 * PI * freq * i / RATE) * env * volume * Short.MAX_VALUE).toInt().toShort())
            }
        }
        return samples.toShortArray().toLeBytes()
    }
}
