package io.github.revocx35.telerouter.core

import java.io.IOException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

/**
 * What the app keeps after setup (the token is stored encrypted by the app). [publicUrl] is used first; [lanUrl]
 * with telerouter's own CA is the backup at home. [caPem] is null until the phone has reached the LAN address once.
 */
data class ServerConfig(val publicUrl: String?, val lanUrl: String?, val caSha256: String, val caPem: String?,
                        val token: String, val name: String)

class TelerouterError(message: String, val unauthorized: Boolean = false) : IOException(message)

object Enroll {
    /**
     * Set the phone up from a link, from anywhere: enroll through the internet address (public certificate) when
     * there is one, else the page's address, else the LAN address with telerouter's pinned CA. The LAN CA is
     * pinned now if the LAN address answers with it, else on a later visit home ([Connection.onCaPinned]).
     */
    fun setup(link: SetupLink, publicClient: OkHttpClient = Trust.publicClient(), lanTimeoutMs: Int = 2500): ServerConfig {
        val ca = try {
            Trust.fetchPinnedCa(link.lanUrl, link.caSha256, lanTimeoutMs)
        } catch (e: Exception) {
            null            // not at home (or another network's device has that address): pinned on a later visit
        }
        val public = link.publicUrl ?: link.server.takeIf { it != link.lanUrl }
        val routes = buildList {
            public?.let { add(it to publicClient) }
            if (link.server != public && link.server != link.lanUrl) add(link.server to publicClient)
            if (ca != null) add(link.lanUrl to Trust.pinnedClient(ca, 6000))
        }
        var last: Exception? = null
        for ((base, client) in routes) {
            try {
                val (token, name) = enrollAt(base, link.code, client)
                return ServerConfig(public, link.lanUrl, link.caSha256, ca, token, name)
            } catch (e: TelerouterError) {
                if (e.unauthorized) throw e
                last = e
            } catch (e: IOException) {
                last = e
            }
        }
        throw TelerouterError("Can't reach telerouter" + (last?.message?.let { " ($it)" } ?: "") +
            if (public == null) ". Away from home this needs telerouter's internet address (TELEROUTER_PUBLIC_URL)." else ".")
    }

    /** POST /api/client/enroll: the one-time code for a device token. Returns (token, device name). */
    fun enrollAt(base: String, code: String, client: OkHttpClient): Pair<String, String> {
        val body = buildJsonObject { put("code", code); put("platform", "android") }.toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("$base/api/client/enroll").post(body).build()
        client.newCall(req).execute().use { r ->
            val text = r.body.string()
            if (r.code == 401) throw TelerouterError("The code is wrong or has expired. Make a new one in telerouter.", true)
            if (r.code == 429) throw TelerouterError("Too many attempts. Wait a few minutes and try again.", true)
            if (!r.isSuccessful) throw TelerouterError("telerouter answered ${r.code}")
            val o = Json.parseToJsonElement(text).jsonObject
            val token = o["token"]?.jsonPrimitive?.contentOrNull ?: throw TelerouterError("telerouter sent no token")
            return token to (o["name"]?.jsonPrimitive?.contentOrNull ?: "Phone")
        }
    }
}

/**
 * One connection to telerouter as a speaker device. [connect] tries the internet address first, then the LAN
 * address with the pinned CA (pinning it on the first visit, see [onCaPinned]). Events arrive on OkHttp's thread;
 * the listener must hand them off quickly.
 */
class Connection(
    private val cfg: ServerConfig,
    private val version: String,
    private val listener: Listener,
    private val publicClient: OkHttpClient? = cfg.publicUrl?.let { Trust.publicClient() },
    private val lanClient: ((String) -> OkHttpClient) = { Trust.pinnedClient(it) },
    private val onCaPinned: (String) -> Unit = {},
) {
    interface Listener {
        fun onOpen(viaInternet: Boolean) {}
        fun onEvent(event: Protocol.Event) {}
        fun onAudio(pcm: ByteArray) {}
        fun onClosed(error: TelerouterError?) {}
    }

    @Volatile private var ws: WebSocket? = null
    @Volatile private var closedByUs = false
    @Volatile var viaInternet = false
        private set

    val isOpen get() = ws != null

    /** Open the WebSocket (blocking up to a few seconds); throws [TelerouterError] when neither address works. */
    fun connect() {
        var last: TelerouterError? = null
        if (cfg.publicUrl != null && publicClient != null) {
            try {
                open(cfg.publicUrl, publicClient, true)
                return
            } catch (e: TelerouterError) {
                if (e.unauthorized) throw e
                last = e
            }
        }
        val lan = cfg.lanUrl
        if (lan != null) {
            try {
                val ca = cfg.caPem ?: try {
                    Trust.fetchPinnedCa(lan, cfg.caSha256, 2500).also(onCaPinned)
                } catch (e: Trust.TrustError) {
                    throw TelerouterError(e.message ?: "wrong certificate")
                } catch (e: Exception) {
                    throw TelerouterError("telerouter is not reachable")
                }
                open(lan, lanClient(ca), false)
                return
            } catch (e: TelerouterError) {
                if (e.unauthorized) throw e
                last = last ?: e
            }
        }
        throw last ?: TelerouterError("telerouter is not reachable")
    }

    private fun open(base: String, client: OkHttpClient, internet: Boolean) {
        val opened = java.util.concurrent.CompletableFuture<Unit>()
        val req = Request.Builder().url(base.replaceFirst("https://", "wss://") + "/ws/client")
            .header("Authorization", "Bearer ${cfg.token}").build()
        val socket = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                ws = webSocket
                viaInternet = internet
                webSocket.send(Protocol.hello(version, aec = false))
                opened.complete(Unit)
                listener.onOpen(internet)
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
                val err = when {
                    response?.code == 401 || response?.code == 403 ->
                        TelerouterError("telerouter does not accept this phone any more. Set it up again.", true)
                    else -> TelerouterError(t.message ?: "connection failed")
                }
                if (!opened.isDone) opened.completeExceptionally(err) else finish(webSocket, 1006, err)
            }
        })
        try {
            opened.get(client.connectTimeoutMillis.toLong() + 4000, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.ExecutionException) {
            socket.cancel()
            throw e.cause as? TelerouterError ?: TelerouterError(e.cause?.message ?: "connection failed")
        } catch (e: java.util.concurrent.TimeoutException) {
            socket.cancel()
            throw TelerouterError("telerouter did not answer")
        }
    }

    private fun finish(webSocket: WebSocket, code: Int, err: TelerouterError?) {
        if (ws !== webSocket) return
        ws = null
        // 1008 = the server refused the token after accepting the socket
        val e = err ?: if (code == 1008) TelerouterError("telerouter refused this phone. Set it up again.", true) else null
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
