package io.github.revocx35.telerouter.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * telerouter's speaker protocol (`wss://<server>/ws/client` with the device token in `X-Telerouter-Token`), the
 * same one the Linux/macOS speaker client speaks. Audio both ways is 16 kHz mono PCM16 little-endian in binary frames.
 */
object Protocol {
    const val SAMPLE_RATE = 16000
    private val json = Json { ignoreUnknownKeys = true }

    /** Speaker states the server reports. Unknown ones fall back to [State.OTHER]. */
    enum class State { IDLE, LISTENING, THINKING, SPEAKING, RINGING, CALL, OTHER;
        companion object {
            fun of(s: String?): State = entries.firstOrNull { it.name.equals(s, ignoreCase = true) } ?: OTHER
        }
    }

    sealed interface Event {
        data class StateChanged(val state: State) : Event
        data object Flush : Event
        data object Ping : Event
        data class Config(val volume: Int, val serverVersion: String, val clientId: String = "", val name: String = "") : Event
        data class Transcript(val fromUser: Boolean, val text: String) : Event
    }

    /** A text frame from the server, or null for anything unknown (newer servers may send more). */
    fun parse(text: String): Event? {
        val o: JsonObject = try {
            json.parseToJsonElement(text).jsonObject
        } catch (e: Exception) {
            return null
        }
        fun str(k: String) = o[k]?.jsonPrimitive?.contentOrNull
        return when (str("type")) {
            "state" -> Event.StateChanged(State.of(str("state")))
            "flush" -> Event.Flush
            "ping" -> Event.Ping
            "config" -> Event.Config(o["volume"]?.jsonPrimitive?.intOrNull ?: 100, str("server_version") ?: "",
                str("client_id") ?: "", str("name") ?: "")
            "transcript" -> {
                val t = str("text") ?: return null
                Event.Transcript(str("role") == "user", t)
            }
            else -> null
        }
    }

    /** [speech] false: answers come as text only (telerouter doesn't speak them to this phone). */
    fun hello(version: String, aec: Boolean, speech: Boolean = true): String = buildJsonObject {
        put("type", "hello"); put("version", version); put("platform", "android")
        put("aec", aec); put("transcript", true); put("speech", speech)
    }.toString()

    /** Start a turn. cue=false: the phone played its own start sound, so the server listens at once. */
    fun talk(cue: Boolean = false): String = buildJsonObject {
        put("type", "button"); put("action", "talk"); put("cue", cue)
    }.toString()

    fun cancel(): String = buildJsonObject { put("type", "button"); put("action", "cancel") }.toString()

    fun pong(): String = """{"type":"pong"}"""
}
