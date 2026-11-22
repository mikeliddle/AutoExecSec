package local.reminders.ui

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ApplicationProvider
import local.reminders.ReminderApplication
import local.reminders.domain.TextTemplate
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ReminderApplication::class, qualifiers = "w400dp-h850dp")
@ConscryptMode(ConscryptMode.Mode.OFF)
class TextTemplatesUiTest {
    @get:Rule val ui = createComposeRule()

    @Test fun templatesEntryIsAccessibleWithoutChangingSendDestinations() {
        val model = ReminderViewModel(ApplicationProvider.getApplicationContext())
        ui.setContent { ReminderTheme { ReminderApp(model, UiState(loading = false), false, {}) } }
        ui.onNodeWithContentDescription("Open text templates").assertIsDisplayed().performClick()
        ui.onNodeWithText("Your first reusable template").assertIsDisplayed()
        ui.onNode(hasText("Schedule") and hasClickAction()).assertIsDisplayed()
        ui.onNode(hasText("Announcements") and hasClickAction()).assertIsDisplayed()
    }

    @Test fun repeatedFieldLivePreviewAndSensitiveCopyWithoutSending() {
        ui.setContent {
            ReminderTheme {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    TextTemplatesScreen(listOf(TextTemplate("x", "Greeting", "Hello {name}; thanks {name}.")),
                        false, {}, {})
                }
            }
        }
        ui.onNodeWithText("Greeting").performClick()
        ui.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        ui.onNodeWithText("Copy output").performScrollTo().assertIsNotEnabled()
        ui.onNodeWithText("name").performScrollTo().performTextInput("Alex")
        ui.onNodeWithText("Hello Alex; thanks Alex.").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Copy output").performScrollTo().performClick()
        val context = ApplicationProvider.getApplicationContext<Context>()
        val clip = context.getSystemService(ClipboardManager::class.java).primaryClip!!
        assertEquals("Hello Alex; thanks Alex.", clip.getItemAt(0).text.toString())
        assertTrue(clip.description.extras!!.getBoolean("android.content.extra.IS_SENSITIVE"))
    }

    @Test fun createEditDeleteAndVariableChangeAtLargeFont() {
        ui.setContent {
            var templates by remember { mutableStateOf(emptyList<TextTemplate>()) }
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                ReminderTheme {
                    Column(Modifier.verticalScroll(rememberScrollState())) {
                        TextTemplatesScreen(templates, false,
                            { template -> templates = templates.filterNot { it.id == template.id } + template },
                            { id -> templates = templates.filterNot { it.id == id } })
                    }
                }
            }
        }
        ui.onNodeWithText("Create template").performClick()
        ui.onNodeWithText("Template name").performTextInput("Meeting")
        ui.onNodeWithText("Template text").performScrollTo().performTextInput("At {time}, {name}.")
        ui.onNodeWithText("Save template").performScrollTo().performClick()
        ui.onNodeWithText("Back to templates").performScrollTo().performClick()
        ui.onAllNodes(hasSetTextAction()).assertCountEquals(2)
        ui.onNodeWithText("Edit template").performScrollTo().performClick()
        ui.onNodeWithText("Template text").performScrollTo().performTextReplacement("At {time}.")
        ui.onNodeWithText("Save template").performScrollTo().performClick()
        ui.onNodeWithText("Back to templates").performScrollTo().performClick()
        ui.onAllNodes(hasSetTextAction()).assertCountEquals(1)
        ui.onNodeWithText("Delete template").performScrollTo().performClick()
        ui.onNode(hasText("Delete template") and hasAnyAncestor(isDialog())).performClick()
        ui.onNodeWithText("Your first reusable template").assertExists()
    }
}
