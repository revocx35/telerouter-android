package io.github.revocx35.telerouter.core

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
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

class ServerUrlTest {
    @Test fun acceptsWhatPeopleType() {
        assertEquals("https://telerouter.example.com", ServerUrl.normalize(" telerouter.example.com "))
        assertEquals("https://telerouter.example.com", ServerUrl.normalize("https://Telerouter.Example.com/"))
        assertEquals("https://192.168.1.5:8443", ServerUrl.normalize("192.168.1.5:8443"))
    }

    @Test fun refusesAnythingElse() {
        for (bad in listOf("", "http://telerouter.example.com", "https://u:p@h", "https://h/admin", "https://h?x=1", "ftp://h")) {
            try {
                ServerUrl.normalize(bad); fail(bad)
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
        assertEquals(Protocol.Event.Config(100, "0.10.0", "0000000f", "Zfold"),
            Protocol.parse("""{"type":"config","volume":100,"server_version":"0.10.0","client_id":"0000000f","name":"Zfold"}"""))
        assertEquals(Protocol.Event.Config(100, "0.9.0"), Protocol.parse("""{"type":"config","volume":100,"server_version":"0.9.0"}"""))
        assertEquals(Protocol.Event.Flush, Protocol.parse("""{"type":"flush"}"""))
        assertNull(Protocol.parse("""{"type":"something_new"}"""))
        assertNull(Protocol.parse("garbage"))
    }

    @Test fun clientMessages() {
        assertEquals("""{"type":"button","action":"talk","cue":false}""", Protocol.talk())
        val hello = Protocol.hello("0.2.0", false)
        assertTrue(hello.contains(""""transcript":true""") && hello.contains(""""platform":"android"""") && hello.contains(""""speech":true"""))
        assertTrue(Protocol.hello("0.2.0", false, speech = false).contains(""""speech":false"""))
        // the app tells telerouter whether its assistant sheet is open (0.2.2)
        assertEquals("""{"type":"assistant","open":true}""", Protocol.assistant(true))
        assertEquals("""{"type":"assistant","open":false}""", Protocol.assistant(false))
    }
}

/**
 * A telerouter behind a reverse proxy with sign-in (like Authentik's forward auth): without the proxy's cookie every
 * request is redirected to /outpost/start, which (the identity provider's session still being valid) comes straight
 * back through /outpost/callback, sets the cookie and redirects to the original URL.
 */
class ProxiedServerTest {
    private val ca = HeldCertificate.Builder().certificateAuthority(0).build()
    private val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").signedBy(ca).build()
    private val trust = HandshakeCertificates.Builder().addTrustedCertificate(ca.certificate).build()
    private lateinit var server: MockWebServer
    private val cookies = MemoryCookies()
    private val requests = LinkedBlockingQueue<RecordedRequest>()
    @Volatile private var idpSession = true          // the identity provider still knows us
    @Volatile private var proxyCookie = "p1"         // the value the proxy currently accepts
    @Volatile private var wsListener: WebSocketListener? = null
    @Volatile private var wsCode = 0                 // e.g. 403: telerouter refuses the token

    private fun base() = "https://localhost:${server.port}"
    private fun client(follow: Boolean = false) =
        Trust.client(StoreCookieJar(cookies), follow, Trust.Custom(trust.sslSocketFactory(), trust.trustManager))
    private fun api() = Api(base(), client(), client(follow = true))

    @Before fun start() {
        server = MockWebServer()
        server.useHttps(HandshakeCertificates.Builder().heldCertificate(cert, ca.certificate).build().sslSocketFactory())
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests.add(request)
                val path = request.url.encodedPath
                val cookie = request.headers["Cookie"] ?: ""
                if (path == "/outpost/start") {
                    return if (idpSession) redirect("/outpost/callback?rd=" + request.url.queryParameter("rd"))
                    else MockResponse.Builder().code(200).body("<html>Authentik login</html>").build()
                }
                if (path == "/outpost/callback") {
                    proxyCookie = "p" + (proxyCookie.drop(1).toInt() + 1)
                    return MockResponse.Builder().code(302).addHeader("Location", request.url.queryParameter("rd")!!)
                        .addHeader("Set-Cookie", "authentik_proxy=$proxyCookie; Path=/; Secure; HttpOnly").build()
                }
                if (!cookie.contains("authentik_proxy=$proxyCookie")) return redirect("/outpost/start?rd=$path")
                return when (path) {
                    "/api/health" -> json("""{"ok":true}""")
                    "/api/session" -> json(if (cookie.contains("__Host-telerouter=s1")) """{"logged_in":true,"csrf":"c1","version":"0.10.0"}"""
                        else """{"logged_in":false,"csrf":"","version":"0.10.0"}""")
                    "/api/clients/enroll" -> if (request.headers["X-CSRF-Token"] == "c1") json("""{"code":"ABCD-EFGH"}""")
                        else MockResponse.Builder().code(403).body("""{"detail":"CSRF check failed"}""").build()
                    "/api/client/enroll" -> json("""{"client_id":"0000000f","name":"Zfold","token":"trc_abc"}""")
                    "/ws/client" -> when {
                        wsCode != 0 -> MockResponse.Builder().code(wsCode).build()
                        request.headers["X-Telerouter-Token"] != "trc_abc" -> MockResponse.Builder().code(403).build()
                        else -> MockResponse.Builder().webSocketUpgrade(wsListener!!).build()
                    }
                    else -> MockResponse.Builder().code(404).build()
                }
            }
        }
        server.start()
        cookies.save(base(), "authentik_proxy=p1; Path=/; Secure")
    }

    @After fun stop() = server.close()

    private fun redirect(to: String) = MockResponse.Builder().code(302).addHeader("Location", to).build()
    private fun json(body: String) = MockResponse.Builder().addHeader("Content-Type", "application/json").body(body).build()

    @Test fun thePhoneAddsItselfWithTheDashboardsSession() {
        assertFalse(api().session().loggedIn)
        cookies.save(base(), "__Host-telerouter=s1; Path=/; Secure; HttpOnly")         // signed in in the WebView
        val s = api().session()
        assertTrue(s.loggedIn)
        val cfg = api().registerDevice("Zfold", s.csrf)
        assertEquals(ServerConfig(base(), "trc_abc", "0000000f", "Zfold"), cfg)
        val enroll = requests.toList().first { it.url.encodedPath == "/api/client/enroll" }
        assertTrue(enroll.body!!.utf8().contains(""""platform":"android""""))
    }

    @Test fun anExpiredProxySignInIsRenewedSilently() {
        proxyCookie = "p5"                                   // the proxy forgot p1
        try {
            api().session(); fail()
        } catch (e: TelerouterError) {
            assertTrue(e.signIn)
        }
        assertTrue(api().renewSignIn())
        assertEquals("authentik_proxy=p6", cookies.header(base()))
        assertFalse(api().session().loggedIn)               // through again (telerouter's own login is separate)
        idpSession = false
        proxyCookie = "p9"
        assertFalse(api().renewSignIn())                     // the identity provider wants a login: show the app
    }

    private fun connection(events: LinkedBlockingQueue<Any>, renew: () -> Boolean = { api().renewSignIn() }, speech: Boolean = true) =
        Connection(ServerConfig(base(), "trc_abc", "0000000f", "Zfold"), "0.2.0", object : Connection.Listener {
            override fun onOpen() { events.add("open") }
            override fun onEvent(event: Protocol.Event) { events.add(event) }
            override fun onAudio(pcm: ByteArray) { events.add(pcm.toList()) }
        }, client(), renew, speech)

    @Test fun theConnectionSpeaksTheProtocolThroughTheProxy() {
        val fromPhone = LinkedBlockingQueue<Any>()
        wsListener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send("""{"type":"ping"}""")
                webSocket.send("""{"type":"transcript","role":"assistant","text":"Hi"}""")
                webSocket.send(byteArrayOf(1, 2, 3, 4).toByteString())
            }
            override fun onMessage(webSocket: WebSocket, text: String) { fromPhone.add(text) }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) { fromPhone.add(bytes) }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(1000, null) }
        }
        proxyCookie = "p3"                                   // expired: renewed before the socket opens
        val events = LinkedBlockingQueue<Any>()
        val conn = connection(events, speech = false)
        conn.connect()
        assertEquals("open", events.poll(5, TimeUnit.SECONDS))
        val hello = fromPhone.poll(5, TimeUnit.SECONDS) as String
        assertEquals(Protocol.assistant(true), fromPhone.poll(5, TimeUnit.SECONDS))   // the sheet is open
        assertTrue(hello.contains(""""type":"hello"""") && hello.contains(""""speech":false"""))
        assertEquals(Protocol.Event.Ping, events.poll(5, TimeUnit.SECONDS))
        assertEquals("""{"type":"pong"}""", fromPhone.poll(5, TimeUnit.SECONDS))
        assertEquals(Protocol.Event.Transcript(false, "Hi"), events.poll(5, TimeUnit.SECONDS))
        assertEquals(listOf<Byte>(1, 2, 3, 4), events.poll(5, TimeUnit.SECONDS))
        assertTrue(conn.talk())
        assertEquals(Protocol.talk(), fromPhone.poll(5, TimeUnit.SECONDS))
        assertTrue(conn.sendAudio(ByteArray(640) { 7 }))
        assertEquals(640, (fromPhone.poll(5, TimeUnit.SECONDS) as ByteString).size)
        val ws = requests.toList().last { it.url.encodedPath == "/ws/client" }
        assertNull(ws.headers["Authorization"])
        assertTrue(ws.headers["Cookie"]!!.contains("authentik_proxy=p4"))
        conn.close()
        assertEquals(Protocol.assistant(false), fromPhone.poll(5, TimeUnit.SECONDS))   // sheet closed, not a drop
        assertFalse(conn.isOpen)
    }

    @Test fun whenTheProxyWantsALoginTheAppMustBeOpened() {
        proxyCookie = "p7"
        try {
            connection(LinkedBlockingQueue(), renew = { false }).connect(); fail()
        } catch (e: TelerouterError) {
            assertTrue(e.signIn && !e.unauthorized)
        }
    }

    @Test fun aRemovedPhoneIsTold() {
        wsCode = 403
        try {
            connection(LinkedBlockingQueue()).connect(); fail()
        } catch (e: TelerouterError) {
            assertTrue(e.unauthorized && !e.signIn)
        }
    }
}
