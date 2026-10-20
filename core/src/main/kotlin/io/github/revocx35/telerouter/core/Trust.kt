package io.github.revocx35.telerouter.core

import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import okhttp3.CookieJar
import okhttp3.OkHttpClient

/**
 * TLS: the app reaches telerouter at the address you sign in with (a reverse proxy with a public certificate), so
 * only the system's CAs are trusted (never user-installed ones, see network_security_config), hostname checks on.
 */
object Trust {
    /** For tests: a client that trusts a test CA instead of the system's. */
    data class Custom(val sslSocketFactory: SSLSocketFactory, val trustManager: X509TrustManager)

    /**
     * The app's HTTP client. [cookies] carry the sign-in at the proxy in front of telerouter (e.g. Authentik) and
     * telerouter's own session; [followRedirects] only for renewing that sign-in (https to https only).
     */
    fun client(cookies: CookieJar = CookieJar.NO_COOKIES, followRedirects: Boolean = false, custom: Custom? = null): OkHttpClient =
        OkHttpClient.Builder()
            .connectTimeout(6, TimeUnit.SECONDS)
            .readTimeout(70, TimeUnit.SECONDS)          // the server pings every 20 s
            .writeTimeout(10, TimeUnit.SECONDS)
            .followRedirects(followRedirects)
            .followSslRedirects(false)                  // never from https to http
            .retryOnConnectionFailure(false)
            .cookieJar(cookies)
            .apply { custom?.let { sslSocketFactory(it.sslSocketFactory, it.trustManager) } }
            .build()
}
