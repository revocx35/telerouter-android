package io.github.revocx35.telerouter.assist

import android.Manifest
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import io.github.revocx35.telerouter.app
import io.github.revocx35.telerouter.ui.AssistSheet
import io.github.revocx35.telerouter.ui.MainActivity
import io.github.revocx35.telerouter.ui.TelerouterTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch

/** The conversation sheet: opened by the assistant gesture/button, the wake word, the tile or the app. */
class AssistActivity : ComponentActivity() {
    private var note by mutableStateOf<String?>(null)
    private val askMic = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) start() else note = "telerouter needs the microphone. Allow it in the app's settings."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val a = app.assistant
        val cfg = app.store.server()
        if (cfg == null) {
            startActivity(Intent(this, MainActivity::class.java))
            finish()
            return
        }
        setContent {
            TelerouterTheme {
                val conv by a.current.collectAsState()
                val st = conv?.state?.collectAsState()?.value ?: UiState()
                AssistSheet(st, "Jarvis", onMic = ::start, onStop = { conv?.stop(); finish() }, onDismiss = ::finish, extra = note)
            }
        }
        // close the sheet when the conversation ends on its own (it went quiet)
        lifecycleScope.launch {
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            a.current.filterNotNull().flatMapLatest { it.state }.collectLatest { if (it.phase == Phase.ENDED && started) finish() }
        }
        if (a.current.value?.active != true || intent.getBooleanExtra(EXTRA_START, false)) start()
        else started = true
    }

    private var started = false

    private fun start() {
        if (!app.assistant.hasMic) {
            askMic.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        note = null
        started = true
        app.assistant.begin(showSheet = false)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_START, false) || intent.action == Intent.ACTION_ASSIST ||
            intent.action == Intent.ACTION_VOICE_COMMAND) start()
    }

    companion object { const val EXTRA_START = "start" }
}
