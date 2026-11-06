package io.github.revocx35.telerouter

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.revocx35.telerouter.assist.DeviceRecognizer
import io.github.revocx35.telerouter.assist.DeviceVoice
import io.github.revocx35.telerouter.assist.Recognizer
import io.github.revocx35.telerouter.assist.checkLanguagePack
import io.github.revocx35.telerouter.assist.onDeviceRecognition
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The phone's own speech services as the app uses them: the on-device recogniser hearing audio through the pipe
 * (10 ms chunks, like the shared microphone), and the text-to-speech voice. Skipped where the phone (or emulator
 * image) has no on-device recogniser.
 */
@RunWith(AndroidJUnit4::class)
class DeviceSpeechTest {
    private val app = InstrumentationRegistry.getInstrumentation().targetContext
    private val test = InstrumentationRegistry.getInstrumentation().context

    @Test fun theOnDeviceRecogniserHearsTheAppsAudio() {
        assumeTrue("no on-device speech recognition here", onDeviceRecognition(app))
        val pack = CompletableFuture<String?>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync { checkLanguagePack(app, "en") { pack.complete(it) } }
        val tag = pack.get(15, TimeUnit.SECONDS)
        assumeTrue("the English on-device language pack is not installed (a download was requested)", tag != null)
        val got = CompletableFuture<String>()
        val partials = mutableListOf<String>()
        val s = DeviceRecognizer(app).start(tag!!, object : Recognizer.Listener {
            override fun partial(text: String) { partials += text }
            override fun result(text: String) { got.complete(text) }
            override fun failed() { got.completeExceptionally(IllegalStateException("recogniser failed")) }
        })
        val audio = test.assets.open("hey_jarvis_light.raw").readBytes() + ByteArray(16000 * 2 * 3)
        var i = 0
        while (i < audio.size && !got.isDone) {                    // in real time, as the microphone delivers it
            s.feed(audio.copyOfRange(i, minOf(i + 320, audio.size)))
            i += 320
            Thread.sleep(10)
        }
        val text = got.get(20, TimeUnit.SECONDS)
        assertTrue("heard: $text", text.contains("light", ignoreCase = true))
    }

    @Test fun theVoiceReportsWhenItHasRead() {
        val done = CompletableFuture<Unit>()
        val v = DeviceVoice(app) { "en" }
        v.speak("The light is on.") { done.complete(Unit) }
        done.get(20, TimeUnit.SECONDS)
        v.release()
    }
}
