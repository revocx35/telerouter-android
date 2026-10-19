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

/** What the app keeps after setup (the token is stored encrypted by the app). */
data class ServerConfig(val lanUrl: String, val caPem: String, val publicUrl: String?, val token: String, val name: String)

class TelerouterError(message: String, val unauthorized: Boolean = false) : IOException(message)

object Enroll {
    /** Turn the one-time code into a device token (POST /api/client/enroll over the pinned connection). */
    fun enroll(link: SetupLink, caPem: String, client: OkHttpClient = Trust.pinnedClient(caPem, 6000)): ServerConfig {
        val body = buildJsonObject { put("code", link.code); put("platform", "android") }.toString()
            .toRequestBody("application/json".toMediaType())
        val req = Request.Builder().url("${link.server}/api/client/enroll").post(body).build()
        client.newCall(req).execute().use { r ->
            val text = r.body.string()
            if (r.code == 401) throw TelerouterError("The code is wrong or has expired. Make a new one in telerouter.", true)
            if (r.code == 429) throw TelerouterError("Too many attempts. Wait a few minutes and try again.")
            if (!r.isSuccessful) throw TelerouterError("telerouter answered ${r.code}")
            val o = Json.parseToJsonElement(text).jsonObject
            val token = o["token"]?.jsonPrimitive?.contentOrNull ?: throw TelerouterError("telerouter sent no token")
            val name = o["name"]?.jsonPrimitive?.contentOrNull ?: "Phone"
            return ServerConfig(link.server, caPem, link.publicUrl, token, name)
        }
    }
}

/**
 * One connection to telerouter as a speaker device. [connect] tries the LAN address first (short timeout), then
 * the internet address. Events arrive on OkHttp's thread; the listener must hand them off quickly.
 */
class Connection(
    private val cfg: ServerConfig,
    private val version: String,
    private val listener: Listener,
    private val lanClient: OkHttpClient = Trust.pinnedClient(cfg.caPem),
    private val publicClient: OkHttpClient? = cfg.publicUrl?.let { Trust.publicClient() },
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
        val attempts = buildList {
            add(Triple(cfg.lanUrl, lanClient, false))
            if (cfg.publicUrl != null && publicClient != null) add(Triple(cfg.publicUrl, publicClient, true))
        }
        var last: TelerouterError? = null
        for ((url, client, internet) in attempts) {
            try {
                open(url, client, internet)
                return
            } catch (e: TelerouterError) {
                last = e
                if (e.unauthorized) throw e
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
