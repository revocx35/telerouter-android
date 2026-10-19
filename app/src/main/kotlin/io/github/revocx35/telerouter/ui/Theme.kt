package io.github.revocx35.telerouter.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// telerouter's web UI colours (accent blue on neutral greys)
private val Light = lightColorScheme(
    primary = Color(0xFF1F6FEB), onPrimary = Color.White, primaryContainer = Color(0xFFDCE8FD),
    onPrimaryContainer = Color(0xFF0B2E66), background = Color(0xFFF6F7F9), surface = Color.White,
    surfaceVariant = Color(0xFFEEF0F3), onSurfaceVariant = Color(0xFF5B6270), error = Color(0xFFC62828),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF6EA8FE), onPrimary = Color(0xFF06204A), primaryContainer = Color(0xFF173C78),
    onPrimaryContainer = Color(0xFFDCE8FD), background = Color(0xFF14161A), surface = Color(0xFF1C1F24),
    surfaceVariant = Color(0xFF262A31), onSurfaceVariant = Color(0xFFA9B0BC), error = Color(0xFFEF9A9A),
)

@Composable
fun TelerouterTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) Dark else Light, content = content)
}
