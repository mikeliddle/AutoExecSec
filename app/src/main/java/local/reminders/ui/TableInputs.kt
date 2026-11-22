package local.reminders.ui

import local.reminders.domain.*
import java.time.DateTimeException
import java.time.ZoneId
import java.time.format.DateTimeParseException
import java.util.UUID

data class PersonInput(
    val key: String = UUID.randomUUID().toString(),
    val firstName: String = "",
    val lastName: String = "",
    val phone: String = "",
    val original: Recipient? = null,
    val legacyName: String? = null
) {
    val blank get() = firstName.isBlank() && lastName.isBlank() && phone.isBlank() && legacyName.isNullOrBlank()
    companion object {
        fun from(person: Recipient) = PersonInput(firstName = person.firstName.orEmpty(),
            lastName = person.lastName.orEmpty(), phone = person.phone, original = person,
            legacyName = person.name.takeIf { person.firstName == null || person.lastName == null })
    }
}

data class AppointmentInput(
    val key: String = UUID.randomUUID().toString(),
    val date: String = "",
    val time: String = "",
    val names: List<String> = listOf(""),
    val zone: String = ZoneId.systemDefault().id,
    val nameOnly: Boolean = true
) {
    val blank get() = date.isBlank() && time.isBlank() && names.all { it.isBlank() }
    fun source(line: Int) = ScheduleRow(line, names.first().trim(), date.trim(), time.trim(), zone,
        nameOnly, names.drop(1).map { it.trim() })
    companion object {
        fun from(row: ScheduleRow) = AppointmentInput(date = row.date, time = row.time,
            names = row.hints, zone = row.zone, nameOnly = row.nameOnly)
    }
}

data class CellProblem(val key: String, val field: String, val detail: String)
data class TableResult<T>(val values: List<T>, val problems: List<CellProblem>)

object TableInputs {
    fun people(rows: List<PersonInput>, country: String, existing: List<Recipient>): TableResult<Recipient> {
        val problems = mutableListOf<CellProblem>()
        val values = rows.filterNot { it.blank }.mapNotNull { row ->
            val phone = try { Importer.normalizePhone(row.phone, country) }
            catch (error: IllegalArgumentException) {
                problems += CellProblem(row.key, "phone", error.message ?: "Check this number.")
                null
            }
            if (row.legacyName == null) {
                if (row.firstName.isBlank()) problems += CellProblem(row.key, "firstName", "Enter a first name.")
                if (row.lastName.isBlank()) problems += CellProblem(row.key, "lastName", "Enter a last name.")
            }
            if (phone == null || problems.any { it.key == row.key }) null else try {
                val original = row.original
                if (original != null) Importer.recipient(original.id,
                    row.legacyName ?: "${row.firstName.trim()} ${row.lastName.trim()}", phone, country,
                    row.firstName.takeIf { it.isNotBlank() }, row.lastName.takeIf { it.isNotBlank() })
                else Importer.namedRecipient(row.firstName, row.lastName, phone, country, existing)
            } catch (error: IllegalArgumentException) {
                problems += CellProblem(row.key, "name", error.message ?: "Check this name.")
                null
            }
        }.distinct()
        if (values.map { it.id }.distinct().size != values.size)
            problems += CellProblem("", "", "The same person has conflicting rows. Keep one version.")
        if (values.isEmpty() && problems.isEmpty()) problems += CellProblem("", "", "Add at least one person.")
        if (rows.count { !it.blank } > Limits.MAX_BATCH)
            problems += CellProblem("", "", "Save at most ${Limits.MAX_BATCH} rows at a time.")
        return TableResult(values, problems)
    }

    fun appointments(rows: List<AppointmentInput>): TableResult<ScheduleRow> {
        val problems = mutableListOf<CellProblem>()
        val values = rows.mapIndexedNotNull { index, row ->
            if (row.blank) return@mapIndexedNotNull null
            row.names.forEachIndexed { member, name ->
                if (name.isBlank()) problems += CellProblem(row.key, "name$member", "Choose or enter a name.")
            }
            val source = row.source(index + 1)
            try {
                Importer.appointment(source)
                source
            } catch (error: DateTimeParseException) {
                val field = if (error.parsedString == source.date) "date" else "time"
                problems += CellProblem(row.key, field, if (field == "date") "Use yyyy-MM-dd." else "Use HH:mm or h:mm AM/PM.")
                null
            } catch (_: DateTimeException) {
                problems += CellProblem(row.key, "zone", "Check the appointment time zone.")
                null
            } catch (error: IllegalArgumentException) {
                problems += CellProblem(row.key, "time", error.message ?: "Check this time.")
                null
            }
        }
        if (values.isEmpty() && problems.isEmpty()) problems += CellProblem("", "", "Add at least one appointment.")
        if (rows.count { !it.blank } > Limits.MAX_BATCH)
            problems += CellProblem("", "", "Review at most ${Limits.MAX_BATCH} appointments.")
        return TableResult(values, problems)
    }

    fun importPeople(text: String, country: String, existing: List<Recipient>) =
        Importer.recipients(text, country, existing).map(PersonInput::from)

    fun importAppointments(text: String, zone: ZoneId): List<AppointmentInput> {
        val parsed = Importer.schedule(text, zone)
        require(parsed.problems.isEmpty()) {
            parsed.problems.joinToString("\n") { "Row ${it.line}: ${it.detail}" }
        }
        return parsed.rows.map(AppointmentInput::from)
    }
}
