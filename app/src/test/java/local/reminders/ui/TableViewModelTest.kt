package local.reminders.ui

import androidx.test.core.app.ApplicationProvider
import local.reminders.ReminderApplication
import local.reminders.domain.Limits
import local.reminders.domain.Recipient
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = ReminderApplication::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class TableViewModelTest {
    private fun model() = ReminderViewModel(ApplicationProvider.getApplicationContext())

    @Test fun editingSavedPeopleRespectsRowLimitWithoutDroppingDrafts() {
        val model = model()
        repeat(Limits.MAX_BATCH) { index ->
            if (index > 0) model.addPerson()
            model.updatePerson(model.state.value.peopleInput.last().copy(firstName = "Draft $index"))
        }
        val before = model.state.value.peopleInput
        model.editPerson(Recipient("saved", "Alex Example", "+12025550101", "Alex", "Example"))
        assertEquals(before, model.state.value.peopleInput)
        assertNotNull(model.state.value.feedback)
    }

    @Test fun peopleCrudAndFailedImportKeepUnsavedInputUntilConsciousReplacement() {
        val model = model()
        val row = model.state.value.peopleInput.single().copy(firstName = "Unsaved")
        model.updatePerson(row)
        model.addPerson()
        model.removePerson(model.state.value.peopleInput.last().key)
        model.loadPeopleTable("FirstName,LastName,phone\nAlex,Example,invalid")
        assertEquals(listOf(row), model.state.value.peopleInput)
        assertNotNull(model.state.value.feedback)
        model.loadPeopleTable("FirstName,LastName,phone\nAlex,Example,2025550101")
        assertEquals(listOf(row), model.state.value.peopleInput)
        assertEquals("Alex", model.state.value.incomingPeople!!.single().firstName)
        model.resolveTableImport(true, false)
        assertEquals(listOf(row), model.state.value.peopleInput)
        model.loadPeopleTable("FirstName,LastName,phone\nAlex,Example,2025550101")
        model.resolveTableImport(true, true)
        assertEquals("Alex", model.state.value.peopleInput.single().firstName)
        assertTrue(model.validatePeopleTable())
        model.removePerson(model.state.value.peopleInput.single().key)
        assertTrue(model.state.value.peopleInput.single().blank)
    }

    @Test fun appointmentCrudFailedImportAndReviewBackPreserveZoneAndEdits() {
        val model = model()
        val row = model.state.value.appointmentInput.single().copy(date = "2027-01-12", time = "14:00",
            names = listOf("Unresolved Example"), zone = "-04:00")
        model.updateAppointmentInput(row)
        model.addAppointment()
        model.removeAppointment(model.state.value.appointmentInput.last().key)
        model.loadAppointmentTable("Date,Time,Name\ninvalid,14:00,Alex Example")
        assertEquals(listOf(row), model.state.value.appointmentInput)
        model.loadAppointmentTable("Date,Time,Name\n2027-01-13,15:00,Alex Example")
        assertNotNull(model.state.value.incomingAppointments)
        model.resolveTableImport(false, false)
        assertEquals(listOf(row), model.state.value.appointmentInput)
        model.reviewTable()
        assertEquals("-04:00", model.state.value.review.single().source.zone)
        assertNull(model.state.value.review.single().recipientIds.single())
        assertThrows(IllegalArgumentException::class.java) { model.drafts() }
        model.backToTable()
        assertEquals("2027-01-12", model.state.value.appointmentInput.single().date)
        assertTrue(model.loadAppointmentTable("Date,Time,Name\n2027-01-13,15:00,Alex Example"))
        model.resolveTableImport(false, true)
        assertEquals("2027-01-13", model.state.value.appointmentInput.single().date)
        model.removeAppointment(model.state.value.appointmentInput.single().key)
        assertTrue(model.state.value.appointmentInput.single().blank)
        model.reviewTable()
        assertTrue(model.state.value.review.isEmpty())
        assertTrue(model.state.value.appointmentProblems.isNotEmpty())
    }
}
