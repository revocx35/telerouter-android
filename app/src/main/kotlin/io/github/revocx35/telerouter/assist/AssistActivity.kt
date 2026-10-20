package io.github.revocx35.telerouter.assist

import android.Manifest
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.view.WindowManager
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
        volumeControlStream = AudioManager.STREAM_MUSIC          // the volume keys set how loud Jarvis answers
        screen(intent)
        setContent {
            TelerouterTheme {
                val conv by a.current.collectAsState()
                val st = conv?.state?.collectAsState()?.value ?: UiState()
                val hint = note ?: if (st.phase == Phase.SPEAKING && a.answersMuted) "The media volume is off: turn it up to hear Jarvis." else null
                AssistSheet(st, "Jarvis", onMic = ::start, onStop = { conv?.stop(); finish() }, onDismiss = ::finish, extra = hint,
                    onOpenApp = { startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); finish() })
            }
        }
        // close the sheet when the conversation ends on its own (it went quiet); keep the screen on until then
        lifecycleScope.launch {
            @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
            // a new conversation starts out ENDED for a moment: only an end after it was active closes the sheet
            var active = false
            a.current.filterNotNull().flatMapLatest { it.state }.collectLatest {
                if (it.phase == Phase.ENDED) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                if (it.phase != Phase.ENDED) active = true
                else if (active && started) finish()
            }
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

    /** Shown over the lock screen; started by "Hey Jarvis" with "Turn the screen on", it also lights the screen. */
    private fun screen(i: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(i.getBooleanExtra(EXTRA_WAKE, false))
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        screen(intent)
        if (intent.getBooleanExtra(EXTRA_START, false) || intent.action == Intent.ACTION_ASSIST ||
            intent.action == Intent.ACTION_VOICE_COMMAND) start()
    }

    companion object {
        const val EXTRA_START = "start"
        const val EXTRA_WAKE = "wake"
    }
}
