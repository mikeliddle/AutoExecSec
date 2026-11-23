package local.reminders.domain

import org.junit.Assert.*
import org.junit.Test

class TextTemplatesTest {
    @Test fun scheduledTemplatesResolveAppointmentFieldsAndRequireBatchValues() {
        val appointment = java.time.ZonedDateTime.parse("2030-05-03T19:00:00Z")
        val alex = Recipient("alex", "Alex Example", "+12025550101")
        val sam = Recipient("sam", "Sam Example", "+12025550102")
        val template = ScheduledTemplate(TextTemplate("visit", "Visit", "{FirstName}: {room} at {time} {dayPeriod}."))
        assertEquals(listOf("room"), template.fields)
        assertThrows(IllegalArgumentException::class.java) { template.render(listOf(alex), appointment) }
        val filled = template.copy(values = mapOf("room" to "{A2}"))
        assertEquals("Alex: {A2} at 7:00 PM tonight.", filled.render(listOf(alex), appointment))
        assertEquals("Alex, Sam: {A2} at 7:00 PM tonight.", filled.render(listOf(alex, sam), appointment))
    }

    @Test fun repeatedVariablesKeepFirstSeenOrderAndRenderWithoutRecursion() {
        val body = "Hello {name}. {time} works for {name}."
        assertEquals(listOf("name", "time"), TextTemplates.variables(body))
        assertEquals("Hello Alex. {name} works for Alex.",
            TextTemplates.render(body, mapOf("name" to "Alex", "time" to "{name}")))
    }
    @Test fun literalTemplatesNeedNoFieldsAndValuesPreserveSpacing() {
        assertTrue(TextTemplates.variables("Just text.").isEmpty())
        assertEquals("Just text.", TextTemplates.render("Just text.", emptyMap()))
        assertEquals("Hello  Alex \n", TextTemplates.render("Hello {full name}", mapOf("full name" to " Alex \n")))
        assertEquals(listOf("Name", "name"), TextTemplates.variables("{Name} {name}"))
    }
    @Test fun malformedTokensMissingValuesAndBoundsFailExplicitly() {
        listOf("{name", "name}", "{}", "{{name}}", "{ name}", "{1name}").forEach {
            assertThrows(IllegalArgumentException::class.java) { TextTemplates.variables(it) }
        }
        assertThrows(IllegalArgumentException::class.java) { TextTemplates.render("{name}", emptyMap()) }
        assertThrows(IllegalArgumentException::class.java) { TextTemplates.render("{name}", mapOf("name" to " ")) }
        assertThrows(IllegalArgumentException::class.java) { TextTemplates.variables("a".repeat(16_001)) }
        assertThrows(IllegalArgumentException::class.java) {
            TextTemplates.variables((1..33).joinToString { "{field$it}" })
        }
        assertThrows(IllegalArgumentException::class.java) {
            TextTemplates.render("{x}{x}", mapOf("x" to "a".repeat(32_001)))
        }
    }
}
