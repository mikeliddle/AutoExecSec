package local.reminders.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class DomainTest {
    private val person = Recipient("alex", "Alex Example", "+12025550101")
    private fun row(date: String = "2027-01-12", time: String = "14:00", zone: String = "America/New_York") =
        ScheduleRow(2, "alex", date, time, zone)

    @Test fun csvQuotesBomAndExtraColumnsDiscarded() {
        val input = "\uFEFFid,name,phone,notes\r\nalex,\"Alex, Example\",2025550101,discard me\r\n"
        val recipients = Importer.recipients(input, "US")
        assertEquals(listOf(Recipient("alex", "Alex, Example", "+12025550101")), recipients)
        assertEquals(listOf(listOf("a", "b"), listOf("x\"y", "z")), Csv.parse("a,b\n\"x\"\"y\",z"))
    }

    @Test fun csvMalformedAndOversizedRejected() {
        rejects { Csv.parse("a,b\n\"unfinished") }
        rejects { Csv.parse("a,b\n\"x\"q,z") }
        rejects { Csv.parse("a,b\n\"line\nbreak\",z") }
        rejects { Csv.records("a,a\nx,y", setOf("a")) }
        rejects { Csv.parse("x".repeat(Limits.MAX_IMPORT_BYTES + 1)) }
        rejects { Importer.recipients("id,name,phone\nalex,Alex,2025550101\nalex,Again,2025550102", "US") }
    }

    @Test fun phoneCountryAndServiceCodeGuards() {
        assertEquals("+12025550101", Importer.normalizePhone("(202) 555-0101", "US"))
        rejects { Importer.normalizePhone("*123#", "US") }
        rejects { Importer.normalizePhone("123", "US") }
        rejects { Importer.normalizePhone("2025550101 ext 2", "US") }
        rejects { Importer.normalizePhone("2025550101", "XX") }
        rejects { Importer.recipient("bad id", "Alex", "2025550101", "US") }
    }

    @Test fun idWinsAndAmbiguousExactNamesNeedChoice() {
        val people = listOf(person, person.copy(id = "another"), Recipient("Alex Example", "Different", "+12025550102"))
        assertEquals(listOf(person), Importer.candidates(row(), people))
        assertEquals(2, Importer.candidates(row().copy(hint = "Alex Example"), people.take(2)).size)
        assertEquals("Different", Importer.candidates(row().copy(hint = "Alex Example"), people).single().name)
        assertTrue(Importer.candidates(row().copy(hint = "alex example"), people).isEmpty())
    }

    @Test fun dateTimeStrictnessAndDst() {
        assertEquals(14, Importer.appointment(row(time = "2:00 PM")).hour)
        assertEquals(0, Importer.appointment(row(time = "12:00 AM")).hour)
        assertEquals(12, Importer.appointment(row(time = "12:00 PM")).hour)
        rejectsTime { Importer.appointment(row(time = "24:00")) }
        rejectsTime { Importer.appointment(row(date = "2027-02-30")) }
        rejectsTime { Importer.appointment(row(time = "2 PM")) }
        rejects { Importer.appointment(row("2027-03-14", "02:30")) }
        rejects { Importer.appointment(row("2027-11-07", "01:30")) }
        assertEquals(ZoneOffset.of("-04:00"), Importer.appointment(row("2027-11-07", "01:30", "-04:00")).offset)
    }

    @Test fun invalidScheduleRowsAreReportedWithoutPartialApproval() {
        val parsed = Importer.schedule("recipient,date,time,zone,private\nalex,2027-01-12,14:00,America/New_York,discard\nalex,2027-02-30,14:00,America/New_York,discard")
        assertEquals(1, parsed.rows.size)
        assertEquals(3, parsed.problems.single().line)
    }

    @Test fun templateExactPreviewAndSixPmBoundary() {
        assertEquals("Hey Alex. This is your reminder that you have an appointment with John at 2:00 PM today.",
            Templates.render(Limits.DEFAULT_TEMPLATE, person.name, Importer.appointment(row())))
        assertEquals("Alex 5:59 PM today", Templates.render("{firstName} {time} {dayPeriod}", person.name, Importer.appointment(row(time = "17:59"))))
        assertEquals("Alex 6:00 PM tonight", Templates.render("{firstName} {time} {dayPeriod}", person.name, Importer.appointment(row(time = "18:00"))))
        rejects { Templates.validate("{unknown}") }
        rejects { Templates.validate("{firstName") }
        rejects { Templates.validate("") }
    }

    @Test fun leadTimeAndFrozenDuplicateFingerprint() {
        val now = Instant.parse("2027-01-12T12:00:00Z")
        val a = Scheduling.draft(row(), person, Limits.DEFAULT_TEMPLATE, 120, now)
        assertEquals(Instant.parse("2027-01-12T17:00:00Z").toEpochMilli(), a.sendAt)
        assertEquals(a.id, Scheduling.draft(row(time = "2:00 PM"), person.copy(name = "Changed"),
            "{firstName}", 60, now).id)
        rejects { Scheduling.draft(row(), person, Limits.DEFAULT_TEMPLATE, 0, now) }
        rejects { Scheduling.draft(row(), person, Limits.DEFAULT_TEMPLATE, 120, Instant.parse("2027-01-12T17:00:00Z")) }
        rejects { Scheduling.draft(row(time = "01:00"), person, Limits.DEFAULT_TEMPLATE, 120, Instant.parse("2027-01-11T00:00:00Z")) }
    }

    @Test fun graceAndAppointmentBoundaries() {
        val start = 1_000_000L
        val appointment = start + 7_200_000
        assertFalse(Scheduling.dispatchable(start, appointment, start - 1))
        assertTrue(Scheduling.dispatchable(start, appointment, start))
        assertTrue(Scheduling.dispatchable(start, appointment, start + Limits.GRACE_MILLIS))
        assertFalse(Scheduling.dispatchable(start, appointment, start + Limits.GRACE_MILLIS + 1))
        assertTrue(Scheduling.expired(start, appointment, start + Limits.GRACE_MILLIS + 1))
        assertFalse(Scheduling.dispatchable(start, appointment, appointment))
        assertTrue(Scheduling.expired(start, appointment, appointment))
        assertFalse(Scheduling.dispatchable(start, appointment, start - 86_400_000))
    }

    @Test fun nightBeforeIndependentOfAppointmentsAndCoverage() {
        val now = ZonedDateTime.parse("2027-01-11T19:00:00-05:00[America/New_York]")
        assertEquals(now.withHour(20), Scheduling.nextPrompt(now, PromptSettings(), emptySet()))
        val tomorrow = now.toLocalDate().plusDays(1)
        assertEquals(now.plusDays(1).withHour(20), Scheduling.nextPrompt(now, PromptSettings(), setOf(tomorrow)))
        val friday = ZonedDateTime.parse("2027-01-15T19:00:00-05:00[America/New_York]")
        assertEquals(DayOfWeek.SUNDAY, Scheduling.nextPrompt(friday, PromptSettings(), emptySet())!!.dayOfWeek)
        assertEquals(friday.withHour(20), Scheduling.nextPrompt(friday,
            PromptSettings(weekdays = emptySet(), oneOffDates = setOf(friday.toLocalDate().plusDays(1))), emptySet()))
        assertNull(Scheduling.nextPrompt(now, PromptSettings(weekdays = emptySet()), emptySet()))
    }

    @Test fun promptClockAndDstResolution() {
        val zone = ZoneId.of("America/New_York")
        val now = ZonedDateTime.of(2027, 3, 14, 0, 0, 0, 0, zone)
        val next = Scheduling.nextPrompt(now, PromptSettings(setOf(DayOfWeek.MONDAY), LocalTime.of(2, 30)), emptySet())!!
        assertEquals(3, next.hour)
        assertEquals(30, next.minute)
        val after = ZonedDateTime.of(2027, 1, 11, 20, 0, 0, 0, zone)
        assertTrue(Scheduling.nextPrompt(after, PromptSettings(), emptySet())!!.isAfter(after))
    }

    private fun rejects(block: () -> Unit) {
        try { block(); fail("Expected validation failure") } catch (_: IllegalArgumentException) { }
    }
    private fun rejectsTime(block: () -> Unit) {
        try { block(); fail("Expected invalid date/time") }
        catch (_: DateTimeException) { }
        catch (_: IllegalArgumentException) { }
    }
}
