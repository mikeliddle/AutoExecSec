package local.reminders.domain

import com.google.i18n.phonenumbers.PhoneNumberUtil
import java.security.MessageDigest
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.ResolverStyle
import java.util.Locale

data class Recipient(val id: String, val name: String, val phone: String, val firstName: String? = null,
    val lastName: String? = null)
enum class ReminderState { PENDING, SUBMITTING, SENT, FAILED, UNCERTAIN, EXPIRED, CANCELLED }
data class ReminderDraft(
    val id: String,
    val recipientId: String,
    val name: String,
    val phone: String,
    val message: String,
    val appointmentAt: Long,
    val sendAt: Long,
    val zone: String,
    val recipients: List<Recipient> = listOf(Recipient(recipientId, name, phone))
) {
    val group: Boolean get() = recipients.size > 1
}
data class ScheduleRow(val line: Int, val hint: String, val date: String, val time: String, val zone: String,
    val nameOnly: Boolean = false, val additionalNames: List<String> = emptyList()) {
    val hints: List<String> get() = listOf(hint) + additionalNames
}
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
    const val MAX_GROUP = 20
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

    fun records(text: String, required: Set<String>): List<Map<String, String>> =
        records(text, listOf(required)).second

    fun records(text: String, formats: List<Set<String>>, groupColumns: Boolean = false): Pair<Set<String>, List<Map<String, String>>> {
        val rows = parse(text)
        require(rows.isNotEmpty()) { "Add a CSV header and at least one data row." }
        val header = rows.first().map { it.lowercase(Locale.ROOT) }
        require(header.size == header.toSet().size) { "CSV header contains duplicate columns." }
        val matching = formats.filter { header.containsAll(it) }
        require(matching.size == 1) {
            "Use exactly one CSV format: ${formats.joinToString(" or ") { it.joinToString(",") }}."
        }
        val required = matching.single()
        val numbered = if (groupColumns) header.filter { it.matches(Regex("name\\d+")) } else emptyList()
        require(numbered.all { it in (2..Limits.MAX_GROUP).map { count -> "name$count" } }) {
            "Additional names must use Name2 through Name${Limits.MAX_GROUP}."
        }
        val highest = numbered.map { it.removePrefix("name").toInt() }.maxOrNull() ?: 1
        require((2..highest).all { "name$it" in numbered }) { "Number name columns consecutively: Name, Name2, Name3." }
        require(numbered.isEmpty() || "name" in required) { "Group names require the Date,Time,Name format." }
        require(rows.size > 1) { "CSV contains no data rows." }
        require(rows.size <= Limits.MAX_BATCH + 1) { "Import at most ${Limits.MAX_BATCH} rows at a time." }
        return required to rows.drop(1).mapIndexed { index, row ->
            require(row.size == header.size) { "Row ${index + 2}: column count does not match the header." }
            header.zip(row).toMap().filterKeys { it in required || it in numbered }
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

    fun recipient(id: String, name: String, phone: String, country: String, firstName: String? = null,
        lastName: String? = null): Recipient {
        require(id.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "Recipient ID must be 1-64 letters, digits, underscores or hyphens." }
        require(name.isNotBlank() && name.length <= 120 && name.none { it.isISOControl() }) { "Enter a name of 1-120 printable characters." }
        require(firstName == null || (firstName.isNotBlank() && firstName.length <= 120 &&
            firstName.none { it.isISOControl() })) { "Enter a first name of 1-120 printable characters." }
        require(lastName == null || (lastName.isNotBlank() && lastName.length <= 120 &&
            lastName.none { it.isISOControl() })) { "Enter a last name of 1-120 printable characters." }
        return Recipient(id, name.trim(), normalizePhone(phone, country), firstName?.trim(), lastName?.trim())
    }

    fun namedRecipient(firstName: String, lastName: String, phone: String, country: String,
        existing: List<Recipient> = emptyList()): Recipient {
        require(firstName.isNotBlank() && lastName.isNotBlank()) { "FirstName and LastName are required." }
        require(firstName.none { it.isISOControl() } && lastName.none { it.isISOControl() }) {
            "Names must contain printable characters."
        }
        val name = "${firstName.trim()} ${lastName.trim()}"
        val normalized = normalizePhone(phone, country)
        val matches = existing.filter { it.name == name && it.phone == normalized }
        require(matches.size <= 1) {
            "Several saved people share this name and number. Edit them in People or use the legacy ID format."
        }
        val id = matches.singleOrNull()?.id ?: MessageDigest.getInstance("SHA-256")
            .digest("$name|$normalized".toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        require(existing.none { it.id == id && (it.name != name || it.phone != normalized) }) {
            "Recipient identity conflicts with a saved person. Use the legacy ID format."
        }
        return recipient(id, name, normalized, country, firstName, lastName)
    }

    fun recipients(text: String, country: String, existing: List<Recipient> = emptyList()): List<Recipient> {
        val (format, records) = Csv.records(text, listOf(setOf("firstname", "lastname", "phone"), setOf("id", "name", "phone")))
        val simple = "firstname" in format
        val result = records.map {
            if (simple) namedRecipient(it.getValue("firstname"), it.getValue("lastname"),
                it.getValue("phone"), country, existing)
            else {
                val id = it.getValue("id")
                val name = it.getValue("name")
                val saved = existing.singleOrNull { person -> person.id == id && person.name == name }
                recipient(id, name, it.getValue("phone"), country, saved?.firstName, saved?.lastName)
            }
        }
        val deduplicated = if (simple) result.distinct() else result
        require(deduplicated.map { it.id }.distinct().size == deduplicated.size) { "Duplicate or conflicting recipient IDs in import." }
        return deduplicated
    }

    fun schedule(text: String, importZone: ZoneId = ZoneId.systemDefault()): ScheduleImport {
        val (format, records) = Csv.records(text, listOf(setOf("date", "time", "name"), setOf("recipient", "date", "time", "zone")),
            groupColumns = true)
        val simple = "name" in format
        val problems = mutableListOf<ImportProblem>()
        val rows = records.mapIndexedNotNull { index, record ->
            val extras = (2..Limits.MAX_GROUP).mapNotNull { record["name$it"] }.dropLastWhile { it.isBlank() }
            val row = ScheduleRow(index + 2, record.getValue(if (simple) "name" else "recipient"),
                record.getValue("date"), record.getValue("time"), if (simple) importZone.id else record.getValue("zone"), simple, extras)
            try {
                require(row.hint.isNotBlank()) { "Recipient ID or exact name is required." }
                require(extras.none { it.isBlank() }) { "Fill group names consecutively; leave only trailing members blank." }
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

    fun candidates(row: ScheduleRow, recipients: List<Recipient>, member: Int = 0): List<Recipient> {
        val hint = row.hints[member]
        val byId = if (row.nameOnly) emptyList() else recipients.filter { it.id == hint }
        return byId.ifEmpty { recipients.filter { it.name == hint } }
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
    private val tokens = Regex("\\{([^{}]+)\\}")
    fun validate(template: String) {
        require(template.isNotBlank() && template.length <= 1200) { "Template must contain 1-1200 characters." }
        require(tokens.findAll(template).all { it.groupValues[1] in setOf("firstName", "FirstName", "Firstname", "LastName", "time", "dayPeriod") }) {
            "Supported tokens: {FirstName}, {firstName}, {Firstname}, {LastName}, {time}, {dayPeriod}."
        }
        val plain = tokens.replace(template, "")
        require('{' !in plain && '}' !in plain) { "Template contains unmatched braces." }
    }

    fun render(template: String, name: String, appointment: ZonedDateTime, firstName: String? = null): String {
        return render(template, listOf(Recipient("legacy", name, "", firstName)), appointment)
    }

    fun render(template: String, people: List<Recipient>, appointment: ZonedDateTime? = null): String {
        validate(template)
        require(people.isNotEmpty()) { "Resolve every recipient before rendering." }
        val firstNames = people.joinToString(", ") { it.firstName ?: it.name.trim().split(Regex("\\s+")).first() }
        val surname = if (tokens.findAll(template).any { it.groupValues[1] == "LastName" }) {
            require(people.all { !it.lastName.isNullOrBlank() }) {
                "Add an explicit last name in People for every recipient before using {LastName}."
            }
            val names = people.map { requireNotNull(it.lastName) }.distinct()
            require(names.size == 1) {
                "Surnames do not match. Choose a template using {FirstName} instead of {LastName}, then review again."
            }
            names.single()
        } else ""
        val replacements = mapOf(
            "firstName" to firstNames, "FirstName" to firstNames, "Firstname" to firstNames,
            "LastName" to surname
        )
        return tokens.replace(template) {
            val token = it.groupValues[1]
            when (token) {
                "time", "dayPeriod" -> {
                    val time = requireNotNull(appointment) {
                        "No appointment time is available for Announcements. Remove {time}/{dayPeriod}, or use an appointment reminder."
                    }
                    if (token == "time") time.format(DateTimeFormatter.ofPattern("h:mm a", Locale.US))
                    else if (time.hour >= 18) "tonight" else "today"
                }
                else -> replacements.getValue(token)
            }
        }
    }
}

object Scheduling {
    fun identity(people: List<Recipient>, appointmentAt: Long): String {
        val recipients = if (people.size == 1) people.single().id
            else "group:" + people.map { it.id }.sorted().joinToString(",")
        return MessageDigest.getInstance("SHA-256")
            .digest("$recipients|$appointmentAt".toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun draft(row: ScheduleRow, recipient: Recipient, template: String, leadMinutes: Long, now: Instant): ReminderDraft =
        draft(row, listOf(recipient), template, leadMinutes, now)

    fun draft(row: ScheduleRow, people: List<Recipient>, template: String, leadMinutes: Long, now: Instant): ReminderDraft {
        require(people.size in 1..Limits.MAX_GROUP) { "Use 1-${Limits.MAX_GROUP} recipients per appointment." }
        require(people.map { it.id }.distinct().size == people.size &&
            people.map { it.phone }.distinct().size == people.size) { "Each group member must have a distinct identity and phone number." }
        require(leadMinutes in 1..1440) { "Lead time must be 1-1440 minutes." }
        val appointment = Importer.appointment(row)
        val sendAt = appointment.toInstant().minusSeconds(leadMinutes * 60)
        require(sendAt.isAfter(now)) { "Send time must be in the future. Adjust the appointment or lead time." }
        // Today/tonight would be misleading for a reminder sent on the preceding date.
        require(sendAt.atZone(appointment.zone).toLocalDate() == appointment.toLocalDate()) {
            "Send time falls on the preceding day. Reduce lead time; today/tonight requires same-day sending."
        }
        val recipient = people.first()
        val digest = identity(people, appointment.toInstant().toEpochMilli())
        return ReminderDraft(digest, recipient.id, recipient.name, recipient.phone,
            Templates.render(template, people, appointment), appointment.toInstant().toEpochMilli(),
            sendAt.toEpochMilli(), appointment.zone.id, people)
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
