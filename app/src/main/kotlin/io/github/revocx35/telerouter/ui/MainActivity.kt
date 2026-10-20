package io.github.revocx35.telerouter.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebStorage
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import io.github.revocx35.telerouter.app
import io.github.revocx35.telerouter.assist.AssistActivity
import io.github.revocx35.telerouter.assist.AssistService
import io.github.revocx35.telerouter.core.ServerUrl
import io.github.revocx35.telerouter.core.Trust
import io.github.revocx35.telerouter.data.DeviceLink
import io.github.revocx35.telerouter.data.Net
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * The app: the address to sign in at (first start), then telerouter's dashboard (its web UI, see [Dashboard]) with
 * the phone's own screen ("This phone": assistant, wake word, answers, sign out) over it.
 */
class MainActivity : ComponentActivity() {
    private var connect by mutableStateOf(ConnectState())
    private var phone by mutableStateOf(PhoneState())
    private var phoneOpen by mutableStateOf(false)
    private var dashboard by mutableStateOf<Dashboard?>(null)
    private val link by lazy { DeviceLink(applicationContext, app.store) }
    private var adding = false

    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { refresh() }
    private val askForWake = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.RECORD_AUDIO] == true) setWake(true) else refresh()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        app.store.baseUrl?.let { openDashboard(it, savedInstanceState?.getString(KEY_URL)) }
        // the login happens inside telerouter's page (no page load): look every few seconds until the phone is added
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    if (dashboard != null && !app.store.hasDevice) addDevice()
                    delay(3000)
                }
            }
        }
        phoneOpen = savedInstanceState?.getBoolean(KEY_PHONE) == true || intent?.getBooleanExtra(EXTRA_PHONE, false) == true
        setContent {
            TelerouterTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val d = dashboard
                    if (d == null) ConnectScreen(connect, onAddress = { connect = connect.copy(address = it, error = null) }, onContinue = ::useAddress)
                    else {
                        DashboardView(d)
                        if (phoneOpen) {
                            BackHandler { phoneOpen = false; phone = phone.copy(justAdded = false, confirmSignOut = false) }
                            PhoneScreen(phone, phoneActions())
                        } else {
                            BackHandler { if (d.web.canGoBack()) d.web.goBack() else finish() }
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_PHONE, false)) phoneOpen = true
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        dashboard?.web?.url?.let { outState.putString(KEY_URL, it) }
        outState.putBoolean(KEY_PHONE, phoneOpen)
    }

    override fun onResume() {
        super.onResume()
        dashboard?.web?.onResume()
        refresh()
    }

    override fun onPause() {
        dashboard?.web?.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onDestroy() {
        dashboard?.web?.destroy()
        super.onDestroy()
    }

    @Composable
    private fun DashboardView(d: Dashboard) {
        val loading by d.loading.collectAsState()
        val error by d.error.collectAsState()
        // the area behind the status and navigation bars takes the web UI's top bar colour
        val bar = if (isSystemInDarkTheme()) Color(0xFF161B22) else Color.White
        Box(Modifier.fillMaxSize().background(bar)) {
            AndroidView(factory = { d.web.also { (it.parent as? ViewGroup)?.removeView(it) } },
                modifier = Modifier.fillMaxSize().safeDrawingPadding())
            if (loading) LinearProgressIndicator(Modifier.fillMaxWidth().safeDrawingPadding().align(Alignment.TopCenter))
            error?.let { DashboardError(it, onRetry = d::reload, onPhone = { phoneOpen = true }, onChangeServer = { signOut(everything = true) }) }
        }
    }

    // -- first start ------------------------------------------------------------------------------------------
    /** Check that the address answers over trusted https (telerouter, or a sign-in proxy in front of it). */
    private fun useAddress() {
        val base = try {
            ServerUrl.normalize(connect.address)
        } catch (e: IllegalArgumentException) {
            connect = connect.copy(error = e.message); return
        }
        connect = connect.copy(busy = true, error = null)
        lifecycleScope.launch {
            val problem = withContext(Dispatchers.IO) {
                try {
                    Trust.client().newCall(Request.Builder().url("$base/api/health").build()).execute().use { null }
                } catch (e: javax.net.ssl.SSLException) {
                    "The certificate of ${base.removePrefix("https://")} isn't trusted. The app needs an address with a public certificate, e.g. telerouter behind a reverse proxy."
                } catch (e: IOException) {
                    "Can't reach ${base.removePrefix("https://")} (${e.message})."
                }
            }
            connect = connect.copy(busy = false, error = problem)
            if (problem == null) {
                app.store.baseUrl = base
                openDashboard(base)
            }
        }
    }

    private fun openDashboard(base: String, url: String? = null) {
        val d = Dashboard(this, base, onAppLink = ::appLink, onPage = { addDevice() })
        dashboard = d
        d.open(url ?: base)
    }

    // -- the phone joins telerouter once you are signed in --------------------------------------------------------
    private fun addDevice() {
        if (app.store.hasDevice || adding) return
        adding = true
        lifecycleScope.launch {
            val added = try { link.ensure() } finally { adding = false } ?: return@launch
            AssistService.applySetting(this@MainActivity)
            refresh()
            phone = phone.copy(justAdded = true)
            phoneOpen = true
            Toast.makeText(this@MainActivity, "This phone is now the speaker “${added.name}”", Toast.LENGTH_LONG).show()
        }
    }

    /** Links from the web UI in the app: telerouter-app://talk, telerouter-app://assistant. */
    private fun appLink(what: String) {
        when (what) {
            "talk" -> talk()
            "assistant" -> phoneOpen = true
        }
    }

    private fun talk() {
        if (app.store.server() == null) {
            Toast.makeText(this, "Sign in first: the phone adds itself then", Toast.LENGTH_LONG).show()
            return
        }
        startActivity(Intent(this, AssistActivity::class.java).putExtra(AssistActivity.EXTRA_START, true))
    }

    // -- this phone -------------------------------------------------------------------------------------------
    private fun refresh() {
        val s = app.store
        phone = phone.copy(base = s.baseUrl ?: "", device = s.server()?.name, isAssistant = AssistService.isAssistant(this),
            wakeWord = s.wakeWord, spokenAnswers = s.spokenAnswers, wakeScreen = s.wakeScreen,
            hasMic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED)
    }

    private fun phoneActions() = PhoneActions(
        onBack = { phoneOpen = false; phone = phone.copy(justAdded = false, confirmSignOut = false) },
        onTalk = ::talk,
        onMakeAssistant = ::openAssistantSettings,
        onWake = ::setWake,
        onMic = { askMic.launch(Manifest.permission.RECORD_AUDIO) },
        onSpoken = { app.store.spokenAnswers = it; refresh() },
        onScreen = { app.store.wakeScreen = it; refresh() },
        onConfirmSignOut = { phone = phone.copy(confirmSignOut = it) },
        onSignOut = { signOut(everything = false) },
        onChangeServer = { signOut(everything = true) },
    )

    private fun openAssistantSettings() {
        for (i in listOf(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS), Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))) {
            try {
                startActivity(i); return
            } catch (_: Exception) {}
        }
    }

    private fun setWake(on: Boolean) {
        if (on) {
            val need = buildList {
                if (ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
                    add(Manifest.permission.RECORD_AUDIO)
                if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this@MainActivity,
                        Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
                    add(Manifest.permission.POST_NOTIFICATIONS)
            }
            if (Manifest.permission.RECORD_AUDIO in need) {
                askForWake.launch(need.toTypedArray()); return
            }
            if (need.isNotEmpty()) askForWake.launch(need.toTypedArray())
        }
        app.store.wakeWord = on
        AssistService.applySetting(this)
        refresh()
    }

    /** Remove this phone from telerouter (while the session still works), log out, and forget the sign-in here. */
    private fun signOut(everything: Boolean) {
        val base = app.store.baseUrl
        val clientId = app.store.server()?.clientId ?: ""
        app.assistant.current.value?.end()
        lifecycleScope.launch {
            if (base != null) withContext(Dispatchers.IO) { Net.api(base).signOut(clientId) }
            app.store.forget(everything)
            AssistService.applySetting(this@MainActivity)
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
            WebStorage.getInstance().deleteAllData()
            dashboard?.web?.apply { clearHistory(); clearCache(true) }
            phoneOpen = false
            phone = PhoneState()
            refresh()
            if (everything) {
                dashboard?.web?.destroy()
                dashboard = null
                connect = ConnectState(address = base?.removePrefix("https://") ?: "")
            } else {
                dashboard?.open()
            }
        }
    }

    companion object {
        const val EXTRA_PHONE = "phone"
        private const val KEY_URL = "url"
        private const val KEY_PHONE = "phone_open"
    }
}
