package io.github.revocx35.telerouter.core

import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Against a real telerouter with real Whisper/Piper (see README "Live test"): set TR_IT_LINK to a fresh setup
 * link from Speakers → Add device and TR_IT_WAV to a 16 kHz mono WAV of someone speaking.
 *
 * It does what the phone does after "Hey Jarvis": pin the CA from the link, enroll, connect, send talk
 * (cue=false) and the speech right behind it, and expects the transcript of what was said, an answer as text
 * and as audio, and the speaker going back to idle.
 */
class LiveServerTest {
    @Test fun aTurnLikeThePhoneDoesIt() {
        val linkText = System.getenv("TR_IT_LINK")
        val wav = System.getenv("TR_IT_WAV")
        assumeTrue("TR_IT_LINK / TR_IT_WAV not set", !linkText.isNullOrEmpty() && !wav.isNullOrEmpty())
        val link = SetupLink.parse(linkText!!)
        val ca = Trust.fetchPinnedCa(link.server, link.caSha256)
        val cfg = Enroll.enroll(link, ca)
        assertTrue(cfg.token.startsWith("trc_"))
        assertEquals(link.publicUrl, cfg.publicUrl)

        val events = LinkedBlockingQueue<Any>()
        var audioBytes = 0
        val conn = Connection(cfg, "live-test", object : Connection.Listener {
            override fun onEvent(event: Protocol.Event) { events.add(event) }
            override fun onAudio(pcm: ByteArray) { synchronized(this) { audioBytes += pcm.size } }
        })
        conn.connect()
        assertTrue(!conn.viaInternet)
        assertTrue(events.poll(5, TimeUnit.SECONDS) is Protocol.Event.Config)

        // talk, then at once the speech (as the phone sends its pre-roll), then silence so the endpointer ends
        val pcm = File(wav!!).readBytes().let { it.copyOfRange(44, it.size) } + ByteArray(32000 * 2)
        assertTrue(conn.talk())
        var i = 0
        while (i < pcm.size) {
            conn.sendAudio(pcm.copyOfRange(i, minOf(i + 640, pcm.size)))
            i += 640
            Thread.sleep(8)                                       // ~2.5x real time, under the server's 3x cap
        }
        val seen = mutableListOf<Any>()
        val deadline = System.currentTimeMillis() + 60_000
        while (System.currentTimeMillis() < deadline) {
            val e = events.poll(1, TimeUnit.SECONDS) ?: continue
            seen += e
            if (e == Protocol.Event.StateChanged(Protocol.State.IDLE) && seen.any { it is Protocol.Event.Transcript && !it.fromUser }) break
        }
        println("events: $seen, audio bytes: $audioBytes")
        val states = seen.filterIsInstance<Protocol.Event.StateChanged>().map { it.state }
        assertEquals(Protocol.State.LISTENING, states.first())
        assertTrue(Protocol.State.THINKING in states && states.last() == Protocol.State.IDLE)
        val heard = seen.filterIsInstance<Protocol.Event.Transcript>().first { it.fromUser }.text
        assertTrue("heard: $heard", heard.split(" ").size >= 2)
        val answer = seen.filterIsInstance<Protocol.Event.Transcript>().first { !it.fromUser }.text
        assertTrue(answer.isNotBlank())
        assertTrue("the answer was spoken too", audioBytes > 16000)
        conn.close()
    }
}
