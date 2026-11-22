package local.reminders.domain

object Broadcasts {
    const val MAX_RECIPIENTS = 100
    const val WINDOW_MILLIS = 24 * 60 * 60_000L
    const val PREVIEW_MILLIS = 15 * 60_000L
    const val DEFAULT_DELAY_SECONDS = 5L

    fun validateDelay(seconds: Long) {
        require(seconds in 1..3_600) { "Choose a delay of 1-3,600 seconds between recipients." }
    }

    fun recipients(selected: List<Recipient>, country: String): List<Recipient> {
        require(selected.isNotEmpty()) { "Select at least one recipient." }
        val normalized = selected.map { it.copy(phone = Importer.normalizePhone(it.phone, country)) }
            .distinctBy { it.phone }
        require(normalized.size <= MAX_RECIPIENTS) { "Select at most 100 unique phone numbers." }
        return normalized
    }

    fun validateMessage(message: String) {
        require(message.isNotBlank()) { "Write a message before reviewing." }
        require('{' !in message && '}' !in message) { "Rendered SMS contains unresolved placeholders or braces. Correct the template or recipient fields." }
        require(message.length <= 1_600 && message.none { it.isISOControl() && it !in "\n\r\t" }) {
            "Use at most 1,600 characters without unsupported control characters; SMS segment limits also apply."
        }
    }
}
