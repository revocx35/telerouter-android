package io.github.revocx35.telerouter.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import android.webkit.CookieManager
import io.github.revocx35.telerouter.core.Api
import io.github.revocx35.telerouter.core.CookieStore
import io.github.revocx35.telerouter.core.ServerConfig
import io.github.revocx35.telerouter.core.StoreCookieJar
import io.github.revocx35.telerouter.core.Trust
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** The WebView's cookies: the sign-in at the proxy in front of telerouter (e.g. Authentik) and telerouter's session. */
object WebCookies : CookieStore {
    override fun header(url: String): String? = CookieManager.getInstance().getCookie(url)

    override fun save(url: String, setCookie: String) {
        val m = CookieManager.getInstance()
        m.setCookie(url, setCookie)
        m.flush()
    }
}

/** HTTP for the voice connection and the API, sharing the dashboard's sign-in. */
object Net {
    val client by lazy { Trust.client(StoreCookieJar(WebCookies)) }
    private val renewing by lazy { Trust.client(StoreCookieJar(WebCookies), followRedirects = true) }

    fun api(base: String) = Api(base, client, renewing)
}

/**
 * Adds this phone to telerouter as a speaker device once you are signed in to the dashboard (the WebView's admin
 * session), so there is no setup code to copy. Runs again whenever a page loads until it worked.
 */
class DeviceLink(private val context: Context, private val store: Store) {
    private val lock = Mutex()

    /** The new device, or null (already added, not signed in yet, or telerouter not reachable). */
    suspend fun ensure(): ServerConfig? = lock.withLock { withContext(Dispatchers.IO) { register() } }

    private fun register(): ServerConfig? {
        if (store.server() != null) return null
        val base = store.baseUrl ?: return null
        val api = Net.api(base)
        return try {
            val s = api.session()
            if (!s.loggedIn) return null
            api.registerDevice(deviceName(), s.csrf).also(store::saveServer)
        } catch (e: IOException) {
            null
        }
    }

    /** "Zfold7" (the name set in the phone's settings), else the model. */
    private fun deviceName(): String {
        val set = try {
            Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
        } catch (e: Exception) {
            null
        }
        val name = (set?.takeIf { it.isNotBlank() } ?: Build.MODEL ?: "Phone").replace(Regex("[\\x00-\\x1f<>]"), "").trim()
        return name.take(64).ifEmpty { "Phone" }
    }
}
