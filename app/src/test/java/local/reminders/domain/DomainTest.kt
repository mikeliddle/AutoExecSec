package local.reminders.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.TimeZone

class DomainTest {
    private val person = Recipient("alex", "Alex Example", "+12025550101")
    private fun row(date: String = "2027-01-12", time: String = "14:00", zone: String = "America/New_York") =
        ScheduleRow(2, "alex", date, time, zone)

    @Test fun groupColumnsResolveEachExactNameAndRejectHoles() {
        val people = listOf(person, Recipient("sam", "Sam Example", "+12025550102"))
        val imported = Importer.schedule("Date,Time,Name,Name2,Name3,discard\n2027-01-12,14:00,Alex Example,Sam Example,,secret",
            ZoneId.of("UTC"))
        assertTrue(imported.problems.isEmpty())
        val group = imported.rows.single()
        assertEquals(listOf("Alex Example", "Sam Example"), group.hints)
        assertEquals(listOf(people[1]), Importer.candidates(group, people, 1))
        assertEquals(2, Importer.candidates(group, people + people[1].copy(id = "duplicate"), 1).size)
        assertTrue(Importer.schedule("Date,Time,Name,Name2,Name3\n2027-01-12,14:00,Alex Example,,Sam Example",
            ZoneId.of("UTC")).problems.isNotEmpty())
        rejects { Importer.schedule("Date,Time,Name,Name3\n2027-01-12,14:00,Alex Example,Sam Example") }
        rejects { Importer.schedule("recipient,date,time,zone,Name2\nalex,2027-01-12,14:00,UTC,Sam Example") }
    }

    @Test fun groupFirstNamesAndSharedSurnameAreExplicitAndMismatchBlocksOnlySurnameToken() {
        val people = Importer.recipients("FirstName,LastName,phone\nMary Ann,Example,2025550101\nSam,Example,2025550102", "US")
        val at = Importer.appointment(row())
        assertEquals("Hello Mary Ann, Sam / Example", Templates.render("Hello {FirstName} / {LastName}", people, at))
        assertEquals("Mary Ann, Sam / Mary Ann, Sam", Templates.render("{firstName} / {Firstname}", people, at))
        val mixed = listOf(people[0], people[1].copy(lastName = "Different", name = "Sam Different"))
        assertEquals("Hello Mary Ann, Sam", Templates.render("Hello {FirstName}", mixed, at))
        val error = assertThrows(IllegalArgumentException::class.java) { Templates.render("{LastName}", mixed, at) }
        assertTrue(error.message!!.contains("Surnames do not match"))
        rejects { Templates.render("{LastName}", listOf(person), at) }
        assertEquals("Example", Templates.render("{LastName}", listOf(people[0]), at))
    }

    @Test fun groupIdentityIsOrderIndependentButRecipientOrderAndNamesRemainFrozen() {
        val people = listOf(person, Recipient("sam", "Sam Example", "+12025550102"))
        val now = Instant.parse("2027-01-01T00:00:00Z")
        val draft = Scheduling.draft(row(), people, "Hello {FirstName}", 120, now)
        val reverse = Scheduling.draft(row(), people.reversed(), "Hello {FirstName}", 120, now)
        assertTrue(draft.group)
        assertEquals(draft.id, reverse.id)
        assertNotEquals(draft.message, reverse.message)
        assertEquals(people, draft.recipients)
        assertNotEquals(draft.id, Scheduling.draft(row(), person, "{firstName}", 120, now).id)
        rejects { Scheduling.draft(row(), listOf(person, person), "{FirstName}", 120, now) }
        rejects { Scheduling.draft(row(), listOf(person, person.copy(id = "other")), "{FirstName}", 120, now) }
    }

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

    @Test fun simplePeoplePreserveFirstNameAndStableIdentity() {
        val csv = "\uFEFFFirstName,LastName,phone,private\r\nMary Ann,Example,(202) 555-0101,discard\r\nMary Ann,Example,+12025550101,discard"
        val imported = Importer.recipients(csv, "US").single()
        assertEquals("Mary Ann Example", imported.name)
        assertEquals("Mary Ann", imported.firstName)
        assertEquals("+12025550101", imported.phone)
        assertEquals(imported, Importer.recipients(csv, "US", listOf(imported)).single())
        val draft = Scheduling.draft(row(), imported, "{firstName} {time} {dayPeriod}", 120, Instant.parse("2027-01-01T00:00:00Z"))
        assertEquals("Mary Ann 2:00 PM today", draft.message)
        val legacy = Recipient("old_id", imported.name, imported.phone)
        assertEquals("old_id", Importer.recipients(csv, "US", listOf(legacy)).single().id)
        assertEquals("Mary Ann", Importer.recipients(csv, "US", listOf(legacy)).single().firstName)
    }

    @Test fun sameNameDifferentNumbersStayDistinctAndNeedScheduleChoice() {
        val people = Importer.recipients("FirstName,LastName,phone\nAlex,Example,2025550101\nAlex,Example,2025550102", "US")
        assertEquals(2, people.map { it.id }.distinct().size)
        val schedule = Importer.schedule("Date,Time,Name\n2027-01-12,14:00,Alex Example", ZoneId.of("UTC"))
        assertEquals(2, Importer.candidates(schedule.rows.single(), people).size)
        assertTrue(Importer.candidates(schedule.rows.single().copy(hint = "alex example"), people).isEmpty())
        val idCollision = Recipient("Alex Example", "Someone Else", "+12025550103")
        assertEquals(people, Importer.candidates(schedule.rows.single(), people + idCollision))
        val changed = Importer.recipients("FirstName,LastName,phone\nAlex,Example,2025550103", "US", people).single()
        assertFalse(changed.id in people.map { it.id })
    }

    @Test fun ambiguousPeopleAndMixedFormatsFailWithoutGuessing() {
        val csv = "FirstName,LastName,phone\nAlex,Example,2025550101"
        rejects { Importer.recipients(csv, "US", listOf(person, person.copy(id = "other"))) }
        rejects { Importer.recipients("FirstName,LastName,phone\n,Example,2025550101", "US") }
        rejects { Importer.recipients("FirstName,LastName,phone\nAlex,,2025550101", "US") }
        rejects { Importer.recipients("FirstName,LastName,phone,id,name\nAlex,Example,2025550101,alex,Alex Example", "US") }
        rejects { Importer.schedule("Date,Time,Name,recipient,zone\n2027-01-12,14:00,Alex Example,alex,UTC") }
        rejects { Importer.recipients("FirstName,LastName,phone\nMary Ann,Example,2025550101\nMary,Ann Example,2025550101", "US") }
    }

    @Test fun legacyReimportPreservesExplicitFirstName() {
        val saved = Recipient("mary", "Mary Ann Example", "+12025550101", "Mary Ann")
        val same = Importer.recipients("id,name,phone\nmary,Mary Ann Example,2025550101", "US", listOf(saved)).single()
        assertEquals(saved, same)
        val renamed = Importer.recipients("id,name,phone\nmary,Jane Example,2025550101", "US", listOf(saved)).single()
        assertNull(renamed.firstName)
    }

    @Test fun simpleScheduleCapturesOnePhoneZoneAndRemainsFrozen() {
        val original = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
            val parsed = Importer.schedule("Date,Time,Name\n2027-01-12,14:00,Alex Example\n2027-01-13,6:30 PM,Alex Example")
            assertTrue(parsed.problems.isEmpty())
            assertEquals(setOf("America/New_York"), parsed.rows.map { it.zone }.toSet())
            val before = Scheduling.draft(parsed.rows.first(), person, Limits.DEFAULT_TEMPLATE, 120, Instant.parse("2027-01-01T00:00:00Z"))
            TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))
            assertEquals(before, Scheduling.draft(parsed.rows.first(), person, Limits.DEFAULT_TEMPLATE, 120, Instant.parse("2027-01-01T00:00:00Z")))
            assertEquals("America/Los_Angeles", Importer.schedule("Date,Time,Name\n2027-01-12,14:00,Alex Example").rows.single().zone)
            assertEquals("UTC", Importer.schedule("recipient,date,time,zone\nalex,2027-01-12,14:00,UTC").rows.single().zone)
        } finally { TimeZone.setDefault(original) }
    }

    @Test fun simpleScheduleRetainsStrictDatesTimesAndDstValidation() {
        val parsed = Importer.schedule("Date,Time,Name\n2027-02-30,14:00,Alex Example\n2027-01-12,24:00,Alex Example\n2027-03-14,02:30,Alex Example\n2027-11-07,01:30,Alex Example\n2027-01-12,14:00,",
            ZoneId.of("America/New_York"))
        assertTrue(parsed.rows.isEmpty())
        assertEquals(listOf(2, 3, 4, 5, 6), parsed.problems.map { it.line })
    }

    @Test fun phoneCountryAndServiceCodeGuards() {
        assertEquals("+12025550101", Importer.normalizePhone("(202) 555-0101", "US"))
        rejects { Importer.normalizePhone("*123#", "US") }
        rejects { Importer.normalizePhone("123", "US") }
        rejects { Importer.normalizePhone("2025550101 ext 2", "US") }
        rejects { Importer.normalizePhone("2025550101", "XX") }
        rejects { Importer.recipient("bad id", "Alex", "2025550101", "US") }
    }

    @Test fun localNumbersUseChosenRegionWhileInternationalPrefixIsPreserved() {
        assertEquals("+12025550101", Importer.normalizePhone("202-555-0101", "US"))
        assertEquals("+442079460018", Importer.normalizePhone("+44 20 7946 0018", "US"))
        assertEquals("+442079460018", Importer.normalizePhone("020 7946 0018", "GB"))
        rejects { Importer.normalizePhone("020 7946 0018", "US") }
        rejects { Importer.normalizePhone("2025550101 ext 3", "US") }
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
