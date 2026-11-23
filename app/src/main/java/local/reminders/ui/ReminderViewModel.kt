package local.reminders.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import local.reminders.ReminderApplication
import local.reminders.domain.*
import local.reminders.runtime.*
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class ReviewRow(val source: ScheduleRow, val recipientIds: List<String?>)
data class AnnouncementInput(val message: String, val delay: String, val selected: Set<String>, val search: String)
data class UiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val recipients: List<Recipient> = emptyList(),
    val reminders: List<ReminderRecord> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val capabilities: Capabilities? = null,
    val coveredDates: Set<LocalDate> = emptySet(),
    val review: List<ReviewRow> = emptyList(),
    val broadcastPreview: BroadcastPreview? = null,
    val diagnostic: String? = null,
    val feedback: String? = null,
    val peopleInput: List<PersonInput> = listOf(PersonInput()),
    val appointmentInput: List<AppointmentInput> = listOf(AppointmentInput()),
    val peopleProblems: List<CellProblem> = emptyList(),
    val appointmentProblems: List<CellProblem> = emptyList(),
    val incomingPeople: List<PersonInput>? = null,
    val incomingAppointments: List<AppointmentInput>? = null,
    val announcementDraft: AnnouncementInput? = null,
    val textTemplates: List<TextTemplate> = emptyList(),
    val scheduledTemplate: ScheduledTemplate? = null
)

class ReminderViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = (application as ReminderApplication).runtime
    private val mutable = MutableStateFlow(UiState())
    val state = mutable.asStateFlow()

    fun refresh() = operation {
        runtime.reconcile()
    }

    private suspend fun snapshot() {
        val store = runtime.store
        mutable.value = mutable.value.copy(
            recipients = store.recipients(), reminders = store.reminders(),
            settings = store.settings(), coveredDates = store.coveredDates(),
            capabilities = runtime.capabilities(), diagnostic = store.diagnostic(), loading = false,
            textTemplates = runtime.textTemplates()
        )
    }

    private fun operation(success: String? = null, work: suspend () -> Unit) {
        if (mutable.value.busy) return
        mutable.value = mutable.value.copy(busy = true)
        viewModelScope.launch {
            var failure: String? = null
            try {
                withContext(Dispatchers.IO) {
                    work()
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failure = error.message ?: "Operation failed. No automatic retry was made."
            }
            try {
                // A failed alarm call may follow a successful database commit; still show the queue.
                withContext(Dispatchers.IO) { snapshot() }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                val loadError = "Local state could not be loaded: ${error.message ?: "storage unavailable"}"
                failure = if (failure == null) loadError else "$failure\n$loadError"
            } finally {
                mutable.value = mutable.value.copy(busy = false, loading = false,
                    feedback = failure ?: success ?: mutable.value.feedback)
            }
        }
    }

    fun clearFeedback() { mutable.value = mutable.value.copy(feedback = null) }
    fun saveTextTemplate(template: TextTemplate) = operation("Template saved.") {
        runtime.saveTextTemplate(template)
    }
    fun deleteTextTemplate(id: String) = operation("Template deleted.") {
        runtime.deleteTextTemplate(id)
    }
    fun rememberAnnouncement(draft: AnnouncementInput) {
        if (draft != mutable.value.announcementDraft) mutable.value = mutable.value.copy(announcementDraft = draft)
    }
    fun clearReview() { mutable.value = mutable.value.copy(review = emptyList(), scheduledTemplate = null) }
    fun selectScheduledTemplate(template: ScheduledTemplate?) {
        mutable.value = mutable.value.copy(scheduledTemplate = template)
    }
    fun backToTable() {
        mutable.value = mutable.value.copy(
            appointmentInput = mutable.value.review.map { AppointmentInput.from(it.source) },
            appointmentProblems = emptyList(), review = emptyList())
    }
    fun updatePerson(row: PersonInput) {
        mutable.value = mutable.value.copy(peopleInput = mutable.value.peopleInput.map { if (it.key == row.key) row else it },
            peopleProblems = mutable.value.peopleProblems.filterNot { it.key == row.key })
    }
    fun addPerson() {
        if (mutable.value.peopleInput.size < Limits.MAX_BATCH)
            mutable.value = mutable.value.copy(peopleInput = mutable.value.peopleInput + PersonInput())
    }
    fun removePerson(key: String) {
        mutable.value = mutable.value.copy(peopleInput = mutable.value.peopleInput.filterNot { it.key == key }.ifEmpty { listOf(PersonInput()) },
            peopleProblems = mutable.value.peopleProblems.filterNot { it.key == key })
    }
    fun editPerson(person: Recipient) {
        val rows = mutable.value.peopleInput
        val input = PersonInput.from(person)
        val filled = rows.filterNot { it.blank }
        mutable.value = when {
            rows.any { it.original?.id == person.id } -> mutable.value
            filled.size >= Limits.MAX_BATCH -> mutable.value.copy(feedback = "Save or remove a row before editing another person.")
            else -> mutable.value.copy(peopleInput = filled + input)
        }
    }
    fun updateAppointmentInput(row: AppointmentInput) {
        mutable.value = mutable.value.copy(appointmentInput = mutable.value.appointmentInput.map { if (it.key == row.key) row else it },
            appointmentProblems = mutable.value.appointmentProblems.filterNot { it.key == row.key })
    }
    fun addAppointment() {
        if (mutable.value.appointmentInput.size < Limits.MAX_BATCH)
            mutable.value = mutable.value.copy(appointmentInput = mutable.value.appointmentInput + AppointmentInput())
    }
    fun removeAppointment(key: String) {
        mutable.value = mutable.value.copy(appointmentInput = mutable.value.appointmentInput.filterNot { it.key == key }
            .ifEmpty { listOf(AppointmentInput()) }, appointmentProblems = mutable.value.appointmentProblems.filterNot { it.key == key })
    }
    fun loadPeopleTable(text: String): Boolean =
        try {
            val rows = TableInputs.importPeople(text, mutable.value.settings.country, mutable.value.recipients)
            mutable.value = if (mutable.value.peopleInput.any { !it.blank }) mutable.value.copy(incomingPeople = rows)
                else mutable.value.copy(peopleInput = rows, peopleProblems = emptyList())
            true
        } catch (error: IllegalArgumentException) {
            mutable.value = mutable.value.copy(feedback = error.message ?: "Import failed. Your rows are unchanged.")
            false
        }
    fun loadAppointmentTable(text: String): Boolean =
        try {
            val rows = TableInputs.importAppointments(text, ZoneId.systemDefault())
            mutable.value = if (mutable.value.appointmentInput.any { !it.blank }) mutable.value.copy(incomingAppointments = rows)
                else mutable.value.copy(appointmentInput = rows, appointmentProblems = emptyList())
            true
        } catch (error: IllegalArgumentException) {
            mutable.value = mutable.value.copy(feedback = error.message ?: "Import failed. Your rows are unchanged.")
            false
        }
    fun resolveTableImport(people: Boolean, replace: Boolean) {
        val s = mutable.value
        mutable.value = if (people) s.copy(
            peopleInput = if (replace) s.incomingPeople ?: s.peopleInput else s.peopleInput,
            incomingPeople = null, peopleProblems = if (replace) emptyList() else s.peopleProblems)
        else s.copy(appointmentInput = if (replace) s.incomingAppointments ?: s.appointmentInput else s.appointmentInput,
            incomingAppointments = null, appointmentProblems = if (replace) emptyList() else s.appointmentProblems)
    }
    fun validatePeopleTable(): Boolean {
        val result = TableInputs.people(mutable.value.peopleInput, mutable.value.settings.country, mutable.value.recipients)
        mutable.value = mutable.value.copy(peopleProblems = result.problems)
        return result.problems.isEmpty()
    }
    fun savePeopleTable() = operation("People saved. Changed recipients need fresh approval.") {
        val s = mutable.value
        val result = TableInputs.people(s.peopleInput, s.settings.country, s.recipients)
        mutable.value = mutable.value.copy(peopleProblems = result.problems)
        require(result.problems.isEmpty()) { "Check the highlighted rows." }
        runtime.savePeople(result.values)
        mutable.value = mutable.value.copy(peopleInput = listOf(PersonInput()), peopleProblems = emptyList(), review = emptyList())
    }
    fun reviewTable() {
        val result = TableInputs.appointments(mutable.value.appointmentInput)
        mutable.value = mutable.value.copy(appointmentProblems = result.problems)
        if (result.problems.isEmpty()) setReview(result.values)
    }
    private fun setReview(rows: List<ScheduleRow>) {
        mutable.value = mutable.value.copy(scheduledTemplate = null, review = rows.map {
            ReviewRow(it, it.hints.indices.map { member ->
                Importer.candidates(it, mutable.value.recipients, member).singleOrNull()?.id
            })
        })
    }
    fun clearBroadcastPreview() { mutable.value = mutable.value.copy(broadcastPreview = null) }

    fun previewBroadcast(ids: Set<String>, template: String, delay: String) = operation {
        val seconds = requireNotNull(delay.toLongOrNull()) { "Enter a whole-number delay in seconds (1-3,600)." }
        mutable.value = mutable.value.copy(broadcastPreview =
            runtime.previewBroadcast(ids, template, mutable.value.settings.subscriptionId, seconds))
    }

    fun saveBroadcastPreferences(template: String, delay: String) = operation("Announcement template and delay saved. Existing approvals unchanged.") {
        val seconds = requireNotNull(delay.toLongOrNull()) { "Enter a whole-number delay in seconds (1-3,600)." }
        runtime.saveSettings(mutable.value.settings.copy(broadcastTemplate = template, broadcastDelaySeconds = seconds))
    }

    fun approveBroadcast(acknowledged: Boolean) = operation("Announcement approved. Watch outcomes; submitted texts cannot be recalled.") {
        val preview = requireNotNull(mutable.value.broadcastPreview) { "Review the announcement first." }
        runtime.approveBroadcast(preview, acknowledged)
        clearBroadcastPreview()
    }

    fun stopBroadcast(id: String) = operation("Remaining queued sends stopped. Already submitted texts cannot be recalled.") {
        runtime.stopBroadcast(id)
    }

    fun parseSchedule(text: String, onParsed: () -> Unit) = operation {
        val importZone = ZoneId.systemDefault()
        val imported = Importer.schedule(text, importZone)
        require(imported.problems.isEmpty()) {
            imported.problems.joinToString("\n") { "Row ${it.line}: ${it.detail}" }
        }
        setReview(imported.rows)
        withContext(Dispatchers.Main) { onParsed() }
    }

    fun selectRecipient(line: Int, id: String, member: Int = 0) {
        mutable.value = mutable.value.copy(review = mutable.value.review.map {
            if (it.source.line == line) it.copy(recipientIds = it.recipientIds.mapIndexed { index, old ->
                if (index == member) id else old
            }) else it
        })
    }

    fun updateAppointment(line: Int, date: String, time: String, zone: String) = operation {
        val current = mutable.value.review.single { it.source.line == line }
        val source = current.source.copy(date = date.trim(), time = time.trim(), zone = zone.trim())
        Importer.appointment(source)
        mutable.value = mutable.value.copy(review = mutable.value.review.map {
            if (it.source.line == line) it.copy(source = source) else it
        })
    }

    fun drafts(state: UiState = mutable.value): List<ReminderDraft> = state.review.map { row ->
        val people = row.recipientIds.map { id ->
            state.recipients.singleOrNull { it.id == id }
                ?: throw IllegalArgumentException("Resolve every recipient for row ${row.source.line}.")
        }
        val template = state.scheduledTemplate
        if (template == null) {
            Scheduling.draft(row.source, people, state.settings.templateFor(people.size),
                state.settings.leadMinutes, Instant.now())
        } else {
            require(state.textTemplates.any { it.id == template.definition.id && it.body == template.definition.body }) {
                "The saved template changed. Load it again to review the messages."
            }
            Scheduling.draft(row.source, people, state.settings.leadMinutes, Instant.now()) { template.render(people, it) }
        }
    }.also { rows ->
        val members = rows.flatMap { row -> row.recipients.map { it.id to row.appointmentAt } }
        require(members.distinct().size == members.size) { "The preview contains overlapping appointments for the same recipient." }
    }

    fun approve(drafts: List<ReminderDraft>, subscriptionId: Int, template: ScheduledTemplate?,
        onApproved: () -> Unit) = operation("Reminders approved. Values frozen; watch the queue for outcomes.") {
        require(drafts.isNotEmpty()) { "Import at least one appointment." }
        val current = mutable.value
        require(current.settings.subscriptionId == subscriptionId && current.scheduledTemplate == template && drafts(current) == drafts) {
            "The reminder preview changed. Check the messages and approve again."
        }
        runtime.approve(drafts, subscriptionId, template)
        clearReview()
        mutable.value = mutable.value.copy(appointmentInput = listOf(AppointmentInput()), appointmentProblems = emptyList())
        withContext(Dispatchers.Main) { onApproved() }
    }

    fun saveRecipient(id: String, name: String, phone: String, firstName: String?, lastName: String?, done: () -> Unit) =
        operation("Recipient saved. Affected pending reminders need fresh approval.") {
            runtime.saveRecipient(Importer.recipient(id, name, phone, mutable.value.settings.country, firstName, lastName))
            clearReview()
            withContext(Dispatchers.Main) { done() }
        }

    fun importRecipients(text: String, done: () -> Unit) =
        operation("Recipient import saved. Review affected reminders before reapproving.") {
            runtime.importRecipients(text)
            clearReview()
            withContext(Dispatchers.Main) { done() }
        }

    fun deleteRecipient(id: String) = operation("Recipient deleted; pending reminders cancelled.") {
        runtime.deleteRecipient(id)
        clearReview()
    }

    fun cancel(id: String) = operation("Pending reminder cancelled.") {
        require(runtime.cancel(id)) { "Cancellation refused: dispatch has already started or the reminder is no longer pending. A submitted SMS cannot be recalled." }
    }

    fun acknowledgeUncertain(id: String) = operation("Review recorded. Outcome remains uncertain and cannot be automatically resent.") {
        require(runtime.acknowledgeUncertain(id)) { "This reminder is no longer an uncertain outcome. Refresh the queue." }
    }

    fun edit(record: ReminderRecord, done: () -> Unit) =
        operation("Previous approval cancelled. Import the corrected appointment and approve again.") {
            require(record.broadcastId == null) { "Announcements cannot be edited or resent from history." }
            require(runtime.cancel(record.id)) { "Dispatch has already started. This reminder cannot be edited or recalled." }
            val appointment = Instant.ofEpochMilli(record.appointmentAt).atZone(ZoneId.of(record.zone))
            mutable.value = mutable.value.copy(scheduledTemplate = null, review = listOf(ReviewRow(
                ScheduleRow(2, record.recipients.first().name, appointment.toLocalDate().toString(),
                    appointment.format(DateTimeFormatter.ofPattern("HH:mm")), record.zone, true,
                    record.recipients.drop(1).map { it.name }),
                record.recipients.map { it.id }
            )))
            withContext(Dispatchers.Main) { done() }
        }

    fun saveSettings(settings: AppSettings) = operation("Settings saved. Changed pending reminders require fresh approval.") {
        runtime.saveSettings(settings)
        clearReview()
    }

    fun selectSendingSim(id: Int) = operation("Sending SIM saved for new approvals.") {
        runtime.saveSettings(mutable.value.settings.copy(subscriptionId = id, subscriptionAutomatic = false))
    }

    fun markNoReminders(date: String) = operation("Date marked as no reminders; its evening prompt is suppressed.") {
        runtime.markNoReminders(LocalDate.parse(date))
    }

    fun deleteHistory() = operation("Completed history deleted. Duplicate-protection fingerprints remain.") {
        runtime.deleteHistory()
    }

    fun deleteAll() = operation("Local data deleted. Already submitted SMS cannot be recalled; duplicate-protection fingerprints remain.") {
        runtime.deleteAll()
        clearReview()
    }

    fun readFile(uri: Uri, done: (String) -> Unit) = operation {
        val text = withContext(Dispatchers.IO) {
            val resolver = getApplication<Application>().contentResolver
            val input = resolver.openInputStream(uri)
                ?: throw IllegalArgumentException("The selected file could not be opened.")
            input.use {
                val output = ByteArrayOutputStream()
                val chunk = ByteArray(4096)
                while (true) {
                    val count = it.read(chunk)
                    if (count < 0) break
                    require(output.size() + count <= Limits.MAX_IMPORT_BYTES) { "Selected file exceeds 128 KiB." }
                    output.write(chunk, 0, count)
                }
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(output.toByteArray())).toString()
            }
        }
        withContext(Dispatchers.Main) { done(text) }
    }
}
