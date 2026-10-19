package io.github.revocx35.telerouter.wake

import android.content.Context
import android.util.Log
import io.github.revocx35.telerouter.audio.Mic
import io.github.revocx35.telerouter.microwakeword.MicroWakeWord
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import org.json.JSONObject

/**
 * "Hey Jarvis" on the phone (microWakeWord, the model and approach of Home Assistant's companion app): it only
 * reads the shared microphone; nothing is sent anywhere until it fires. After a detection it stays quiet for 2 s.
 */
class WakeWord(private val context: Context, private val onDetected: () -> Unit) {
    private var engine: MicroWakeWord? = null
    private var cooldown = 0
    /** Detections are dropped (the model keeps running, so it is ready the moment this turns false again:
     *  a reset would make it deaf for 3 s). Set while a conversation listens, thinks or is in a call. */
    @Volatile var paused = false
    @Volatile var running = false
        private set

    private val lock = Any()                                 // the engine is used on the mic thread, closed elsewhere

    private val listener = Mic.Listener { samples, count ->
        val fired = synchronized(lock) {
            val e = engine ?: return@Listener
            if (cooldown > 0) {
                cooldown--; return@Listener
            }
            e.processAudio(if (count == samples.size) samples else samples.copyOf(count)).also {
                if (it) {
                    e.reset()
                    cooldown = 200                           // 10 ms chunks: 2 s
                }
            }
        }
        if (fired && !paused) {
            Log.i(TAG, "wake word detected")
            onDetected()
        }
    }

    @Synchronized
    fun start() {
        if (running) return
        val cfg = JSONObject(context.assets.open("wakeword/hey_jarvis.json").bufferedReader().readText())
        val micro = cfg.getJSONObject("micro")
        val buffer = context.assets.openFd("wakeword/" + cfg.getString("model")).use { fd ->
            fd.createInputStream().use { it.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
        }.order(ByteOrder.nativeOrder())
        engine = MicroWakeWord(buffer, micro.getInt("feature_step_size"), micro.getDouble("probability_cutoff").toFloat(),
            micro.getInt("sliding_window_size"))
        running = true
        Mic.add(listener)
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        Mic.remove(listener)
        synchronized(lock) {
            engine?.close()
            engine = null
        }
    }

    /** After a conversation: listen again at once. */
    fun resume() {
        paused = false
    }

    private companion object { const val TAG = "telerouter.wake" }
}
