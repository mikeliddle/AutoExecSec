package local.reminders.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import local.reminders.ReminderApplication
import local.reminders.domain.Recipient
import local.reminders.runtime.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Fake preview only: never confirms a send, opens storage or changes device permissions. */
@RunWith(AndroidJUnit4::class)
class BroadcastReviewTest {
    @get:Rule val ui = createComposeRule()

    @Test fun separateSmsPreviewRequiresBothAcknowledgmentsAndFinalConfirmation() {
        val model = ReminderViewModel(ApplicationProvider.getApplicationContext<ReminderApplication>())
        val preview = BroadcastPreview("synthetic-preview", listOf(
            BroadcastMessage(Recipient("alex", "Alex Example", "+12025550101"), "Hello Alex.", 1),
            BroadcastMessage(Recipient("sam", "Sam Example", "+12025550102"), "Hello Sam.", 1)),
            "Hello {FirstName}.", 17, 37,
            1_800_000_000_000, 1_800_086_400_000)
        val state = UiState(loading = false, settings = AppSettings(subscriptionId = 17),
            capabilities = Capabilities(emptyList(), listOf(SimChoice(17, "Test SIM"))),
            broadcastPreview = preview)
        ui.setContent {
            ReminderTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) { BroadcastScreen(model, state) }
            }
        }
        ui.onNodeWithText("Consent and carrier permission are your responsibility").assertExists()
        ui.onNodeWithText("Send 2 separate SMS").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithText("To: +12025550101", substring = true).assertExists()
        ui.onNodeWithText("To: +12025550102", substring = true).assertExists()
        ui.onNodeWithText("Hello Alex.").assertExists()
        ui.onNodeWithText("Hello Sam.").assertExists()
        ui.onNodeWithText("Minimum delay: 37 seconds", substring = true).assertExists()
        ui.onNode(hasText("I verified every number", substring = true) and isToggleable())
            .performScrollTo().performClick()
        ui.onNodeWithText("Send 2 separate SMS").performScrollTo().assertIsNotEnabled()
        ui.onNode(hasText("I checked my actual carrier/plan", substring = true) and isToggleable())
            .performScrollTo().performClick()
        ui.onNodeWithText("Send 2 separate SMS").performScrollTo().assertIsEnabled().performClick()
        ui.onNodeWithText("Send announcement?").assertIsDisplayed()
        ui.onNodeWithText("Keep unchanged").performClick()
        ui.onNodeWithText("Send announcement?").assertDoesNotExist()
    }
}
