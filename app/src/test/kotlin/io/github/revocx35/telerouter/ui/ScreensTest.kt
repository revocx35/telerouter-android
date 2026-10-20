package io.github.revocx35.telerouter.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.revocx35.telerouter.assist.Message
import io.github.revocx35.telerouter.assist.Phase
import io.github.revocx35.telerouter.assist.UiState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every native screen renders (light and dark, phone width); screenshots land in app/build/screenshots/.
 *  The dashboard itself is telerouter's web UI (tested in telerouter). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class ScreensTest {
    @get:Rule val compose = createComposeRule()

    private val base = "https://telerouter.example.org"

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            TelerouterTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Test fun firstStart() = shot("connect") { ConnectScreen(ConnectState("telerouter.example.org"), {}, {}) }

    @Test fun firstStartError() {
        shot("connect-error", dark = true) {
            ConnectScreen(ConnectState("telerouter.example.org", error = "The certificate of telerouter.example.org isn't trusted. The app needs an address with a public certificate, e.g. telerouter behind a reverse proxy."), {}, {})
        }
        compose.onNode(hasText("Continue") and hasClickAction()).assertExists()
    }

    @Test fun justAdded() = shot("phone-added") {
        PhoneScreen(PhoneState(base, device = "Zfold7", justAdded = true, hasMic = true), PhoneActions())
    }

    @Test fun assistantAndWakeWord() = shot("phone", dark = true) {
        PhoneScreen(PhoneState(base, device = "Zfold7", isAssistant = true, wakeWord = true, hasMic = true, spokenAnswers = false), PhoneActions())
    }

    @Test fun notAddedYet() = shot("phone-not-added") { PhoneScreen(PhoneState(base), PhoneActions()) }

    @Test fun phoneScreenButtonsWork() {
        var talked = false
        var spoken: Boolean? = null
        compose.setContent {
            TelerouterTheme {
                PhoneScreen(PhoneState(base, device = "Zfold7", isAssistant = true, hasMic = true),
                    PhoneActions(onTalk = { talked = true }, onSpoken = { spoken = it }))
            }
        }
        compose.onNodeWithText("Talk to Jarvis").performClick()
        assertTrue(talked)
        compose.onNodeWithText("Spoken answers", substring = true).assertExists()
        compose.onNode(hasText("Sign out") and hasClickAction()).assertExists()
        assertEquals(null, spoken)
    }

    @Test fun dashboardDown() = shot("dashboard-error") {
        DashboardError("telerouter is not answering (HTTP 502 from the proxy in front of it).", {}, {}, {})
    }

    private fun sheet(name: String, st: UiState, dark: Boolean = false) = shot(name, dark) {
        Box(Modifier.fillMaxSize().background(Color(0x66000000))) { AssistSheet(st, "Jarvis", {}, {}, {}) }
    }

    @Test fun sheetListening() = sheet("sheet-listening", UiState(Phase.LISTENING))

    @Test fun sheetAnswer() = sheet("sheet-answer", UiState(Phase.SPEAKING, listOf(
        Message(true, "Set Odam to 23 degrees."), Message(false, "Done, Odam is set to 23 degrees."),
    )), dark = true)

    @Test fun sheetSignInAgain() {
        sheet("sheet-sign-in", UiState(Phase.ERROR, error = "Sign in to telerouter again: open the telerouter app.", needsApp = true))
        compose.onNodeWithText("Open telerouter").assertExists()
    }
}
