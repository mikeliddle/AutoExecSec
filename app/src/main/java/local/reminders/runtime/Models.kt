package local.reminders.runtime

import local.reminders.domain.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

data class AppSettings(
    val country: String = "US",
    val template: String = Limits.DEFAULT_TEMPLATE,
    val leadMinutes: Long = 120,
    val subscriptionId: Int = -1,
    val promptEnabled: Boolean = true,
    val promptWeekdays: Set<DayOfWeek> = DayOfWeek.entries.take(5).toSet(),
    val promptTime: LocalTime = LocalTime.of(20, 0),
    val promptDates: Set<LocalDate> = emptySet(),
    val screenLock: Boolean = false,
    val retentionDays: Int = 30
)

data class SimChoice(val id: Int, val label: String)
data class Capabilities(
    val blockers: List<String>,
    val subscriptions: List<SimChoice>,
    val ready: Boolean = blockers.isEmpty()
)

data class ReminderRecord(
    val id: String,
    val recipientId: String,
    val name: String,
    val phone: String,
    val message: String,
    val appointmentAt: Long,
    val sendAt: Long,
    val zone: String,
    val state: ReminderState,
    val subscriptionId: Int,
    val attemptId: String?,
    val detail: String,
    val parts: Int,
    val delivered: Boolean,
    val reviewed: Boolean = false
)

internal object Outcomes {
    const val CALLBACK_TIMEOUT = 120_000L
    fun sent(results: List<Int?>): ReminderState = when {
        results.isEmpty() || results.any { it == null } -> ReminderState.SUBMITTING
        results.all { it == android.app.Activity.RESULT_OK } -> ReminderState.SENT
        results.all { it != android.app.Activity.RESULT_OK } -> ReminderState.FAILED
        else -> ReminderState.UNCERTAIN
    }
}
