package local.reminders.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], qualifiers = "w360dp-h800dp")
@ConscryptMode(ConscryptMode.Mode.OFF)
class AboutSectionTest {
    @get:Rule val ui = createComposeRule()

    @Test fun setupDisclosesRunningPackageVersionWithoutLaunchingBrowserAtLargeFont() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        @Suppress("DEPRECATION")
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        installed.versionName = "7.4.2"
        installed.setLongVersionCode(42)
        shadowOf(context.packageManager).installPackage(installed)
        val model = ReminderViewModel(ApplicationProvider.getApplicationContext())
        ui.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                ReminderTheme { ReminderApp(model, UiState(loading = false), false, {}) }
            }
        }
        ui.onNodeWithContentDescription("Open setup").performClick()
        ui.onNodeWithText("Installed version 7.4.2 (build 42)").assertDoesNotExist()
        ui.onNodeWithText("About").performScrollTo().performClick()
        ui.onNodeWithText("Installed version 7.4.2 (build 42)").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Latest release on GitHub").performScrollTo().assertIsDisplayed()
        assertNull(shadowOf(context as android.app.Application).nextStartedActivity)
    }

    @Test fun explicitTapOpensStableLatestReleaseInExternalBrowser() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        var launched: Intent? = null
        val browserContext = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { launched = intent }
        }
        ui.setContent {
            CompositionLocalProvider(LocalContext provides browserContext) {
                ReminderTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AboutSection() } }
            }
        }
        assertNull(launched)
        ui.onNodeWithText("About").performClick()
        assertNull(launched)
        ui.onNodeWithText("Latest release on GitHub").performClick()
        assertEquals(Intent.ACTION_VIEW, launched!!.action)
        assertEquals("https://github.com/mikeliddle/AutoExecSec/releases/latest", launched!!.data.toString())
        assertTrue(launched!!.hasCategory(Intent.CATEGORY_BROWSABLE))
        assertNull(launched!!.component)
    }

    @Test fun missingBrowserShowsVisibleRecoveryAndInstalledBuildWithoutVersionName() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        @Suppress("DEPRECATION")
        val installed = context.packageManager.getPackageInfo(context.packageName, 0)
        installed.versionName = null
        installed.setLongVersionCode(51)
        shadowOf(context.packageManager).installPackage(installed)
        val noBrowserContext = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { throw ActivityNotFoundException() }
        }
        ui.setContent {
            CompositionLocalProvider(LocalContext provides noBrowserContext) {
                ReminderTheme { Column(Modifier.verticalScroll(rememberScrollState())) { AboutSection() } }
            }
        }
        ui.onNodeWithText("About").performClick()
        ui.onNodeWithText("Installed build 51").assertIsDisplayed()
        ui.onNodeWithText("Latest release on GitHub").performClick()
        ui.onNodeWithText("No browser available. Install or enable a browser to open the latest release.")
            .performScrollTo().assertIsDisplayed()
    }
}
