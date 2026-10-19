package io.github.revocx35.telerouter.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import io.github.revocx35.telerouter.assist.Message
import io.github.revocx35.telerouter.assist.Phase
import io.github.revocx35.telerouter.assist.UiState
import io.github.revocx35.telerouter.core.ServerConfig
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Every screen renders (light and dark, phone width); screenshots land in app/build/screenshots/. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w400dp-h860dp-xxhdpi")
class ScreensTest {
    @get:Rule val compose = createComposeRule()

    private val cfg = ServerConfig("https://jarvis.example.org", "https://192.168.1.5:8443", "ab".repeat(32), null, "trc_x", "Pixel")

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) {
        compose.setContent {
            TelerouterTheme(dark = dark) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) { content() }
            }
        }
        compose.onRoot().captureRoboImage("build/screenshots/$name.png")
    }

    @Test fun setUpFromTheLink() = shot("setup") { MainScreen(MainState(link = "telerouter://setup?v=1&…"), Actions()) }

    @Test fun setUpError() {
        shot("setup-error") { MainScreen(MainState(link = "telerouter://setup?v=1&…", error = "The code is wrong or has expired. Make a new one in telerouter."), Actions()) }
        compose.onNode(hasText("Set up") and hasClickAction()).assertExists()
    }

    @Test fun setUpNotAssistantYet() = shot("main-not-assistant") { MainScreen(MainState(server = cfg, hasMic = true), Actions()) }

    @Test fun setUpAndListening() = shot("main", dark = true) {
        MainScreen(MainState(server = cfg, hasMic = true, isAssistant = true, wakeWord = true), Actions())
    }

    @Test fun talkButtonWorks() {
        var talked = false
        compose.setContent { TelerouterTheme { MainScreen(MainState(server = cfg, hasMic = true, isAssistant = true), Actions(onTalk = { talked = true })) } }
        compose.onNodeWithText("Talk to Jarvis").performClick()
        assertTrue(talked)
    }

    private fun sheet(name: String, st: UiState, dark: Boolean = false) = shot(name, dark) {
        Box(Modifier.fillMaxSize().background(Color(0x66000000))) { AssistSheet(st, "Jarvis", {}, {}, {}) }
    }

    @Test fun sheetListening() = sheet("sheet-listening", UiState(Phase.LISTENING))

    @Test fun sheetAnswer() = sheet("sheet-answer", UiState(Phase.SPEAKING, listOf(
        Message(true, "Set Odam to 23 degrees."), Message(false, "Done, Odam is set to 23 degrees."),
    ), viaInternet = true), dark = true)

    @Test fun sheetError() = sheet("sheet-error", UiState(Phase.ERROR, error = "telerouter is not reachable"))
}
