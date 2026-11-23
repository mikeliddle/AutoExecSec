package local.reminders.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import local.reminders.ReminderApplication
import local.reminders.domain.Recipient
import local.reminders.domain.TextTemplate
import local.reminders.runtime.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

/** In-memory draft/fake-preview UI only: no storage, permissions, approvals or dispatch. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ReminderApplication::class, qualifiers = "w400dp-h850dp")
@ConscryptMode(ConscryptMode.Mode.OFF)
class TableUiTest {
    @get:Rule val ui = createComposeRule()

    @Test fun scheduledTemplateLoadsFromLibraryAndCanBeCleared() {
        ui.setContent {
            val state = androidx.compose.runtime.remember {
                androidx.compose.runtime.mutableStateOf(UiState(loading = false,
                    textTemplates = listOf(TextTemplate("visit", "Office visit", "{FirstName}, come to {room} at {time}."))))
            }
            ReminderTheme {
                Column {
                    ScheduleTemplatePicker(state.value, { state.value = state.value.copy(scheduledTemplate = it) }, {})
                }
            }
        }
        ui.onNodeWithText("Load template").performClick()
        ui.onNodeWithText("Office visit").performClick()
        ui.onNodeWithText("room").performTextInput("A2")
        ui.onNodeWithText("room").assertTextContains("A2")
        ui.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        ui.onNodeWithText("Use defaults").performClick()
        ui.onNodeWithText("room").assertDoesNotExist()
    }

    @Test fun queueOffersNextPreparationStepAndPhoneSetupReturnsToQueue() {
        val model = ReminderViewModel(ApplicationProvider.getApplicationContext())
        val state = androidx.compose.runtime.mutableStateOf(UiState(loading = false))
        ui.setContent { ReminderTheme { ReminderApp(model, state.value, false, {}) } }
        ui.onNodeWithText("Add people").assertIsDisplayed().performClick()
        ui.onNodeWithText("First name · 1").assertExists()
        ui.onNodeWithContentDescription("Go back").assertDoesNotExist()
        ui.onNode(hasText("Queue") and hasClickAction()).performClick()
        ui.onNodeWithText("Set up sending").assertIsDisplayed().performClick()
        ui.onNodeWithText("Setup needs attention").assertIsDisplayed()
        ui.onNodeWithContentDescription("Go back").performClick()
        ui.runOnIdle {
            state.value = state.value.copy(recipients = listOf(Recipient("alex", "Alex Example", "+12025550101")))
        }
        ui.onNodeWithText("Add or edit appointments").assertIsDisplayed().performClick()
        ui.onNodeWithText("Date · 1").assertExists()
    }

    @Test fun brandedNavigationTableCrudErrorsAndImportReplacement() {
        val model = ReminderViewModel(ApplicationProvider.getApplicationContext())
        ui.setContent {
            val state by model.state.collectAsState()
            ReminderTheme { ReminderApp(model, state.copy(loading = false), false, {}) }
        }
        ui.onNodeWithText("Auto ExecSec").assertIsDisplayed()
        ui.onNode(hasText("People") and hasClickAction()).performClick()
        ui.onNodeWithText("First name · 1").performTextInput("Unsaved")
        ui.onNodeWithText("Add person").performScrollTo().performClick()
        ui.onNodeWithContentDescription("Remove Person 2").performScrollTo().performClick()
        ui.onNodeWithText("Save people").performScrollTo().performClick()
        ui.onNodeWithText("Enter a last name.").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Import").performScrollTo().performClick()
        ui.onNodeWithText("People CSV").performScrollTo().performTextInput("FirstName,LastName,phone\nAlex,Example,2025550101")
        ui.onNodeWithText("Load rows").performScrollTo().performClick()
        ui.onNodeWithText("Replace unsaved rows?").assertIsDisplayed()
        ui.onNodeWithText("Keep unchanged").performClick()
        ui.onNodeWithText("First name · 1").performScrollTo().assertTextContains("Unsaved")
        ui.onNode(hasText("Schedule") and hasClickAction()).performClick()
        ui.onNodeWithContentDescription("Go back").assertDoesNotExist()
        ui.onNodeWithText("Date · 1").performTextInput("bad")
        ui.onNodeWithText("Review messages").performScrollTo().performClick()
        ui.onNodeWithText("Use yyyy-MM-dd.").performScrollTo().assertIsDisplayed()
        ui.onNode(hasText("Announce") and hasClickAction()).performClick()
        ui.onNodeWithText("Announcements").assertIsDisplayed()
        ui.onNodeWithContentDescription("Go back").assertDoesNotExist()
        ui.onNodeWithText("Announcement message").assertExists()
        ui.onNodeWithText("Send 1 separate SMS").assertDoesNotExist()
        ui.onNode(hasText("People") and hasClickAction()).performClick()
        ui.onNodeWithText("First name · 1").performScrollTo().assertTextContains("Unsaved")
        ui.onNodeWithText("Import").performScrollTo().performClick()
        ui.onNodeWithText("People CSV").performScrollTo().performTextInput("FirstName,LastName,phone\nAlex,Example,2025550101")
        ui.onNodeWithText("Load rows").performScrollTo().performClick()
        ui.onNodeWithText("Replace rows").performClick()
        ui.onNodeWithText("First name · 1").performScrollTo().assertTextContains("Alex")
        ui.onNodeWithText("Phone · 1").assertTextContains("+12025550101")
    }
    @Test
    @Config(qualifiers = "w360dp-h800dp")
    fun largeFontPhoneKeepsTableCellsAndApprovalActionsReachable() {
        val model = ReminderViewModel(ApplicationProvider.getApplicationContext())
        ui.setContent {
            val state by model.state.collectAsState()
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                ReminderTheme { ReminderApp(model, state.copy(loading = false), false, {}) }
            }
        }
        ui.onNode(hasText("People") and hasClickAction()).performClick()
        ui.onNodeWithText("First name · 1").performScrollTo().performTextInput("Alex")
        ui.onNodeWithText("Last name · 1").performScrollTo().performTextInput("Example")
        ui.onNodeWithText("Phone · 1").performScrollTo().performTextInput("2025550101")
        ui.onNodeWithText("Save people").performScrollTo().performClick()
        ui.onNodeWithText("Save people?").assertIsDisplayed()
        ui.onNodeWithText("Keep unchanged").performClick()
        ui.onNode(hasText("Schedule") and hasClickAction()).performClick()
        ui.onNodeWithText("Date · 1").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Time · 1").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Review messages").performScrollTo().assertIsDisplayed()
    }

    @Test
    @Config(qualifiers = "w840dp-h1100dp")
    fun tabletUsesSameEditableRowsAndImportAction() {
        val model = ReminderViewModel(ApplicationProvider.getApplicationContext())
        ui.setContent {
            val state by model.state.collectAsState()
            ReminderTheme { ReminderApp(model, state.copy(loading = false), false, {}) }
        }
        ui.onNode(hasText("People") and hasClickAction()).performClick()
        ui.onNodeWithText("First name · 1").performTextInput("Alex")
        ui.onNodeWithText("Last name · 1").performTextInput("Example")
        ui.onNodeWithText("Phone · 1").performTextInput("2025550101")
        ui.onNodeWithText("Import").assertIsDisplayed()
        ui.onNodeWithContentDescription("Remove Person 1").assertHeightIsAtLeast(48.dp)
        ui.onNodeWithText("Save people").performScrollTo().performClick()
        ui.onNodeWithText("Save people?").assertIsDisplayed()
        ui.onNodeWithText("Keep unchanged").performClick()
    }

    @Test fun announcementAcknowledgmentsStillRequireFinalConfirmation() {
        val model = ReminderViewModel(ApplicationProvider.getApplicationContext())
        val preview = BroadcastPreview("fake", listOf(
            BroadcastMessage(Recipient("alex", "Alex Example", "+12025550101"), "Hello Alex.", 1)),
            "Hello {FirstName}.", 17, 37, 1_800_000_000_000, 1_800_086_400_000)
        val state = UiState(loading = false, settings = AppSettings(subscriptionId = 17),
            capabilities = Capabilities(emptyList(), listOf(SimChoice(17, "Test SIM"))), broadcastPreview = preview)
        ui.setContent {
            ReminderTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) { BroadcastScreen(model, state) }
            }
        }
        ui.onNodeWithText("Send 1 separate SMS").performScrollTo().assertIsNotEnabled()
        ui.onNode(hasText("I verified every number", substring = true) and isToggleable()).performScrollTo().performClick()
        ui.onNodeWithText("Send 1 separate SMS").performScrollTo().assertIsNotEnabled()
        ui.onNode(hasText("I checked my actual carrier/plan", substring = true) and isToggleable()).performScrollTo().performClick()
        ui.onNodeWithText("Send 1 separate SMS").performScrollTo().assertIsEnabled().performClick()
        ui.onNodeWithText("Send announcement?", substring = false).assertIsDisplayed()
        ui.onNodeWithText("Keep unchanged").performClick()
        ui.onNodeWithText("Send announcement?", substring = false).assertDoesNotExist()
    }
}
