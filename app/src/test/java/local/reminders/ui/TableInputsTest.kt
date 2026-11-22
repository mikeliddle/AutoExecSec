package local.reminders.ui

import local.reminders.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.ZoneId

class TableInputsTest {
    @Test fun peopleImportIsEditableAndPreservesSavedIdentityWhenChangingNumber() {
        val saved = Recipient("existing_id", "Mary Ann Example", "+12025550101", "Mary Ann", "Example")
        val rows = TableInputs.importPeople("FirstName,LastName,phone,discard\nMary Ann,Example,2025550101,not-kept", "US", listOf(saved))
        val result = TableInputs.people(rows.map { it.copy(phone = "2025550102") }, "US", listOf(saved))
        assertTrue(result.problems.isEmpty())
        assertEquals(saved.copy(phone = "+12025550102"), result.values.single())
    }

    @Test fun legacyNamesAreNeverSplitOrGivenAnInventedSurname() {
        val row = TableInputs.importPeople("id,name,phone\nalex,Alex Multi Word,2025550101", "US", emptyList()).single()
        val result = TableInputs.people(listOf(row), "US", emptyList()).values.single()
        assertEquals("Alex Multi Word", result.name)
        assertNull(result.firstName)
        assertNull(result.lastName)
        val explicit = TableInputs.people(listOf(row.copy(firstName = "Alex", lastName = "Multi Word")), "US", emptyList())
        assertEquals("Multi Word", explicit.values.single().lastName)
    }

    @Test fun peopleErrorsIdentifyCellsAndConflictingRowsWithoutPartialSave() {
        val bad = PersonInput(firstName = "Alex", phone = "not-a-number")
        val result = TableInputs.people(listOf(PersonInput(), bad), "US", emptyList())
        assertTrue(result.values.isEmpty())
        assertEquals(setOf("phone", "lastName"), result.problems.map { it.field }.toSet())
        assertTrue(result.problems.all { it.key == bad.key })
        val person = Recipient("alex", "Alex Example", "+12025550101", "Alex", "Example")
        val conflict = TableInputs.people(listOf(PersonInput.from(person),
            PersonInput.from(person).copy(phone = "2025550102")), "US", listOf(person))
        assertTrue(conflict.problems.any { it.key.isEmpty() })
    }

    @Test fun groupImportRetainsMembersAndCapturedZoneThroughTableEditing() {
        val rows = TableInputs.importAppointments("Date,Time,Name,Name2\n2027-01-12,14:00,Alex Example,Sam Example",
            ZoneId.of("America/New_York"))
        val edited = rows.single().copy(time = "6:30 PM")
        val result = TableInputs.appointments(listOf(edited))
        assertTrue(result.problems.isEmpty())
        assertEquals(listOf("Alex Example", "Sam Example"), result.values.single().hints)
        assertEquals("America/New_York", result.values.single().zone)
        assertTrue(result.values.single().nameOnly)
        assertEquals(18, Importer.appointment(result.values.single()).hour)
        val legacy = TableInputs.importAppointments("recipient,date,time,zone\nalex,2027-01-12,14:00,-04:00", ZoneId.of("UTC")).single()
        assertFalse(legacy.nameOnly)
        assertEquals("-04:00", legacy.zone)
    }

    @Test fun appointmentErrorsPointToDateTimeMemberAndDstGap() {
        val row = AppointmentInput(date = "bad", time = "14:00", names = listOf("Alex", ""), zone = "UTC")
        val invalid = TableInputs.appointments(listOf(row))
        assertEquals(setOf("date", "name1"), invalid.problems.map { it.field }.toSet())
        assertTrue(TableInputs.appointments(listOf(row.copy(date = "2027-01-12", time = "25:00")))
            .problems.any { it.field == "time" })
        val gap = row.copy(date = "2027-03-14", time = "02:30", names = listOf("Alex"), zone = "America/New_York")
        assertTrue(TableInputs.appointments(listOf(gap)).problems.any { it.field == "time" && it.detail.contains("daylight") })
    }
}
