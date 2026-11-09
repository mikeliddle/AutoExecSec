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

data class ReviewRow(val source: ScheduleRow, val recipientId: String?)
data class UiState(
    val loading: Boolean = true,
    val busy: Boolean = false,
    val recipients: List<Recipient> = emptyList(),
    val reminders: List<ReminderRecord> = emptyList(),
    val settings: AppSettings = AppSettings(),
    val capabilities: Capabilities? = null,
    val coveredDates: Set<LocalDate> = emptySet(),
    val review: List<ReviewRow> = emptyList(),
    val diagnostic: String? = null,
    val feedback: String? = null
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
            capabilities = runtime.capabilities(), diagnostic = store.diagnostic(), loading = false
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
    fun clearReview() { mutable.value = mutable.value.copy(review = emptyList()) }

    fun parseSchedule(text: String, onParsed: () -> Unit) = operation {
        val imported = Importer.schedule(text)
        require(imported.problems.isEmpty()) {
            imported.problems.joinToString("\n") { "Row ${it.line}: ${it.detail}" }
        }
        mutable.value = mutable.value.copy(review = imported.rows.map {
            ReviewRow(it, Importer.candidates(it, mutable.value.recipients).singleOrNull()?.id)
        })
        withContext(Dispatchers.Main) { onParsed() }
    }

    fun selectRecipient(line: Int, id: String) {
        mutable.value = mutable.value.copy(review = mutable.value.review.map {
            if (it.source.line == line) it.copy(recipientId = id) else it
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

    fun drafts(): List<ReminderDraft> = mutable.value.review.map { row ->
        val person = mutable.value.recipients.singleOrNull { it.id == row.recipientId }
            ?: throw IllegalArgumentException("Resolve recipient for row ${row.source.line}.")
        Scheduling.draft(row.source, person, mutable.value.settings.template,
            mutable.value.settings.leadMinutes, Instant.now())
    }.also { rows ->
        require(rows.map { it.id }.distinct().size == rows.size) { "The preview contains duplicate appointments for the same recipient." }
    }

    fun approve(onApproved: () -> Unit) = operation("Batch approved. Values are frozen; watch the queue for outcomes.") {
        val drafts = drafts()
        require(drafts.isNotEmpty()) { "Import at least one appointment." }
        runtime.approve(drafts, mutable.value.settings.subscriptionId)
        clearReview()
        withContext(Dispatchers.Main) { onApproved() }
    }

    fun saveRecipient(id: String, name: String, phone: String, done: () -> Unit) =
        operation("Recipient saved. Affected pending reminders need fresh approval.") {
            runtime.saveRecipient(Importer.recipient(id, name, phone, mutable.value.settings.country))
            clearReview()
            withContext(Dispatchers.Main) { done() }
        }

    fun importRecipients(text: String, done: () -> Unit) =
        operation("Recipient import saved. Review affected reminders before reapproving.") {
            val recipients = Importer.recipients(text, mutable.value.settings.country)
            for (recipient in recipients) runtime.saveRecipient(recipient)
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
            require(runtime.cancel(record.id)) { "Dispatch has already started. This reminder cannot be edited or recalled." }
            val appointment = Instant.ofEpochMilli(record.appointmentAt).atZone(ZoneId.of(record.zone))
            mutable.value = mutable.value.copy(review = listOf(ReviewRow(
                ScheduleRow(2, record.recipientId, appointment.toLocalDate().toString(),
                    appointment.format(DateTimeFormatter.ofPattern("HH:mm")), record.zone),
                record.recipientId
            )))
            withContext(Dispatchers.Main) { done() }
        }

    fun saveSettings(settings: AppSettings) = operation("Settings saved. Changed pending reminders require fresh approval.") {
        runtime.saveSettings(settings)
        clearReview()
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
