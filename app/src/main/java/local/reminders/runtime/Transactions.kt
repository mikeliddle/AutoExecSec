package local.reminders.runtime

import local.reminders.domain.*
import java.time.*

internal class Transactions(private val db: RuntimeDatabase) {
    private val dao get() = db.dao()

    fun saveRecipients(people: List<Recipient>, now: Long) = db.runInTransaction {
        require(people.map { it.id }.distinct().size == people.size) { "Duplicate recipient identities." }
        people.forEach { person ->
            if (dao.recipient(person.id)?.model()?.let { it != person } == true)
                cancelAffected({ row -> row.people().any { it.id == person.id } }, now)
            dao.putRecipient(RecipientEntity(person.id, person.name, person.phone, person.firstName, person.lastName))
        }
    }

    fun approve(rows: List<ReminderEntity>, now: Long) = db.runInTransaction {
        require(rows.size in 1..Limits.MAX_BATCH && rows.map { it.id }.distinct().size == rows.size) {
            "Approve 1-40 distinct reminders."
        }
        require(rows.none { it.broadcastId != null }) { "Use the separate Announcements approval flow." }
        val pending = dao.activeReminders().count { it.broadcastId == null }
        require(pending + rows.size <= Limits.MAX_BATCH) { "At most 40 active approved reminders are allowed." }
        rows.forEach {
            require(dao.tombstone(it.id) == 0) { "This appointment has already been processed; it cannot be sent again." }
            val existing = dao.reminder(it.id)
            require(existing == null || existing.state == "CANCELLED") {
                "This appointment is already approved or processed."
            }
            val ids = it.people().map { person -> person.id }.toSet()
            require(dao.reminders().none { other ->
                other.broadcastId == null && other.id != it.id && other.appointmentAt == it.appointmentAt && other.state != "CANCELLED" &&
                    other.people().any { person -> person.id in ids }
            } && rows.none { other ->
                other.id != it.id && other.appointmentAt == it.appointmentAt &&
                    other.people().any { person -> person.id in ids }
            }) { "A recipient already has a reminder for this appointment. Cancel the previous pending approval before changing its group." }
            require(ids.none { id -> dao.tombstone(Scheduling.identity(
                listOf(Recipient(id, "", "")), it.appointmentAt)) != 0 }) {
                "A recipient has already been processed for this appointment; changing the group cannot authorize another send."
            }
        }
        rows.forEach {
            dao.deleteReminder(it.id)
            dao.insertReminder(it.copy(updatedAt = now))
            dao.putCoverage(Coverage(appointmentDate(it), "approved"))
        }
    }

    fun approveBroadcast(rows: List<ReminderEntity>, now: Long) = db.runInTransaction {
        require(rows.size in 1..Broadcasts.MAX_RECIPIENTS &&
            rows.map { it.id }.distinct().size == rows.size &&
            rows.map { it.phone }.distinct().size == rows.size) { "Approve 1-100 distinct individual SMS recipients." }
        val first = rows.first()
        Broadcasts.validateDelay(requireNotNull(first.broadcastDelaySeconds) { "Review the sending delay first." })
        require(first.broadcastId != null && rows.all {
            it.broadcastId == first.broadcastId && it.broadcastExpiresAt == first.broadcastExpiresAt &&
                it.broadcastDelaySeconds == first.broadcastDelaySeconds && it.subscriptionId == first.subscriptionId &&
                it.parts in 1..Limits.MAX_SEGMENTS &&
                it.state == "PENDING" && !it.group && it.dispatchable(now)
        }) { "Announcement values need a fresh preview." }
        require(dao.activeReminders().none { it.broadcastId != null }) { "Stop or finish the active announcement first." }
        rows.forEach {
            require(dao.reminder(it.id) == null && dao.tombstone(it.id) == 0) {
                "This announcement was already approved or processed. Review its outcomes; do not resend."
            }
        }
        rows.forEach {
            dao.insertReminder(it)
            // Approval itself is one-shot, including if all remaining sends are stopped.
            dao.putTombstone(Tombstone(it.id))
        }
        dao.putValue(StoredValue("broadcast-next:${first.broadcastId}", now.toString()))
    }

    fun availableAt(row: ReminderEntity): Long = if (row.broadcastId == null) row.sendAt else
        requireNotNull(dao.value("broadcast-next:${row.broadcastId}")) { "Announcement pacing state is unavailable. Stop it and review local storage." }.toLong()

    fun stopBroadcast(id: String, now: Long) = db.runInTransaction {
        dao.activeReminders().filter { it.broadcastId == id && it.state == "PENDING" }.forEach {
            dao.updateReminder(it.copy(state = "CANCELLED",
                detail = "Remaining announcement send stopped before submission.", updatedAt = now))
        }
    }

    private fun recordBroadcastOutcome(row: ReminderEntity, state: ReminderState, now: Long) {
        if (state in setOf(ReminderState.FAILED, ReminderState.UNCERTAIN, ReminderState.EXPIRED))
            row.broadcastId?.let { stopBroadcast(it, now) }
        if (row.broadcastId != null && row.state == "SUBMITTING" && state == ReminderState.SENT) {
            val delay = requireNotNull(row.broadcastDelaySeconds) { "Announcement delay is missing." }
            dao.putValue(StoredValue("broadcast-next:${row.broadcastId}", (now + delay * 1_000).toString()))
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
        if (row.broadcastId != null) return
        val date = appointmentDate(row)
        val stillApproved = dao.reminders().any {
            it.broadcastId == null && appointmentDate(it) == date && it.state !in setOf("CANCELLED", "EXPIRED")
        }
        if (!stillApproved) dao.removeApproval(date)
    }

    fun claim(id: String, attempt: String, owner: String, now: Long): ReminderEntity? {
        var claimed: ReminderEntity? = null
        db.runInTransaction {
            val row = dao.reminder(id) ?: return@runInTransaction
            if (row.state != "PENDING" || !row.dispatchable(now) || now < availableAt(row)) return@runInTransaction
            if (row.broadcastId != null && dao.reminders().any {
                it.broadcastId == row.broadcastId && it.state in setOf("SUBMITTING", "FAILED", "UNCERTAIN", "EXPIRED")
            }) return@runInTransaction
            val timeout = if (row.group) Outcomes.MMS_CALLBACK_TIMEOUT else Outcomes.CALLBACK_TIMEOUT
            if (dao.claim(id, attempt, owner, now + timeout, now) == 1) {
                dao.putTombstone(Tombstone(row.id))
                if (row.broadcastId == null) row.people().forEach { person ->
                    dao.putTombstone(Tombstone(Scheduling.identity(listOf(person), row.appointmentAt)))
                }
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
        recordBroadcastOutcome(row, state, now)
    }

    fun callback(id: String, attempt: String, index: Int, delivery: Boolean, code: Int, now: Long,
        deliveryStatus: Int? = null) =
        db.runInTransaction {
            val row = dao.reminder(id) ?: return@runInTransaction
            if (row.attemptId != attempt || index !in 0 until row.parts || (delivery && row.group)) return@runInTransaction
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
                        ReminderState.SENT -> if (row.group) "Carrier MMS send succeeded. Per-person delivery and group replies are not confirmed; check Messages with participants."
                            else "All parts accepted; delivery is not guaranteed."
                        ReminderState.FAILED -> "Transport reported a submission failure. No automatic retry."
                        ReminderState.UNCERTAIN -> "Submission may be partial or unknown. Do not resend."
                        else -> row.detail
                    }, updatedAt = if (row.state == "SUBMITTING") now else row.updatedAt))
                recordBroadcastOutcome(row, state, now)
            }
        }

    fun reconcile(now: Long, owner: String, retentionDays: Int) = db.runInTransaction {
        dao.activeReminders().forEach { row ->
            when {
                row.state == "PENDING" && row.expired(now) -> {
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
        dao.reminders().filter { it.broadcastId != null &&
            it.state in setOf("FAILED", "UNCERTAIN", "EXPIRED") }.forEach {
            stopBroadcast(requireNotNull(it.broadcastId), now)
        }
        dao.finalizedBefore(now - retentionDays * 86_400_000L).forEach { row ->
            if (row.state != "CANCELLED") dao.putTombstone(Tombstone(row.id))
            dao.deleteReminder(row.id)
        }
        dao.deleteUnusedBroadcastPacing()
    }

    fun deleteHistory() = db.runInTransaction {
        dao.reminders().filter { it.state in setOf("SENT", "FAILED", "EXPIRED", "CANCELLED") ||
            (it.state == "UNCERTAIN" && isReviewed(it.id)) }.forEach {
            if (it.state != "CANCELLED") dao.putTombstone(Tombstone(it.id))
            dao.deleteReminder(it.id)
            dao.deleteValue("reviewed:${it.id}")
        }
        dao.deleteUnusedBroadcastPacing()
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
        dao.deleteUnusedBroadcastPacing()
        dao.deleteValue("settings")
        dao.deleteValue(TextTemplateStorage.KEY)
        dao.deleteValue("diagnostic")
        dao.deleteValue("background_diagnostic")
        dao.deleteValue("prompt_target")
    }
}

internal fun appointmentDate(row: ReminderEntity): String =
    Instant.ofEpochMilli(row.appointmentAt).atZone(ZoneId.of(row.zone)).toLocalDate().toString()
