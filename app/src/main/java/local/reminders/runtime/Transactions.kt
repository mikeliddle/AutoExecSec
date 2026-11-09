package local.reminders.runtime

import local.reminders.domain.*
import java.time.*

internal class Transactions(private val db: RuntimeDatabase) {
    private val dao get() = db.dao()

    fun approve(rows: List<ReminderEntity>, now: Long) = db.runInTransaction {
        require(rows.size in 1..Limits.MAX_BATCH && rows.map { it.id }.distinct().size == rows.size) {
            "Approve 1-40 distinct reminders."
        }
        val pending = dao.activeReminders().size
        require(pending + rows.size <= Limits.MAX_BATCH) { "At most 40 active approved reminders are allowed." }
        rows.forEach {
            require(dao.tombstone(it.id) == 0) { "This appointment has already been processed; it cannot be sent again." }
            val existing = dao.reminder(it.id)
            require(existing == null || existing.state == "CANCELLED") {
                "This appointment is already approved or processed."
            }
        }
        rows.forEach {
            dao.deleteReminder(it.id)
            dao.insertReminder(it.copy(updatedAt = now))
            dao.putCoverage(Coverage(appointmentDate(it), "approved"))
        }
    }

    fun cancel(id: String, now: Long): Boolean {
        var cancelled = false
        db.runInTransaction {
            val row = dao.reminder(id) ?: return@runInTransaction
            if (row.state != "PENDING") return@runInTransaction
            dao.updateReminder(row.copy(state = "CANCELLED", detail = "Cancelled before submission.", updatedAt = now))
            removeCoverage(row)
            cancelled = true
        }
        return cancelled
    }

    fun cancelAffected(predicate: (ReminderEntity) -> Boolean, now: Long) = db.runInTransaction {
        dao.activeReminders().filter { it.state == "PENDING" && predicate(it) }.forEach { row ->
            dao.updateReminder(row.copy(state = "CANCELLED", detail = "Changed inputs require fresh approval.", updatedAt = now))
            removeCoverage(row)
        }
    }

    private fun removeCoverage(row: ReminderEntity) {
        val date = appointmentDate(row)
        val stillApproved = dao.reminders().any {
            appointmentDate(it) == date && it.state !in setOf("CANCELLED", "EXPIRED")
        }
        if (!stillApproved) dao.removeApproval(date)
    }

    fun claim(id: String, attempt: String, owner: String, now: Long): ReminderEntity? {
        var claimed: ReminderEntity? = null
        db.runInTransaction {
            val row = dao.reminder(id) ?: return@runInTransaction
            if (row.state != "PENDING" || !Scheduling.dispatchable(row.sendAt, row.appointmentAt, now)) return@runInTransaction
            if (dao.claim(id, attempt, owner, now + Outcomes.CALLBACK_TIMEOUT, now) == 1) {
                dao.putTombstone(Tombstone(row.id))
                dao.putValue(StoredValue("rate:$attempt", now.toString()))
                claimed = dao.reminder(id)
            }
        }
        return claimed
    }

    fun finish(id: String, state: ReminderState, detail: String, now: Long) = db.runInTransaction {
        val row = dao.reminder(id) ?: return@runInTransaction
        if (row.state != "SUBMITTING" && row.state != "PENDING") return@runInTransaction
        dao.updateReminder(row.copy(state = state.name, detail = detail, updatedAt = now))
        if (state != ReminderState.CANCELLED) dao.putTombstone(Tombstone(id))
        if (state == ReminderState.EXPIRED) removeCoverage(row)
    }

    fun callback(id: String, attempt: String, index: Int, delivery: Boolean, code: Int, now: Long,
        deliveryStatus: Int? = null) =
        db.runInTransaction {
            val row = dao.reminder(id) ?: return@runInTransaction
            if (row.attemptId != attempt || index !in 0 until row.parts) return@runInTransaction
            val values = results(if (delivery) row.deliveryResults else row.sentResults, row.parts)
            if (delivery) {
                if (deliveryStatus == null || deliveryStatus !in 0..255) return@runInTransaction
                if (values[index] == DeliveryReports.COMPLETE) return@runInTransaction
                // A pending/error report is not proof of delivery and must not block a later complete report.
                values[index] = deliveryStatus
                dao.updateReminder(row.copy(deliveryResults = encodeResults(values),
                    delivered = values.all { it == DeliveryReports.COMPLETE }))
            } else {
                // Duplicate sent callbacks cannot overwrite an authoritative submission result.
                if (values[index] != null) return@runInTransaction
                values[index] = code
                val timedOut = row.deadline?.let { now >= it } == true
                val state = if (row.state == "SUBMITTING" && !timedOut) Outcomes.sent(values)
                    else if (row.state == "SUBMITTING") ReminderState.UNCERTAIN
                    else ReminderState.valueOf(row.state)
                dao.updateReminder(row.copy(sentResults = encodeResults(values), state = state.name,
                    detail = when (state) {
                        ReminderState.SENT -> "All parts accepted; delivery is not guaranteed."
                        ReminderState.FAILED -> "Every part reported an explicit submission failure. No automatic retry."
                        ReminderState.UNCERTAIN -> "Submission may be partial or unknown. Do not resend."
                        else -> row.detail
                    }, updatedAt = if (row.state == "SUBMITTING") now else row.updatedAt))
            }
        }

    fun reconcile(now: Long, owner: String, retentionDays: Int) = db.runInTransaction {
        dao.activeReminders().forEach { row ->
            when {
                row.state == "PENDING" && Scheduling.expired(row.sendAt, row.appointmentAt, now) -> {
                    dao.updateReminder(row.copy(state = "EXPIRED", detail = "The safe sending window has passed.", updatedAt = now))
                    dao.putTombstone(Tombstone(row.id))
                    removeCoverage(row)
                }
                row.state == "SUBMITTING" && (row.owner != owner || row.deadline == null || now >= row.deadline) -> {
                    dao.updateReminder(row.copy(state = "UNCERTAIN",
                        detail = "Submission interrupted or callbacks missing. Do not resend.", updatedAt = now))
                    dao.putTombstone(Tombstone(row.id))
                }
            }
        }
        dao.finalizedBefore(now - retentionDays * 86_400_000L).forEach { row ->
            if (row.state != "CANCELLED") dao.putTombstone(Tombstone(row.id))
            dao.deleteReminder(row.id)
        }
    }

    fun deleteHistory() = db.runInTransaction {
        dao.reminders().filter { it.state in setOf("SENT", "FAILED", "EXPIRED", "CANCELLED") ||
            (it.state == "UNCERTAIN" && isReviewed(it.id)) }.forEach {
            if (it.state != "CANCELLED") dao.putTombstone(Tombstone(it.id))
            dao.deleteReminder(it.id)
            dao.deleteValue("reviewed:${it.id}")
        }
    }

    fun markNoReminders(date: LocalDate) = db.runInTransaction {
        dao.putCoverage(Coverage(date.toString(), "none"))
    }

    fun acknowledgeUncertain(id: String): Boolean {
        if (id.length !in 1..128) return false
        var acknowledged = false
        db.runInTransaction {
            val row = dao.reminder(id) ?: return@runInTransaction
            if (row.state != "UNCERTAIN") return@runInTransaction
            dao.putTombstone(Tombstone(id))
            dao.putValue(StoredValue("reviewed:$id", "true"))
            acknowledged = true
        }
        return acknowledged
    }

    private fun isReviewed(id: String) = dao.value("reviewed:$id") == "true"

    fun deleteAll() = db.runInTransaction {
        check(dao.reminders().none { it.state == "SUBMITTING" || (it.state == "UNCERTAIN" && !isReviewed(it.id)) }) {
            "Wait for submissions to finish and explicitly acknowledge unknown SMS outcomes before deleting all data."
        }
        dao.reminders().forEach {
            if (it.state != "CANCELLED" && it.state != "PENDING") dao.putTombstone(Tombstone(it.id))
            dao.deleteReminder(it.id)
            dao.deleteValue("reviewed:${it.id}")
        }
        dao.deleteRecipients()
        dao.deleteCoverage()
        dao.deleteValue("settings")
        dao.deleteValue("diagnostic")
        dao.deleteValue("background_diagnostic")
        dao.deleteValue("prompt_target")
    }
}

internal fun appointmentDate(row: ReminderEntity): String =
    Instant.ofEpochMilli(row.appointmentAt).atZone(ZoneId.of(row.zone)).toLocalDate().toString()
