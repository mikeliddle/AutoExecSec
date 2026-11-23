package local.reminders.ui

import android.app.NotificationManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import local.reminders.MainActivity
import local.reminders.ReminderApplication
import local.reminders.runtime.Platform
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate

/** Opt-in connected UI validation. Adds only synthetic people; never grants access or approves SMS. */
@RunWith(AndroidJUnit4::class)
class DeviceWorkflowTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val runtime get() = (ui.activity.application as ReminderApplication).runtime

    private fun navigate(label: String) {
        ui.onNode(hasText(label) and hasClickAction()).performClick()
    }

    private fun field(label: String, value: String) {
        ui.onNodeWithText(label).performScrollTo().performTextReplacement(value)
    }

    private fun button(label: String) {
        ui.onNodeWithText(label).performScrollTo().performClick()
    }

    private fun importRows(table: String, csv: String) {
        if (ui.onAllNodesWithText("$table CSV").fetchSemanticsNodes().isEmpty())
            button("Import")
        field("$table CSV", csv)
        button("Load rows")
        if (ui.onAllNodesWithText("Replace unsaved rows?").fetchSemanticsNodes().isNotEmpty())
            button("Replace rows")
    }

    private fun awaitText(text: String) {
        ui.waitUntil(15_000) {
            ui.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test fun syntheticImportPreviewEditingAndMissingCapabilityGuard() {
        awaitText("Automatic sending is blocked")
        val before = runBlocking { runtime.store.reminders() }
        navigate("People")
        importRows("People", "id,name,phone\nhuawei_alex,Alex Device Example,+12025550101\nhuawei_sam,Sam Device Example,+12025550102")
        button("Save people")
        ui.onNode(hasText("Save people") and hasAnyAncestor(isDialog()))
            .assertIsDisplayed().performClick()
        ui.waitUntil(15_000) { runBlocking { runtime.store.recipients() }.count { it.id.startsWith("huawei_") } == 2 }
        ui.onNodeWithText("Alex Device Example").performScrollTo().assertIsDisplayed()

        navigate("Schedule")
        importRows("Schedule", "recipient,date,time,zone\nhuawei_alex,not-a-date,14:00,UTC")
        awaitText("Row 2:")
        ui.onNodeWithText("Date · 1").assertExists()

        val date = LocalDate.now().plusDays(7)
        importRows("Schedule", "recipient,date,time,zone,discarded\nhuawei_alex,$date,14:00,UTC,UNRELATED_SOURCE_FIELD")
        button("Review messages")
        awaitText("Check every text")
        ui.onNodeWithText("To: +12025550101").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Hey Alex. This is your reminder that you have an appointment with John at 2:00 PM today.")
            .performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("1 SMS segment(s)").performScrollTo().assertIsDisplayed()
        ui.onAllNodes(hasText("UNRELATED_SOURCE_FIELD", substring = true)).assertCountEquals(0)
        ui.onNodeWithText("Approval is blocked").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Approve 1 reminders").performScrollTo().assertIsNotEnabled()

        button("Change appointment")
        field("Time · HH:mm or h:mm AM/PM", "18:30")
        ui.onNodeWithText("Apply appointment edits first").performScrollTo().assertIsDisplayed()
        button("Update preview")
        awaitText("6:30 PM tonight.")
        ui.onNodeWithText("Approve 1 reminders").performScrollTo().assertIsNotEnabled()
        button("Back to table")
        awaitText("Date · 1")

        importRows("Schedule", "recipient,date,time,zone\nmissing_person,$date,14:00,UTC")
        button("Review messages")
        awaitText("Resolve recipient")
        button("Resolve recipient")
        ui.onNodeWithText("Sam Device Example\n+12025550102").performClick()
        awaitText("To: +12025550102")
        ui.onNodeWithText("Hey Sam. This is your reminder that you have an appointment with John at 2:00 PM today.")
            .performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Approve 1 reminders").performScrollTo().assertIsNotEnabled()
        button("Back to table")

        ui.onNodeWithContentDescription("Open setup").performClick()
        awaitText("Setup needs attention")
        button("Choose an active SIM")
        ui.onNodeWithText("Grant SIM access and insert/enable a SIM, then refresh. No available SIM can be selected.")
            .assertIsDisplayed()
        ui.onNodeWithText("Close").performClick()
        button("Night-before prompt")
        ui.onNodeWithText("Evening local time · HH:mm").performScrollTo().assertTextContains("20:00")
        navigate("Queue")
        awaitText("Automatic sending is blocked")
        assertEquals(before, runBlocking { runtime.store.reminders() })

        ui.activityRule.scenario.recreate()
        awaitText("Automatic sending is blocked")
        navigate("People")
        ui.onNodeWithText("Alex Device Example").performScrollTo().assertIsDisplayed()
        ui.onNodeWithText("Sam Device Example").performScrollTo().assertIsDisplayed()

        Platform(ui.activity).notifyPrompt()
        val notifications = requireNotNull(ui.activity.getSystemService(NotificationManager::class.java))
        notifications.activeNotifications.single { it.id == 11 }.notification.contentIntent.send()
        awaitText("Date · 1")
        ui.onNodeWithText("Date · 1").performScrollTo().assertIsDisplayed()
    }
}
