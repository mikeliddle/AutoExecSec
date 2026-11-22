package local.reminders.ui

import android.os.ParcelFileDescriptor
import android.os.Bundle
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import local.reminders.MainActivity
import local.reminders.ReminderApplication
import local.reminders.domain.*
import local.reminders.runtime.MmsPayloadProvider
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.util.UUID

/** Opt-in connected preview validation. Never approves, submits, grants access, or clears data. */
@RunWith(AndroidJUnit4::class)
class GroupWorkflowTest {
    @get:Rule val ui = createAndroidComposeRule<MainActivity>()
    private val runtime get() = (ui.activity.application as ReminderApplication).runtime

    private fun await(text: String) = ui.waitUntil(20_000) {
        ui.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()
    }
    private fun button(label: String) = ui.onNodeWithText(label).performScrollTo().performClick()
    private fun field(label: String, text: String) =
        ui.onNodeWithText(label).performScrollTo().performTextReplacement(text)

    @Test fun groupPreviewSurnameBlockAndMemoryStreamWithoutSending() {
        await("Your reminder queue")
        val before = runBlocking { runtime.store.reminders() }
        check(before.none { it.state in setOf(ReminderState.PENDING, ReminderState.SUBMITTING, ReminderState.UNCERTAIN) }) {
            "This opt-in test requires no active or uncertain reminders."
        }
        val original = runBlocking { runtime.store.settings() }
        val suffix = UUID.randomUUID().toString().take(8)
        val people = listOf(
            Recipient("check_alex_$suffix", "Alex Group Example", "+12025550101", "Alex", "Group Example"),
            Recipient("check_sam_$suffix", "Sam Other Example", "+12025550102", "Sam", "Other Example"))
        try {
            runBlocking {
                people.forEach { runtime.saveRecipient(it) }
                runtime.saveSettings(original.copy(groupTemplates = original.groupTemplates + (2 to "Hello {LastName}.")))
            }
            ui.onNodeWithContentDescription("Refresh outcomes and capability checks").performClick()
            ui.onNode(hasText("Schedule") and hasClickAction()).performClick()
            button("Import")
            field("Schedule CSV", "Date,Time,Name,Name2\n${LocalDate.now().plusDays(7)},14:00,${people[0].name},${people[1].name}")
            button("Load rows")
            button("Review messages")
            await("Surnames do not match")
            ui.onNodeWithText("Approve 1 reminders").performScrollTo().assertIsNotEnabled()
            button("Back to table")
            runBlocking {
                runtime.saveSettings(original.copy(groupTemplates = original.groupTemplates + (2 to "Hello {FirstName}.")))
            }
            ui.onNodeWithContentDescription("Refresh outcomes and capability checks").performClick()
            button("Review messages")
            await("Hello Alex, Sam.")
            ui.onNodeWithText("To: +12025550101").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("To: +12025550102").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("One shared group MMS", substring = true).performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("Shared conversation").performScrollTo().assertIsDisplayed()
            ui.onNodeWithText("Approve 1 reminders").performScrollTo().assertIsNotEnabled()
            button("Back to table")
            val payload = MmsPdu.encode(people.map { it.phone }, "Synthetic memory stream only", "stream-test")
            val expected = payload.copyOf()
            val descriptor = MmsPayloadProvider.openMemory(ui.activity, payload)
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                assertEquals(expected.size.toLong(), stream.channel.size())
                assertArrayEquals(expected, stream.readBytes())
            }
            val caps = runtime.capabilities()
            val mms = caps.mms[original.subscriptionId]
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "GROUP_CHECK ready=${caps.ready}; mmsKnown=${mms != null}; mmsAllowed=${mms?.blocker == null && mms != null}; maxMembers=${mms?.maxRecipients}; maxBytes=${mms?.maxBytes}\n")
            })
            InstrumentationRegistry.getInstrumentation().sendStatus(0, Bundle().apply {
                putString("stream", "GROUP_HISTORY total=${before.size}; groups=${before.count { it.group }}; sentGroups=${before.count { it.group && it.state == ReminderState.SENT }}; attemptedGroups=${before.count { it.group && it.attemptId != null }}; deliveredSms=${before.count { !it.group && it.delivered }}; originalLeadMinutes=${original.leadMinutes}\n")
            })
            assertEquals(before, runBlocking { runtime.store.reminders() })
        } finally {
            runBlocking {
                runtime.saveSettings(original)
                people.forEach { runtime.deleteRecipient(it.id) }
            }
        }
    }
}
