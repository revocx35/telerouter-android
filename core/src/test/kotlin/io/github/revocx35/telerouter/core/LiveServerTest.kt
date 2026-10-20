package io.github.revocx35.telerouter.core

import java.io.File
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.decodeCertificatePem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Against a real telerouter with real Whisper/Piper (see README "Live test"): TR_IT_URL (its address), TR_IT_USER and
 * TR_IT_PASSWORD (an admin login), TR_IT_WAV (a 16 kHz mono WAV of someone speaking), and for a server with its own
 * certificate TR_IT_CA (a PEM file to trust instead of the system's CAs).
 *
 * It does what the app does: sign in (here with the password API instead of the WebView), add the phone as a device
 * with that session, connect, send talk (cue=false) with the speech right behind it, and expects the transcript, an
 * answer as text and as audio, and the speaker going back to idle. The device is removed again at the end.
 */
class LiveServerTest {
    @Test fun aTurnLikeThePhoneDoesIt() {
        val url = System.getenv("TR_IT_URL")
        val wav = System.getenv("TR_IT_WAV")
        assumeTrue("TR_IT_URL / TR_IT_WAV not set", !url.isNullOrEmpty() && !wav.isNullOrEmpty())
        val base = ServerUrl.normalize(url!!)
        val custom = System.getenv("TR_IT_CA")?.let { File(it).readText().decodeCertificatePem() }?.let {
            val h = HandshakeCertificates.Builder().addTrustedCertificate(it).build()
            Trust.Custom(h.sslSocketFactory(), h.trustManager)
        }
        val cookies = MemoryCookies()
        val client = Trust.client(StoreCookieJar(cookies), custom = custom)
        val login = buildJsonObject { put("username", System.getenv("TR_IT_USER")); put("password", System.getenv("TR_IT_PASSWORD")) }
        client.newCall(Request.Builder().url("$base/api/login").post(login.toString().toRequestBody("application/json".toMediaType())).build())
            .execute().use { assertEquals(200, it.code) }
        val api = Api(base, client, Trust.client(StoreCookieJar(cookies), true, custom))
        val s = api.session()
        assertTrue(s.loggedIn)
        val cfg = api.registerDevice("live-test phone", s.csrf)
        assertTrue(cfg.token.startsWith("trc_"))

        val events = LinkedBlockingQueue<Any>()
        var audioBytes = 0
        val conn = Connection(cfg, "live-test", object : Connection.Listener {
            override fun onEvent(event: Protocol.Event) { events.add(event) }
            override fun onAudio(pcm: ByteArray) { synchronized(this) { audioBytes += pcm.size } }
        }, client, api::renewSignIn)
        try {
            conn.connect()
            val config = events.poll(5, TimeUnit.SECONDS) as Protocol.Event.Config
            assertEquals(cfg.clientId, config.clientId)

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
        } finally {
            conn.close()
            api.signOut(cfg.clientId)
        }
    }
}
