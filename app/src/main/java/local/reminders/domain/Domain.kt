package local.reminders.domain

import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.security.MessageDigest
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

data class Recipient(val id: String, val name: String, val phone: String)
enum class ReminderState { PENDING, SUBMITTING, SENT, FAILED, UNCERTAIN, EXPIRED, CANCELLED }
data class ReminderDraft(
    val id: String,
    val recipientId: String,
    val name: String,
    val phone: String,
    val message: String,
    val appointmentAt: Long,
    val sendAt: Long,
    val zone: String
)
data class ScheduleRow(val line: Int, val hint: String, val date: String, val time: String, val zone: String)
data class ImportProblem(val line: Int, val detail: String)
data class ScheduleImport(val rows: List<ScheduleRow>, val problems: List<ImportProblem>)
data class PromptSettings(
    val weekdays: Set<DayOfWeek> = DayOfWeek.entries.take(5).toSet(),
    val evening: LocalTime = LocalTime.of(20, 0),
    val oneOffDates: Set<LocalDate> = emptySet()
)

object Limits {
    const val MAX_IMPORT_BYTES = 128 * 1024
    const val MAX_BATCH = 40
    const val MAX_SEGMENTS = 6
    const val GRACE_MILLIS = 15 * 60 * 1000L
    const val DEFAULT_TEMPLATE = "Hey {firstName}. This is your reminder that you have an appointment with John at {time} {dayPeriod}."
}

object Csv {
    fun parse(text: String): List<List<String>> {
        require(text.toByteArray(Charsets.UTF_8).size <= Limits.MAX_IMPORT_BYTES) { "Import exceeds 128 KiB." }
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var closedQuote = false
        var index = 0
        fun endField() {
            row += field.toString().trim()
            field.clear()
            closedQuote = false
        }
        fun endRow() {
            endField()
            if (row.any { it.isNotEmpty() }) rows += row.toList()
            row.clear()
        }
        val source = text.removePrefix("\uFEFF")
        while (index < source.length) {
            val c = source[index]
            if (quoted) {
                if (c == '"') {
                    if (index + 1 < source.length && source[index + 1] == '"') {
                        field.append('"')
                        index++
                    } else {
                        quoted = false
                        closedQuote = true
                    }
                } else {
                    require(c != '\n' && c != '\r') { "Multiline CSV fields are not supported." }
                    field.append(c)
                }
            } else when (c) {
                '"' -> {
                    require(field.isBlank() && !closedQuote) { "Quote must start a CSV field." }
                    field.clear()
                    quoted = true
                }
                ',' -> endField()
                '\n' -> endRow()
                '\r' -> {
                    endRow()
                    if (index + 1 < source.length && source[index + 1] == '\n') index++
                }
                else -> {
                    require(!closedQuote || c.isWhitespace()) { "Unexpected text after closing quote." }
                    if (!closedQuote) field.append(c)
                }
            }
            index++
        }
        require(!quoted) { "Unclosed CSV quote." }
        if (field.isNotEmpty() || row.isNotEmpty() || closedQuote) endRow()
        return rows
    }

    fun records(text: String, required: Set<String>): List<Map<String, String>> {
        val rows = parse(text)
        require(rows.isNotEmpty()) { "Add a CSV header and at least one data row." }
        val header = rows.first().map { it.lowercase(Locale.ROOT) }
        require(header.size == header.toSet().size) { "CSV header contains duplicate columns." }
        require(header.containsAll(required)) { "Required columns: ${required.joinToString(",")}." }
        require(rows.size > 1) { "CSV contains no data rows." }
        require(rows.size <= Limits.MAX_BATCH + 1) { "Import at most ${Limits.MAX_BATCH} rows at a time." }
        return rows.drop(1).mapIndexed { index, row ->
            require(row.size == header.size) { "Row ${index + 2}: column count does not match the header." }
            header.zip(row).toMap().filterKeys { it in required }
        }
    }
}

object Importer {
    fun normalizePhone(value: String, country: String): String {
        val util = PhoneNumberUtil.getInstance()
        require(country.uppercase(Locale.ROOT) in util.supportedRegions) { "Choose a valid two-letter country code." }
        require(!value.contains(';') && !value.contains('*') && !value.contains('#')) { "Use a mobile phone number, not a service code." }
        val number = try {
            util.parse(value, country.uppercase(Locale.ROOT))
        } catch (_: com.google.i18n.phonenumbers.NumberParseException) {
            throw IllegalArgumentException("Phone number could not be parsed for the selected country.")
        }
        require(!number.hasExtension()) { "SMS numbers cannot have extensions." }
        require(util.isValidNumber(number)) { "Phone number is not valid for the selected country." }
        return util.format(number, PhoneNumberUtil.PhoneNumberFormat.E164)
    }

    fun recipient(id: String, name: String, phone: String, country: String): Recipient {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Recipient ID must be 1-64 letters, digits, underscores or hyphens." }
        require(name.isNotBlank() && name.length <= 120 && name.none { it.isISOControl() }) { "Enter a name of 1-120 printable characters." }
        return Recipient(id, name.trim(), normalizePhone(phone, country))
    }

    fun recipients(text: String, country: String): List<Recipient> {
        val result = Csv.records(text, setOf("id", "name", "phone")).map {
            recipient(it.getValue("id"), it.getValue("name"), it.getValue("phone"), country)
        }
        require(result.map { it.id }.distinct().size == result.size) { "Duplicate recipient IDs in import." }
        return result
    }

    fun schedule(text: String): ScheduleImport {
        val records = Csv.records(text, setOf("recipient", "date", "time", "zone"))
        val problems = mutableListOf<ImportProblem>()
        val rows = records.mapIndexedNotNull { index, record ->
            val row = ScheduleRow(index + 2, record.getValue("recipient"), record.getValue("date"), record.getValue("time"), record.getValue("zone"))
            try {
                require(row.hint.isNotBlank()) { "Recipient ID or exact name is required." }
                appointment(row)
                row
            } catch (error: IllegalArgumentException) {
                problems += ImportProblem(row.line, error.message ?: "Invalid appointment.")
                null
            } catch (_: DateTimeException) {
                problems += ImportProblem(row.line, "Use yyyy-MM-dd, HH:mm or h:mm AM/PM, and an IANA zone.")
                null
            }
        }
        return ScheduleImport(rows, problems)
    }

    fun candidates(row: ScheduleRow, recipients: List<Recipient>): List<Recipient> {
        val byId = recipients.filter { it.id == row.hint }
        return byId.ifEmpty { recipients.filter { it.name == row.hint } }
    }

    fun appointment(row: ScheduleRow): ZonedDateTime {
        val date = LocalDate.parse(row.date, DateTimeFormatter.ISO_LOCAL_DATE)
        val time = if (row.time.matches(Regex("\\d{2}:\\d{2}"))) {
            LocalTime.parse(row.time, DateTimeFormatter.ofPattern("HH:mm").withResolverStyle(ResolverStyle.STRICT))
        } else {
            LocalTime.parse(row.time.uppercase(Locale.US), DateTimeFormatter.ofPattern("h:mm a", Locale.US).withResolverStyle(ResolverStyle.STRICT))
        }
        val zone = ZoneId.of(row.zone)
        val local = LocalDateTime.of(date, time)
        val offsets = zone.rules.getValidOffsets(local)
        require(offsets.size == 1) {
            if (offsets.isEmpty()) "Time does not exist due to daylight saving. Choose a valid time."
            else "Time is ambiguous due to daylight saving. Use an explicit fixed-offset zone, e.g. -04:00."
        }
        return ZonedDateTime.ofStrict(local, timeOffset(offsets), zone)
    }

    private fun timeOffset(offsets: List<ZoneOffset>): ZoneOffset = offsets.single()
}

object Templates {
    private val tokens = Regex("\\{([^{}]+)}")
    fun validate(template: String) {
        require(template.isNotBlank() && template.length <= 1200) { "Template must contain 1-1200 characters." }
        require(tokens.findAll(template).all { it.groupValues[1] in setOf("firstName", "time", "dayPeriod") }) { "Supported tokens: {firstName}, {time}, {dayPeriod}." }
        val plain = tokens.replace(template, "")
        require('{' !in plain && '}' !in plain) { "Template contains unmatched braces." }
    }

    fun render(template: String, name: String, appointment: ZonedDateTime): String {
        validate(template)
        val replacements = mapOf(
            "firstName" to name.trim().split(Regex("\\s+")).first(),
            "time" to appointment.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US)),
            "dayPeriod" to if (appointment.hour >= 18) "tonight" else "today"
        )
        return tokens.replace(template) { replacements.getValue(it.groupValues[1]) }
    }
}

object Scheduling {
    fun draft(row: ScheduleRow, recipient: Recipient, template: String, leadMinutes: Long, now: Instant): ReminderDraft {
        require(leadMinutes in 1..1440) { "Lead time must be 1-1440 minutes." }
        val appointment = Importer.appointment(row)
        val sendAt = appointment.toInstant().minusSeconds(leadMinutes * 60)
        require(sendAt.isAfter(now)) { "Send time must be in the future. Adjust the appointment or lead time." }
        // Today/tonight would be misleading for a reminder sent on the preceding date.
        require(sendAt.atZone(appointment.zone).toLocalDate() == appointment.toLocalDate()) {
            "Send time falls on the preceding day. Reduce lead time; today/tonight requires same-day sending."
        }
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${recipient.id}|${appointment.toInstant().toEpochMilli()}".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return ReminderDraft(digest, recipient.id, recipient.name, recipient.phone,
            Templates.render(template, recipient.name, appointment), appointment.toInstant().toEpochMilli(),
            sendAt.toEpochMilli(), appointment.zone.id)
    }

    fun dispatchable(sendAt: Long, appointmentAt: Long, now: Long): Boolean =
        now >= sendAt && now - sendAt <= Limits.GRACE_MILLIS && now < appointmentAt

    fun expired(sendAt: Long, appointmentAt: Long, now: Long): Boolean =
        now >= appointmentAt || now > sendAt + Limits.GRACE_MILLIS

    fun nextPrompt(now: ZonedDateTime, settings: PromptSettings, coveredDates: Set<LocalDate>): ZonedDateTime? {
        require(settings.oneOffDates.size <= 366) { "Use at most 366 one-off dates." }
        for (days in 0L..366L) {
            val eveningDate = now.toLocalDate().plusDays(days)
            val workDate = eveningDate.plusDays(1)
            if (workDate in coveredDates) continue
            if (workDate.dayOfWeek !in settings.weekdays && workDate !in settings.oneOffDates) continue
            val local = LocalDateTime.of(eveningDate, settings.evening)
            // Evening prompt: resolve a gap forward and an overlap to its earlier occurrence.
            val candidate = local.atZone(now.zone)
            if (candidate.isAfter(now)) return candidate
        }
        return null
    }
}
