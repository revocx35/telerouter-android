package io.github.revocx35.telerouter.core

import java.net.URI
import java.net.URLDecoder

/**
 * The link telerouter's admin UI shows when you add a device:
 * `telerouter://setup?v=1&server=https://192.168.1.5:8443&ca=<sha256 hex of its CA>&code=ABCD-EFGH[&public=https://x.example]`.
 *
 * `server` is reached with telerouter's own CA (pinned by `ca`), `public` (optional) through a reverse proxy
 * with a public certificate. The code works once, for 15 minutes.
 */
data class SetupLink(val server: String, val caSha256: String, val code: String, val publicUrl: String?) {
    companion object {
        private val HEX64 = Regex("^[0-9a-f]{64}$")
        private val CODE = Regex("^[A-Za-z0-9-]{4,32}$")

        fun parse(text: String): SetupLink {
            val uri = try {
                URI(text.trim())
            } catch (e: Exception) {
                throw IllegalArgumentException("That is not a telerouter setup link")
            }
            require(uri.scheme == "telerouter" && uri.host == "setup") { "That is not a telerouter setup link" }
            val q = (uri.rawQuery ?: "").split("&").filter { it.contains("=") }.associate {
                val (k, v) = it.split("=", limit = 2)
                URLDecoder.decode(k, Charsets.UTF_8) to URLDecoder.decode(v, Charsets.UTF_8)
            }
            require(q["v"] == "1") { "This setup link is for a newer app version" }
            val server = baseUrl(q["server"]) ?: throw IllegalArgumentException("The link has no valid server address")
            val ca = q["ca"]?.lowercase()?.takeIf { HEX64.matches(it) }
                ?: throw IllegalArgumentException("The link has no valid certificate fingerprint")
            val code = q["code"]?.takeIf { CODE.matches(it) } ?: throw IllegalArgumentException("The link has no valid code")
            val public = q["public"]?.let { baseUrl(it) ?: throw IllegalArgumentException("The internet address is not valid") }
            return SetupLink(server, ca, code, public)
        }

        /** "https://host[:port]" (no path, no user info), or null. */
        fun baseUrl(text: String?): String? {
            val t = text?.trim()?.trimEnd('/') ?: return null
            val uri = try {
                URI(t)
            } catch (e: Exception) {
                return null
            }
            if (uri.scheme != "https" || uri.host.isNullOrEmpty() || uri.rawUserInfo != null) return null
            if (!uri.rawPath.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null) return null
            if (uri.port != -1 && uri.port !in 1..65535) return null
            return t
        }
    }
}
