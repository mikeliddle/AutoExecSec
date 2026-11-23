package local.reminders.ui

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
@ConscryptMode(ConscryptMode.Mode.OFF)
class LicenseNoticesTest {
    @get:Rule val ui = createComposeRule()

    @Test fun setupProvidesCompleteOfflineNoticesAtLargeFont() {
        val model = ReminderViewModel(ApplicationProvider.getApplicationContext())
        ui.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                ReminderTheme { ReminderApp(model, UiState(loading = false), false, {}) }
            }
        }
        ui.onNodeWithContentDescription("Open setup").performClick()
        ui.onNodeWithText("Licenses").performScrollTo().performClick()
        ui.onNodeWithText("Attribution").performScrollTo().performClick()
        ui.onNodeWithText("Auto ExecSec third-party notices", substring = true)
            .assertTextContains("retain their own licenses", substring = true)
            .assertTextContains("OpenSSL 3.0.16", substring = true)
        ui.onNodeWithText("Attribution").performScrollTo().performClick()
        ui.onNodeWithText("Auto ExecSec · MIT").performScrollTo().performClick()
        ui.onNodeWithText("MIT License", substring = true)
            .assertTextContains("Copyright (c) 2026 mikeliddle", substring = true)
        ui.onNodeWithText("Auto ExecSec · MIT").performScrollTo().performClick()
        ui.onNodeWithText("SQLCipher Community").performScrollTo().performClick()
        ui.onNodeWithText("SQLCipher Community: notices", substring = true)
            .assertTextContains("Redistributions in binary form must reproduce", substring = true)
            .assertTextContains("THIS SOFTWARE IS PROVIDED BY ZETETIC LLC", substring = true)
        ui.onNodeWithText("SQLCipher Community").performScrollTo().performClick()
        ui.onNodeWithText("Apache License 2.0").performScrollTo().performClick()
        ui.onNodeWithText("TERMS AND CONDITIONS FOR USE", substring = true)
            .assertTextContains("END OF TERMS AND CONDITIONS", substring = true)
    }
}
