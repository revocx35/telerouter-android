package io.github.revocx35.telerouter.core

import java.io.ByteArrayInputStream
import java.net.InetSocketAddress
import java.net.URI
import java.security.KeyStore
import java.security.MessageDigest
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.util.Base64
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * TLS trust: telerouter's LAN address uses its own CA, which the app pins (only that CA, hostname checks on);
 * the internet address uses the system's CAs (never user-installed ones, see network_security_config).
 */
object Trust {
    class TrustError(message: String) : Exception(message)

    fun sha256Hex(der: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(der).joinToString("") { "%02x".format(it) }

    fun pem(cert: X509Certificate): String =
        "-----BEGIN CERTIFICATE-----\n" + Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(cert.encoded) +
            "\n-----END CERTIFICATE-----\n"

    fun parsePem(pem: String): X509Certificate =
        CertificateFactory.getInstance("X.509").generateCertificate(ByteArrayInputStream(pem.toByteArray())) as X509Certificate

    /** "AB:CD:…" for showing a fingerprint to a person. */
    fun pretty(hex: String): String = hex.uppercase().chunked(2).joinToString(":")

    /**
     * Connect to [baseUrl] without trusting anything yet, and return the certificate in the chain it presents whose
     * SHA-256 is [expectedSha256] (telerouter sends its server certificate and its CA). Nothing else is accepted.
     */
    fun fetchPinnedCa(baseUrl: String, expectedSha256: String, timeoutMs: Int = 8000): String {
        val uri = URI(baseUrl)
        var chain: Array<X509Certificate> = emptyArray()
        val capture = object : X509TrustManager {
            override fun checkClientTrusted(c: Array<X509Certificate>, a: String) = throw java.security.cert.CertificateException()
            override fun checkServerTrusted(c: Array<X509Certificate>, a: String) { chain = c }
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(capture), null) }
        (ctx.socketFactory.createSocket() as SSLSocket).use { s ->
            s.soTimeout = timeoutMs
            s.connect(InetSocketAddress(uri.host, if (uri.port == -1) 443 else uri.port), timeoutMs)
            s.startHandshake()
        }
        val ca = chain.firstOrNull { sha256Hex(it.encoded) == expectedSha256.lowercase() }
            ?: throw TrustError("The server's certificate does not match the setup link. Don't continue on this network.")
        return pem(ca)
    }

    /** A trust manager that accepts only chains ending in [caPem]. */
    fun pinnedTrustManager(caPem: String): X509TrustManager {
        val ks = KeyStore.getInstance(KeyStore.getDefaultType()).apply {
            load(null, null)
            setCertificateEntry("telerouter-ca", parsePem(caPem))
        }
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(ks) }
        return tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    private fun base(): OkHttpClient.Builder = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(70, TimeUnit.SECONDS)          // the server pings every 20 s
        .writeTimeout(10, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)

    /** For the LAN address: only telerouter's CA, with normal hostname verification. */
    fun pinnedClient(caPem: String, connectTimeoutMs: Long = 2500): OkHttpClient {
        val tm = pinnedTrustManager(caPem)
        val ctx = SSLContext.getInstance("TLS").apply { init(null, arrayOf(tm), null) }
        return base().connectTimeout(connectTimeoutMs, TimeUnit.MILLISECONDS).sslSocketFactory(ctx.socketFactory, tm).build()
    }

    /** For the internet address (a reverse proxy with a public certificate): the system's CAs. */
    fun publicClient(): OkHttpClient = base().connectTimeout(6, TimeUnit.SECONDS).build()
}
