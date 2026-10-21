package io.github.revocx35.telerouter.ui

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.net.toUri
import android.net.http.SslError
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import io.github.revocx35.telerouter.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * telerouter's own web UI as the app's dashboard: every page and setting the browser has, at the same address, with
 * the same sign-in (the proxy's, e.g. Authentik, then telerouter's login or its single sign-on). The UI knows it is
 * in the app (user agent `telerouter-android/<version>`) and links to the phone's own screens with
 * `telerouter-app://talk` and `telerouter-app://assistant`.
 *
 * Navigation rules: telerouter's own pages stay here; so do redirects and pages that aren't opened by a tap (the
 * sign-in flow through the identity provider), and anything while a sign-in page is open. A tap on a link from
 * telerouter's UI to another site opens the browser. Nothing but https is loaded; certificate errors are never
 * skipped; there is no JavaScript bridge, and the app links work only from telerouter's own pages.
 */
@SuppressLint("SetJavaScriptEnabled")
class Dashboard(context: Context, val base: String, private val onAppLink: (String) -> Unit, private val onPage: (String) -> Unit) {
    val loading = MutableStateFlow(true)
    val error = MutableStateFlow<String?>(null)
    @Volatile private var current: String = base

    val web: WebView = WebView(context).apply {
        // MATCH_PARENT, not the wrap_content Compose's AndroidView gives a view by default: with wrap_content the
        // WebView takes the viewport height as 0, so 100vh/100dvh are 0 px (Authentik's login page collapsed)
        layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        with(settings) {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = false
            allowContentAccess = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            setSupportMultipleWindows(false)
            javaScriptCanOpenWindowsAutomatically = false
            userAgentString = "$userAgentString telerouter-android/${BuildConfig.VERSION_NAME}"
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
        webChromeClient = WebChromeClient()                     // confirm()/prompt() dialogs of the UI
        webViewClient = Client()
    }

    private fun origin(url: String?): String? = try {
        val u = (url ?: return null).toUri()
        if (u.scheme != "https" || u.host.isNullOrEmpty()) null
        else "https://" + u.host!!.lowercase() + if (u.port != -1 && u.port != 443) ":${u.port}" else ""
    } catch (e: Exception) {
        null
    }

    private val onOwnPage get() = origin(current) == base

    fun open(url: String = base) {
        error.value = null
        web.loadUrl(if (origin(url) == base) url else base)
    }

    fun reload() {
        error.value = null
        if (web.url == null) open() else web.reload()
    }

    private inner class Client : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
            val u = request.url
            if (u.scheme == "telerouter-app") {
                if (onOwnPage) onAppLink(u.host ?: "")
                return true
            }
            if (u.scheme != "https") return true                       // no http, intent:, file:, javascript:
            if (!request.isForMainFrame || origin(u.toString()) == base) return false
            if (request.isRedirect || !request.hasGesture() || !onOwnPage) return false   // a sign-in flow
            try {
                view.context.startActivity(Intent(Intent.ACTION_VIEW, u).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Exception) {}
            return true
        }

        override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
            current = url
            loading.value = true
        }

        override fun onPageFinished(view: WebView, url: String) {
            current = url
            loading.value = false
            if (origin(url) == base) onPage(url)
        }

        override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) {
            current = url
            if (origin(url) == base) onPage(url)
        }

        override fun onReceivedError(view: WebView, request: WebResourceRequest, err: WebResourceError) {
            if (request.isForMainFrame) {
                loading.value = false
                error.value = "Can't reach ${request.url.host}: ${err.description}"
            }
        }

        override fun onReceivedHttpError(view: WebView, request: WebResourceRequest, resp: WebResourceResponse) {
            if (request.isForMainFrame && resp.statusCode in listOf(502, 503, 504) && origin(request.url.toString()) == base) {
                error.value = "telerouter is not answering (HTTP ${resp.statusCode} from the proxy in front of it)."
            }
        }

        @SuppressLint("WebViewClientOnReceivedSslError")
        override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, e: SslError) {
            handler.cancel()                                           // never skipped
            loading.value = false
            error.value = "The certificate of ${e.url.toUri().host} isn't trusted. The app needs an address with a " +
                "public certificate, e.g. telerouter behind a reverse proxy."
        }
    }
}
