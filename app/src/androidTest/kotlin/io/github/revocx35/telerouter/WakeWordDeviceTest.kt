package io.github.revocx35.telerouter

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.revocx35.telerouter.microwakeword.MicroWakeWord
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The native wake word engine with the app's "Hey Jarvis" model, fed like the microphone feeds it (10 ms chunks of
 * 16 kHz PCM16). The recordings are Piper saying "Hey Jarvis" and a sentence without it (test assets).
 */
@RunWith(AndroidJUnit4::class)
class WakeWordDeviceTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val test = InstrumentationRegistry.getInstrumentation().context

    private fun engine(): MicroWakeWord {
        val cfg = JSONObject(app.assets.open("wakeword/hey_jarvis.json").bufferedReader().readText()).getJSONObject("micro")
        val model = app.assets.openFd("wakeword/hey_jarvis.tflite").use { fd ->
            fd.createInputStream().use { it.channel.map(FileChannel.MapMode.READ_ONLY, fd.startOffset, fd.declaredLength) }
        }.order(ByteOrder.nativeOrder())
        return MicroWakeWord(model, cfg.getInt("feature_step_size"), cfg.getDouble("probability_cutoff").toFloat(),
            cfg.getInt("sliding_window_size"))
    }

    /** Detections (in seconds) when [asset] is played into the engine after 3 s of quiet: like ESPHome, the
     *  engine ignores the first 100 inference slices (3 s) after it starts or is reset. */
    private fun detections(asset: String): List<Double> {
        val raw = ByteArray(16000 * 2 * 3) + test.assets.open(asset).readBytes()
        val samples = ShortArray(raw.size / 2)
        ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(samples)
        val hits = mutableListOf<Double>()
        engine().use { e ->
            var i = 0
            while (i + 160 <= samples.size) {
                if (e.processAudio(samples.copyOfRange(i, i + 160))) {
                    hits += i / 16000.0
                    e.reset()
                }
                i += 160
            }
        }
        return hits
    }

    @Test fun heyJarvisIsDetected() {
        val hits = detections("hey_jarvis.raw")
        assertEquals("detections at $hits", 1, hits.size)
        assertTrue("near the end of the phrase: ${hits[0]} s", hits[0] in 4.2..5.5)   // 3 s + 1 s of quiet first
    }

    @Test fun heyJarvisAtTheStartOfASentence() = assertEquals(1, detections("hey_jarvis_light.raw").size)

    @Test fun otherSpeechIsNot() = assertEquals(emptyList<Double>(), detections("no_wake.raw"))

    @Test fun nothingInTheFirstThreeSeconds() {
        val raw = test.assets.open("hey_jarvis.raw").readBytes()                     // 1 s quiet, then the phrase
        val samples = ShortArray(raw.size / 2).also { ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(it) }
        engine().use { e ->
            var hit = false
            for (i in 0 until samples.size - 160 step 160) hit = hit || e.processAudio(samples.copyOfRange(i, i + 160))
            assertTrue("detected during the warm-up", !hit)
        }
    }
}
