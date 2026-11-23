package local.reminders.domain

import java.time.ZonedDateTime

data class TextTemplate(val id: String, val name: String, val body: String)

data class ScheduledTemplate(val definition: TextTemplate, val values: Map<String, String> = emptyMap()) {
    val fields: List<String> get() = TextTemplates.variables(definition.body).filterNot(Templates.recipientAndTimeFields::contains)

    fun render(people: List<Recipient>, appointment: ZonedDateTime): String {
        val names = TextTemplates.variables(definition.body)
        require(fields.all { !values[it].isNullOrBlank() }) { "Fill in every template field before approving." }
        return TextTemplates.render(definition.body, names.associateWith { name ->
            if (name in Templates.recipientAndTimeFields) Templates.render("{$name}", people, appointment)
            else values.getValue(name)
        }).also { require(it.isNotBlank()) { "The rendered reminder is blank." } }
    }
}

object TextTemplates {
    const val MAX_TEMPLATES = 50
    private val label = Regex("[A-Za-z][A-Za-z0-9 _-]{0,63}")

    fun variables(body: String): List<String> {
        require(body.isNotBlank()) { "Enter some template text." }
        require(body.length <= 16_000) { "Keep template text under 16,000 characters." }
        val names = linkedSetOf<String>()
        var cursor = 0
        while (cursor < body.length) {
            when (body[cursor]) {
                '}' -> throw IllegalArgumentException("Closing brace has no opening brace.")
                '{' -> {
                    val end = body.indexOf('}', cursor + 1)
                    require(end >= 0) { "Close each variable with }." }
                    val name = body.substring(cursor + 1, end)
                    require(label.matches(name) && name == name.trim()) {
                        "Use a variable like {name}: start with a letter, then letters, numbers, spaces, _ or -."
                    }
                    names += name
                    require(names.size <= 32) { "Use at most 32 different variables." }
                    cursor = end
                }
            }
            cursor++
        }
        return names.toList()
    }

    fun validate(template: TextTemplate) {
        require(template.id.isNotBlank()) { "Template identity is missing." }
        require(template.name.isNotBlank() && template.name.length <= 80 &&
            template.name.none { it.isISOControl() }) { "Enter a template name of 1-80 printable characters." }
        variables(template.body)
    }

    fun render(body: String, values: Map<String, String>): String {
        val names = variables(body)
        require(names.all { !values[it].isNullOrBlank() }) { "Fill in every field before copying." }
        val output = StringBuilder()
        var cursor = 0
        while (cursor < body.length) {
            if (body[cursor] == '{') {
                val end = body.indexOf('}', cursor + 1)
                output.append(values.getValue(body.substring(cursor + 1, end)))
                cursor = end + 1
            } else output.append(body[cursor++])
            require(output.length <= 64_000) { "Keep generated text under 64,000 characters." }
        }
        return output.toString()
    }
}
