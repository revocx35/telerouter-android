package io.github.revocx35.telerouter.core

import java.io.IOException
import java.net.URI
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** The address you sign in with: "telerouter.example.com" or "https://host[:port]" becomes "https://host[:port]". */
object ServerUrl {
    fun normalize(text: String): String {
        var t = text.trim().trimEnd('/')
        require(t.isNotEmpty()) { "Enter telerouter's address" }
        if (!t.contains("://")) t = "https://$t"
        val uri = try {
            URI(t)
        } catch (e: Exception) {
            throw IllegalArgumentException("That is not a web address")
        }
        require(uri.scheme.equals("https", true)) { "telerouter's address must start with https://" }
        require(!uri.host.isNullOrEmpty() && uri.rawUserInfo == null) { "That is not a web address" }
        require(uri.rawPath.isNullOrEmpty() && uri.rawQuery == null && uri.rawFragment == null) {
            "Only the address, without a path (e.g. telerouter.example.com)"
        }
        return "https://" + uri.host.lowercase() + if (uri.port != -1 && uri.port != 443) ":${uri.port}" else ""
    }
}

/** What the phone keeps after it added itself as a device (the token is stored encrypted by the app). */
data class ServerConfig(val baseUrl: String, val token: String, val clientId: String, val name: String)

/**
 * [unauthorized]: telerouter refused this phone's token (it was removed). [signIn]: the reverse proxy in front of
 * telerouter (e.g. Authentik) wants you to sign in again in the app.
 */
class TelerouterError(message: String, val unauthorized: Boolean = false, val signIn: Boolean = false) : IOException(message)

/** Cookies shared with the app's WebView: the proxy's sign-in (e.g. Authentik) and telerouter's session. */
interface CookieStore {
    /** The Cookie header for [url] ("a=b; c=d"), or null. */
    fun header(url: String): String?
    /** Keep a Set-Cookie value [setCookie] that [url] sent. */
    fun save(url: String, setCookie: String)
}

class StoreCookieJar(private val store: CookieStore) : CookieJar {
    override fun loadForRequest(url: HttpUrl): List<Cookie> =
        store.header(url.toString())?.split(";")?.mapNotNull { part ->
            val (n, v) = part.trim().split("=", limit = 2).takeIf { it.size == 2 } ?: return@mapNotNull null
            if (n.isEmpty()) null else Cookie.Builder().name(n).value(v).hostOnlyDomain(url.host).path("/").secure().build()
        } ?: emptyList()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        for (c in cookies) store.save(url.toString(), c.toString())
    }
}

/** A simple in-memory [CookieStore] (tests, and the JVM live test). */
class MemoryCookies : CookieStore {
    private val jar = mutableMapOf<String, MutableMap<String, String>>()

    @Synchronized override fun header(url: String): String? =
        jar[URI(url).host]?.entries?.joinToString("; ") { "${it.key}=${it.value}" }?.ifEmpty { null }

    @Synchronized override fun save(url: String, setCookie: String) {
        val (nv) = setCookie.split(";", limit = 2)
        val (n, v) = nv.split("=", limit = 2).takeIf { it.size == 2 } ?: return
        val m = jar.getOrPut(URI(url).host) { mutableMapOf() }
        if (setCookie.contains("expires=Thu, 01 Jan 1970", ignoreCase = true) || setCookie.contains("max-age=0", true)) m.remove(n.trim())
        else m[n.trim()] = v.trim()
    }
}

private const val SIGN_IN = "Sign in to telerouter again: open the telerouter app."

/**
 * telerouter's admin API as the signed-in app uses it: the session (cookies from the WebView sign-in), adding this
 * phone as a speaker device, and renewing the proxy's sign-in without showing anything.
 */
class Api(val base: String, private val client: OkHttpClient, private val renewClient: OkHttpClient) {
    data class Session(val loggedIn: Boolean, val csrf: String, val version: String)

    private val json = Json { ignoreUnknownKeys = true }
    private val jsonType = "application/json".toMediaType()

    private fun call(req: Request): JsonObject {
        client.newCall(req).execute().use { r ->
            // the proxy in front wants a sign-in (a redirect to its login, or its own 401)
            if (r.isRedirect || (r.code == 401 && r.header("content-type")?.contains("json") != true)) {
                throw TelerouterError(SIGN_IN, signIn = true)
            }
            val text = r.peekBody(MAX_BODY).string()
            val o = try {
                json.parseToJsonElement(text).jsonObject
            } catch (e: Exception) {
                throw TelerouterError(if (r.isSuccessful) SIGN_IN else "telerouter answered ${r.code}", signIn = r.isSuccessful)
            }
            if (r.code == 401) throw TelerouterError("Sign in to telerouter first", signIn = true)
            if (!r.isSuccessful) {
                throw TelerouterError(o["detail"]?.jsonPrimitive?.contentOrNull ?: "telerouter answered ${r.code}")
            }
            return o
        }
    }

    private fun str(o: JsonObject, k: String) = o[k]?.jsonPrimitive?.contentOrNull

    /** Is the WebView's sign-in a telerouter admin session? */
    fun session(): Session {
        val o = call(Request.Builder().url("$base/api/session").build())
        return Session(o["logged_in"]?.jsonPrimitive?.booleanOrNull == true, str(o, "csrf") ?: "", str(o, "version") ?: "")
    }

    /** Add this phone as a speaker device (its own speaker too), with the admin session the WebView signed in with. */
    fun registerDevice(name: String, csrf: String): ServerConfig {
        val start = call(Request.Builder().url("$base/api/clients/enroll").header("X-CSRF-Token", csrf)
            .post(buildJsonObject { put("name", name) }.toString().toRequestBody(jsonType)).build())
        val code = str(start, "code") ?: throw TelerouterError("telerouter sent no code")
        val done = call(Request.Builder().url("$base/api/client/enroll")
            .post(buildJsonObject { put("code", code); put("platform", "android") }.toString().toRequestBody(jsonType)).build())
        val token = str(done, "token") ?: throw TelerouterError("telerouter sent no token")
        return ServerConfig(base, token, str(done, "client_id") ?: "", str(done, "name") ?: name)
    }

    /** Remove this phone's device (its token stops working) and log the session out. Best effort. */
    fun signOut(clientId: String) {
        val s = try { session() } catch (e: IOException) { return }
        if (!s.loggedIn) return
        if (clientId.isNotEmpty()) try {
            call(Request.Builder().url("$base/api/clients/$clientId").header("X-CSRF-Token", s.csrf).delete().build())
        } catch (_: IOException) {}
        try {
            call(Request.Builder().url("$base/api/logout").header("X-CSRF-Token", s.csrf).post(ByteArray(0).toRequestBody(jsonType)).build())
        } catch (_: IOException) {}
    }

    /**
     * The proxy's sign-in cookie expired: follow its redirects (they come back at once while the identity provider's
     * own session lasts) and keep the new cookies. True when telerouter answers again.
     */
    fun renewSignIn(): Boolean = try {
        renewClient.newCall(Request.Builder().url("$base/api/health").build()).execute().use { r ->
            r.isSuccessful && r.request.url.host == URI(base).host &&
                r.peekBody(4096).string().contains("\"ok\"")
        }
    } catch (e: IOException) {
        false
    }

    private companion object { const val MAX_BODY = 512L * 1024 }
}

/**
 * One connection to telerouter as a speaker device, through the same address as the dashboard. The device token
 * goes in `X-Telerouter-Token` (the proxy may use Authorization itself), the proxy's sign-in in the cookies. When
 * the proxy wants a new sign-in, [renewSignIn] gets one silently if it can. Events arrive on OkHttp's thread; the
 * listener must hand them off quickly.
 */
class Connection(
    private val cfg: ServerConfig,
    private val version: String,
    private val listener: Listener,
    private val client: OkHttpClient,
    private val renewSignIn: () -> Boolean = { false },
    private val speech: Boolean = true,
) {
    interface Listener {
        fun onOpen() {}
        fun onEvent(event: Protocol.Event) {}
        fun onAudio(pcm: ByteArray) {}
        fun onClosed(error: TelerouterError?) {}
    }

    private class SignInNeeded : Exception()

    @Volatile private var ws: WebSocket? = null
    @Volatile private var closedByUs = false

    val isOpen get() = ws != null

    /** Open the WebSocket (blocking up to a few seconds); throws [TelerouterError]. */
    fun connect() {
        try {
            open()
        } catch (e: SignInNeeded) {
            if (!renewSignIn()) throw TelerouterError(SIGN_IN, signIn = true)
            try {
                open()
            } catch (e: SignInNeeded) {
                throw TelerouterError(SIGN_IN, signIn = true)
            }
        }
    }

    private fun open() {
        val opened = java.util.concurrent.CompletableFuture<Unit>()
        val req = Request.Builder().url(cfg.baseUrl.replaceFirst("https://", "wss://") + "/ws/client")
            .header("X-Telerouter-Token", cfg.token).build()
        val socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ws = webSocket
                webSocket.send(Protocol.hello(version, aec = false, speech = speech))
                opened.complete(Unit)
                listener.onOpen()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val ev = Protocol.parse(text) ?: return
                if (ev is Protocol.Event.Ping) webSocket.send(Protocol.pong())
                listener.onEvent(ev)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onAudio(bytes.toByteArray())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = finish(webSocket, code, null)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: 0
                val err: Exception = when {
                    code in 300..399 || code == 401 -> SignInNeeded()           // the proxy's login
                    code == 403 -> TelerouterError("telerouter doesn't know this phone any more. Open the app to add it again.", unauthorized = true)
                    response != null -> TelerouterError("telerouter answered $code")
                    else -> TelerouterError(t.message ?: "connection failed")
                }
                if (!opened.isDone) opened.completeExceptionally(err)
                else finish(webSocket, 1006, err as? TelerouterError ?: TelerouterError("the connection was lost"))
            }
        })
        try {
            opened.get(client.connectTimeoutMillis.toLong() + 6000, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            socket.cancel()
            when (val c = e.cause) {
                is SignInNeeded -> throw c
                is TelerouterError -> throw c
                else -> throw TelerouterError(c?.message ?: "connection failed")
            }
        } catch (e: java.util.concurrent.TimeoutException) {
            socket.cancel()
            throw TelerouterError("telerouter did not answer")
        }
    }

    private fun finish(webSocket: WebSocket, code: Int, err: TelerouterError?) {
        if (ws !== webSocket) return
        ws = null
        // 1008 = the server refused the token after accepting the socket
        val e = err ?: if (code == 1008) TelerouterError("telerouter refused this phone. Open the app to add it again.", true) else null
        listener.onClosed(if (closedByUs) null else e)
    }

    fun talk() = ws?.send(Protocol.talk(cue = false)) ?: false
    fun cancel() = ws?.send(Protocol.cancel()) ?: false
    fun sendAudio(pcm: ByteArray, len: Int = pcm.size): Boolean = ws?.send(pcm.toByteString(0, len)) ?: false

    fun close() {
        closedByUs = true
        ws?.close(1000, null)
        ws = null
    }
}
