package local.reminders.domain

import org.junit.Assert.*
import org.junit.Test

class BroadcastsTest {
    @Test fun deduplicatesNormalizedPhoneNumbersWithoutMergingDifferentNumbersOrNames() {
        val people = listOf(
            Recipient("a", "Alex", "(202) 555-0101"),
            Recipient("alias", "Another name", "+12025550101"),
            Recipient("b", "Alex", "+12025550102"))
        assertEquals(listOf(people[0].copy(phone = "+12025550101"), people[2]),
            Broadcasts.recipients(people, "US"))
    }

    @Test fun acceptsOneHundredUniqueNumbersButRejectsMoreEmptyAndInvalidSelections() {
        val people = (100..200).map { Recipient("p$it", "Person $it", "+12025550$it") }
        assertEquals(100, Broadcasts.recipients(people.take(100), "US").size)
        assertThrows(IllegalArgumentException::class.java) { Broadcasts.recipients(people, "US") }
        assertThrows(IllegalArgumentException::class.java) { Broadcasts.recipients(emptyList(), "US") }
        assertThrows(IllegalArgumentException::class.java) {
            Broadcasts.recipients(listOf(people.first().copy(phone = "*123#")), "US")
        }
    }

    @Test fun renderedMessageAllowsLinesButRejectsUnresolvedBlankOversizedAndControlText() {
        Broadcasts.validateMessage("Hello Alex\nAn expected notification.")
        for (invalid in listOf(" \n", "x".repeat(1601), "Hello\u0000", "Hello {FirstName}", "Hello {")) {
            assertThrows(IllegalArgumentException::class.java) { Broadcasts.validateMessage(invalid) }
        }
    }

    @Test fun usesExistingNameTokensAndRejectsUnavailableAppointmentOrInvalidFields() {
        val person = Recipient("alex", "Alex Example", "+12025550101", "Alex", "Example")
        assertEquals("Hello Alex Example.", Templates.render("Hello {FirstName} {LastName}.", listOf(person)))
        for (invalid in listOf("At {time}", "{dayPeriod}", "{unknown}", "Hello {FirstName")) {
            assertThrows(IllegalArgumentException::class.java) { Templates.render(invalid, listOf(person)) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            Templates.render("{LastName}", listOf(person.copy(lastName = null)))
        }
        assertThrows(IllegalArgumentException::class.java) {
            Broadcasts.validateMessage(Templates.render("Hello {FirstName}", listOf(person.copy(firstName = "{time}"))))
        }
    }

    @Test fun delayHasExplicitBoundsRatherThanSilentlyCoercingInput() {
        Broadcasts.validateDelay(1)
        Broadcasts.validateDelay(3_600)
        for (invalid in listOf(-1L, 0L, 3_601L, Long.MAX_VALUE)) {
            assertThrows(IllegalArgumentException::class.java) { Broadcasts.validateDelay(invalid) }
        }
    }
}
