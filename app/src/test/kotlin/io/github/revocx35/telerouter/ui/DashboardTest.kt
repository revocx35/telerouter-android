package io.github.revocx35.telerouter.ui

import android.view.ViewGroup
import org.robolectric.RuntimeEnvironment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DashboardTest {
    /** With wrap_content (AndroidView's default) the WebView's viewport height is 0: 100vh = 0 px, and Authentik's
     *  login page (min-height: 100dvh) collapsed to its background. */
    @Test fun theWebViewFillsItsSpaceSoViewportUnitsWork() {
        val d = Dashboard(RuntimeEnvironment.getApplication(), "https://telerouter.example.org", {}, {})
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, d.web.layoutParams.width)
        assertEquals(ViewGroup.LayoutParams.MATCH_PARENT, d.web.layoutParams.height)
        assertTrue(d.web.settings.userAgentString.contains("telerouter-android/"))
        assertTrue(!d.web.settings.allowFileAccess)
    }
}
