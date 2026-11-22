package local.reminders.domain

import org.junit.Assert.*
import org.junit.Test

class TextTemplatesTest {
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
