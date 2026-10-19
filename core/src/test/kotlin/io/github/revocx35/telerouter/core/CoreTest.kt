package io.github.revocx35.telerouter.core

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import okio.ByteString
import okio.ByteString.Companion.toByteString
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class SetupLinkTest {
    private val ca = "ab".repeat(32)

    @Test fun parsesTheLinkTelerouterShows() {
        val l = SetupLink.parse("telerouter://setup?v=1&server=https%3A%2F%2Ftr.example.org&lan=https%3A%2F%2F192.168.1.5%3A8443&ca=$ca&code=ABCD-EFGH&public=https%3A%2F%2Fjarvis.example.org")
        assertEquals(SetupLink("https://tr.example.org", ca, "ABCD-EFGH", "https://jarvis.example.org", "https://192.168.1.5:8443"), l)
        val old = SetupLink.parse("telerouter://setup?v=1&server=https://h:8443&ca=$ca&code=ABCD-EFGH")   // telerouter 0.9.0
        assertNull(old.publicUrl)
        assertEquals("https://h:8443", old.lanUrl)
    }

    @Test fun refusesAnythingElse() {
        for (bad in listOf(
            "https://setup?v=1&server=https://h&ca=$ca&code=ABCD",                 // wrong scheme
            "telerouter://setup?v=2&server=https://h&ca=$ca&code=ABCD",            // newer version
            "telerouter://setup?v=1&server=http://h:8443&ca=$ca&code=ABCD",        // plain http
            "telerouter://setup?v=1&server=https://u:p@h&ca=$ca&code=ABCD",        // user info
            "telerouter://setup?v=1&server=https://h/x&ca=$ca&code=ABCD",          // a path
            "telerouter://setup?v=1&server=https://h&ca=abcd&code=ABCD",           // short fingerprint
            "telerouter://setup?v=1&server=https://h&ca=$ca&code=AB%20CD",         // bad code
            "telerouter://setup?v=1&server=https://h&ca=$ca&code=ABCD&public=http://x",
            "not a link",
        )) {
            try {
                SetupLink.parse(bad); fail(bad)
            } catch (e: IllegalArgumentException) {
                assertTrue(e.message!!.isNotEmpty())
            }
        }
    }
}

class ProtocolTest {
    @Test fun parsesServerMessages() {
        assertEquals(Protocol.Event.StateChanged(Protocol.State.LISTENING), Protocol.parse("""{"type":"state","state":"listening"}"""))
        assertEquals(Protocol.Event.StateChanged(Protocol.State.OTHER), Protocol.parse("""{"type":"state","state":"new"}"""))
        assertEquals(Protocol.Event.Transcript(true, "Turn on the light."), Protocol.parse("""{"type":"transcript","role":"user","text":"Turn on the light."}"""))
        assertEquals(Protocol.Event.Transcript(false, "Done."), Protocol.parse("""{"type":"transcript","role":"assistant","text":"Done."}"""))
        assertEquals(Protocol.Event.Config(100, "0.9.0"), Protocol.parse("""{"type":"config","volume":100,"server_version":"0.9.0"}"""))
        assertEquals(Protocol.Event.Flush, Protocol.parse("""{"type":"flush"}"""))
        assertNull(Protocol.parse("""{"type":"something_new"}"""))
        assertNull(Protocol.parse("garbage"))
    }

    @Test fun clientMessages() {
        assertEquals("""{"type":"button","action":"talk","cue":false}""", Protocol.talk())
        assertTrue(Protocol.hello("0.1.0", false).contains(""""transcript":true"""))
        assertTrue(Protocol.hello("0.1.0", false).contains(""""platform":"android""""))
    }
}

/** A telerouter-like TLS server: its own CA signs the server certificate for 127.0.0.1, the chain includes the CA. */
class TlsTest {
    private val ca = HeldCertificate.Builder().certificateAuthority(0).commonName("telerouter local CA").build()
    private val serverCert = HeldCertificate.Builder().addSubjectAlternativeName("127.0.0.1").signedBy(ca).build()
    private val otherCa = HeldCertificate.Builder().certificateAuthority(0).build()
    private lateinit var server: MockWebServer

    @Before fun start() {
        server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(serverCert, ca.certificate).build().sslSocketFactory())
        server.start()
    }

    @After fun stop() = server.close()

    private fun base() = "https://127.0.0.1:${server.port}"

    @Test fun pinsTheCaNamedByTheLinkAndNothingElse() {
        val pem = Trust.fetchPinnedCa(base(), Trust.sha256Hex(ca.certificate.encoded))
        assertEquals(Trust.sha256Hex(ca.certificate.encoded), Trust.sha256Hex(Trust.parsePem(pem).encoded))
        try {
            Trust.fetchPinnedCa(base(), Trust.sha256Hex(otherCa.certificate.encoded)); fail()
        } catch (e: Trust.TrustError) {
            assertTrue(e.message!!.contains("does not match"))
        }
    }

    private val sha get() = Trust.sha256Hex(ca.certificate.encoded)
    private fun enrolled() = MockResponse.Builder().body("""{"client_id":"0000000f","name":"Pixel","token":"trc_abc"}""").build()
    private fun dead() = "https://127.0.0.1:" + java.net.ServerSocket(0).use { it.localPort }

    @Test fun setUpAtHomeWithoutAnInternetAddress() {
        server.enqueue(enrolled())
        val cfg = Enroll.setup(SetupLink(base(), sha, "ABCD-EFGH", null))
        assertEquals("trc_abc", cfg.token)
        assertNull(cfg.publicUrl)
        assertEquals(sha, Trust.sha256Hex(Trust.parsePem(cfg.caPem!!).encoded))   // pinned during setup
        val req = server.takeRequest()
        assertEquals("/api/client/enroll", req.url.encodedPath)
        assertTrue(req.body!!.utf8().contains(""""code":"ABCD-EFGH""""))
    }

    @Test fun setUpFromAwayGoesThroughTheInternetAddress() {
        server.enqueue(enrolled())
        // the LAN address can't be reached from here; "public" stands in for a server with a public certificate
        val link = SetupLink(base(), sha, "ABCD-EFGH", base(), lanUrl = dead())
        val cfg = Enroll.setup(link, publicClient = Trust.pinnedClient(Trust.pem(ca.certificate)))
        assertEquals(base(), cfg.publicUrl)
        assertNull(cfg.caPem)                                                   // pinned on the first visit home
        assertEquals("trc_abc", cfg.token)
    }

    @Test fun aWrongCertificateAtTheLanAddressIsNeverPinned() {
        server.enqueue(enrolled())
        val link = SetupLink(base(), Trust.sha256Hex(otherCa.certificate.encoded), "ABCD-EFGH", base())
        val cfg = Enroll.setup(link, publicClient = Trust.pinnedClient(Trust.pem(ca.certificate)))
        assertNull(cfg.caPem)
    }

    @Test fun thePinnedClientTrustsOnlyThatCa() {
        try {
            Enroll.enrollAt(base(), "ABCD-EFGH", Trust.pinnedClient(Trust.pem(otherCa.certificate))); fail()
        } catch (e: javax.net.ssl.SSLException) {
        }
    }

    @Test fun wrongCodeIsAClearError() {
        server.enqueue(MockResponse.Builder().code(401).body("""{"detail":"invalid or expired code"}""").build())
        try {
            Enroll.setup(SetupLink(base(), sha, "ABCD-EFGH", null)); fail()
        } catch (e: TelerouterError) {
            assertTrue(e.unauthorized && e.message!!.contains("expired"))
        }
    }

    @Test fun connectionSpeaksTheProtocolAndFallsBackToTheLanAddress() {
        val fromPhone = LinkedBlockingQueue<Any>()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"ping"}""")
                webSocket.send("""{"type":"transcript","role":"assistant","text":"Hi"}""")
                webSocket.send(byteArrayOf(1, 2, 3, 4).toByteString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) { fromPhone.add(text) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { fromPhone.add(bytes) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }).build())
        val events = LinkedBlockingQueue<Any>()
        // the internet address is down: the phone, at home, falls back to the LAN address and pins its CA there
        var pinned: String? = null
        val cfg = ServerConfig(dead(), base(), sha, null, "trc_abc", "Pixel")
        val conn = Connection(cfg, "0.1.0", object : Connection.Listener {
            override fun onOpen(viaInternet: Boolean) { events.add("open:$viaInternet") }
            override fun onEvent(event: Protocol.Event) { events.add(event) }
            override fun onAudio(pcm: ByteArray) { events.add(pcm.toList()) }
        }, publicClient = Trust.publicClient(), onCaPinned = { pinned = it })
        conn.connect()
        assertFalse(conn.viaInternet)
        assertEquals("open:false", events.poll(5, TimeUnit.SECONDS))
        assertEquals(sha, Trust.sha256Hex(Trust.parsePem(pinned!!).encoded))
        val hello = fromPhone.poll(5, TimeUnit.SECONDS) as String
        assertTrue(hello.contains(""""type":"hello"""") && hello.contains(""""transcript":true"""))
        assertEquals(Protocol.Event.Ping, events.poll(5, TimeUnit.SECONDS))
        assertEquals("""{"type":"pong"}""", fromPhone.poll(5, TimeUnit.SECONDS))
        assertEquals(Protocol.Event.Transcript(false, "Hi"), events.poll(5, TimeUnit.SECONDS))
        assertEquals(listOf<Byte>(1, 2, 3, 4), events.poll(5, TimeUnit.SECONDS))
        assertTrue(conn.talk())
        assertEquals(Protocol.talk(), fromPhone.poll(5, TimeUnit.SECONDS))
        assertTrue(conn.sendAudio(ByteArray(640) { 7 }))
        assertEquals(640, (fromPhone.poll(5, TimeUnit.SECONDS) as ByteString).size)
        assertEquals("Bearer trc_abc", server.takeRequest().headers["Authorization"])
        conn.close()
        assertFalse(conn.isOpen)
    }

    @Test fun theInternetAddressComesFirst() {
        val opened = LinkedBlockingQueue<Boolean>()
        server.enqueue(MockResponse.Builder().webSocketUpgrade(object : WebSocketListener() {
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }).build())
        val cfg = ServerConfig(base(), dead(), sha, Trust.pem(ca.certificate), "trc_abc", "Pixel")
        val conn = Connection(cfg, "0.1.0", object : Connection.Listener {
            override fun onOpen(viaInternet: Boolean) { opened.add(viaInternet) }
        }, publicClient = Trust.pinnedClient(Trust.pem(ca.certificate)))
        conn.connect()
        assertEquals(true, opened.poll(5, TimeUnit.SECONDS))
        conn.close()
    }
}
