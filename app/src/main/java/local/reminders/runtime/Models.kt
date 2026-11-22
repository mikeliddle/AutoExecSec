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
    val retentionDays: Int = 30,
    val subscriptionAutomatic: Boolean = false,
    val groupTemplates: Map<Int, String> = emptyMap(),
    val broadcastTemplate: String = "Hello {FirstName}.",
    val broadcastDelaySeconds: Long = Broadcasts.DEFAULT_DELAY_SECONDS
) {
    fun templateFor(count: Int): String = if (count == 1) template else
        groupTemplates[count] ?: throw IllegalArgumentException("Configure a template for exactly $count recipients in Setup.")
}

data class SimChoice(val id: Int, val label: String)
data class BroadcastMessage(val recipient: Recipient, val message: String, val parts: Int)
data class BroadcastPreview(
    val id: String,
    val messages: List<BroadcastMessage>,
    val template: String,
    val subscriptionId: Int,
    val delaySeconds: Long,
    val preparedAt: Long,
    val expiresAt: Long
) {
    val recipients: List<Recipient> get() = messages.map { it.recipient }
    val totalSegments: Int get() = messages.sumOf { it.parts }
}

data class Capabilities(
    val blockers: List<String>,
    val subscriptions: List<SimChoice>,
    val ready: Boolean = blockers.isEmpty(),
    val mms: Map<Int, MmsCapability> = emptyMap()
) {
    fun selectionForNewApprovals(current: Int, automatic: Boolean = false): Int =
        subscriptions.singleOrNull { it.id == current && (!automatic || subscriptions.size == 1) }?.id
            ?: subscriptions.singleOrNull()?.id ?: -1
}

data class MmsCapability(val maxRecipients: Int = 0, val maxBytes: Int = 0, val blocker: String? = null) {
    fun check(count: Int, bytes: Int): String? = blocker
        ?: if (count !in 2..minOf(maxRecipients, Limits.MAX_GROUP)) "This group exceeds the carrier MMS recipient limit." else
            if (bytes !in 1..maxBytes) "This message exceeds the carrier MMS size limit." else null
}

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
    val reviewed: Boolean = false,
    val recipients: List<Recipient> = listOf(Recipient(recipientId, name, phone)),
    val broadcastId: String? = null,
    val broadcastExpiresAt: Long? = null,
    val broadcastDelaySeconds: Long? = null
) {
    val group: Boolean get() = recipients.size > 1
}

internal object Outcomes {
    const val CALLBACK_TIMEOUT = 120_000L
    const val MMS_CALLBACK_TIMEOUT = 35 * 60_000L
    fun sent(results: List<Int?>): ReminderState = when {
        results.isEmpty() || results.any { it == null } -> ReminderState.SUBMITTING
        results.all { it == android.app.Activity.RESULT_OK } -> ReminderState.SENT
        results.all { it != android.app.Activity.RESULT_OK } -> ReminderState.FAILED
        else -> ReminderState.UNCERTAIN
    }
}
