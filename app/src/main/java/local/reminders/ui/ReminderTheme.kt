package local.reminders.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Material task-first UI: a dated queue, exact review, explicit approval.
// Cool mineral surfaces and blue action roles; no decorative dashboard metrics.
// Compact navigation becomes a rail on wide windows. System typography and insets.
@Composable
fun ReminderTheme(content: @Composable () -> Unit) {
    val light = lightColorScheme(
        primary = Color(0xFF155CAB), onPrimary = Color.White,
        primaryContainer = Color(0xFFD6E4FF), onPrimaryContainer = Color(0xFF102C50),
        secondary = Color(0xFF4E6077), secondaryContainer = Color(0xFFD9E3F3),
        surface = Color(0xFFF8F9FD), onSurface = Color(0xFF191C20),
        surfaceVariant = Color(0xFFE0E5EF), onSurfaceVariant = Color(0xFF424750),
        error = Color(0xFFBA1A1A)
    )
    val dark = darkColorScheme(
        primary = Color(0xFFA7C8FF), onPrimary = Color(0xFF003064),
        primaryContainer = Color(0xFF004788), onPrimaryContainer = Color(0xFFD6E4FF),
        secondary = Color(0xFFBDCBDD), secondaryContainer = Color(0xFF37495F),
        surface = Color(0xFF101318), onSurface = Color(0xFFE1E2E9),
        surfaceVariant = Color(0xFF424750), onSurfaceVariant = Color(0xFFC2C6D1),
        error = Color(0xFFFFB4AB)
    )
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) dark else light, content = content)
}
