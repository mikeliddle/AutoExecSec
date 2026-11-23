package local.reminders.runtime

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import local.reminders.domain.*
import java.time.*
import java.util.UUID
import java.util.concurrent.locks.ReentrantLock

class ReminderRuntime(context: Context) {
    private val context = context.applicationContext
    private val platform = Platform(this.context).also { it.channels() }
    private val owner = UUID.randomUUID().toString()
    private val lock = ReentrantLock()
    @Volatile private var receiverFailed = false
    private val database by lazy { RuntimeDatabase.encrypted(this.context) }
    private val dao get() = database.dao()
    private val transactions get() = Transactions(database)
    val store = ReminderStore { database }

    fun capabilities(): Capabilities = platform.capabilities()

    suspend fun textTemplates(): List<TextTemplate> = locked {
        TextTemplateStorage.decode(dao.value(TextTemplateStorage.KEY))
    }

    suspend fun saveTextTemplate(template: TextTemplate) = locked {
        TextTemplates.validate(template)
        val current = TextTemplateStorage.decode(dao.value(TextTemplateStorage.KEY))
        val updated = current.filterNot { it.id == template.id } + template.copy(name = template.name.trim())
        dao.putValue(StoredValue(TextTemplateStorage.KEY, TextTemplateStorage.encode(updated)))
    }

    suspend fun deleteTextTemplate(id: String) = locked {
        val current = TextTemplateStorage.decode(dao.value(TextTemplateStorage.KEY))
        require(current.any { it.id == id }) { "This template no longer exists. Refresh the list." }
        dao.putValue(StoredValue(TextTemplateStorage.KEY,
            TextTemplateStorage.encode(current.filterNot { it.id == id })))
    }

    private suspend fun <T> locked(block: () -> T): T = withContext(Dispatchers.IO) {
        lock.lockInterruptibly()
        try {
            check(platform.unlocked()) { "Unlock the device before using reminders." }
            block()
        } finally { lock.unlock() }
    }

    suspend fun reconcile() = locked {
        housekeeping()
        scheduleOrReport()
    }

    suspend fun approve(drafts: List<ReminderDraft>, subscriptionId: Int) = approve(drafts, subscriptionId, null)

    internal suspend fun approve(drafts: List<ReminderDraft>, subscriptionId: Int, template: ScheduledTemplate?) = locked {
        housekeeping()
        val caps = capabilities()
        require(caps.ready) { caps.blockers.joinToString("\n") }
        require(caps.subscriptions.any { it.id == subscriptionId }) { "Choose an active SIM explicitly." }
        val settings = SettingsCodec.decode(dao.value("settings"))
        require(subscriptionId == settings.subscriptionId) { "SIM selection changed. Refresh and choose an active SIM before approving." }
        val now = System.currentTimeMillis()
        require(drafts.size in 1..Limits.MAX_BATCH) { "Approve 1-40 reminders at a time." }
        if (template != null) {
            val saved = TextTemplateStorage.decode(dao.value(TextTemplateStorage.KEY))
                .singleOrNull { it.id == template.definition.id }
            require(saved?.body == template.definition.body) { "The saved template changed. Load it again and review the messages." }
        }
        val manager = platform.sms(subscriptionId)
        val rows = drafts.map { draft ->
            val people = draft.recipients.map { frozen ->
                val person = dao.recipient(frozen.id)?.model()
                    ?: throw IllegalArgumentException("A recipient was removed. Import and approve again.")
                require(person.name == frozen.name && person.phone == frozen.phone) {
                    "A recipient changed. Import and approve again."
                }
                person
            }
            require(people.size in 1..Limits.MAX_GROUP && people.map { it.id }.distinct().size == people.size &&
                people.map { it.phone }.distinct().size == people.size &&
                draft.recipientId == people.first().id && draft.name == people.first().name && draft.phone == people.first().phone) {
                "The recipient list needs a fresh preview."
            }
            val appointment = Instant.ofEpochMilli(draft.appointmentAt).atZone(ZoneId.of(draft.zone))
            val send = Instant.ofEpochMilli(draft.sendAt).atZone(appointment.zone)
            require(draft.sendAt > now && draft.sendAt < draft.appointmentAt &&
                draft.sendAt == draft.appointmentAt - settings.leadMinutes * 60_000 &&
                send.toLocalDate() == appointment.toLocalDate()) { "The sending time needs a fresh valid preview." }
            val message = if (template == null) Templates.render(settings.templateFor(people.size), people, appointment)
                else template.render(people, appointment)
            require(draft.message == message) {
                "The template changed. Import and approve again."
            }
            val digest = Scheduling.identity(people, draft.appointmentAt)
            require(draft.id == digest) { "Invalid appointment identity. Import and approve again." }
            val parts = if (draft.group) {
                val blocker = platform.mmsBlocker(subscriptionId, people.map { it.phone }, draft.message,
                    "00000000-0000-0000-0000-000000000000", caps)
                require(blocker == null) { blocker.orEmpty() }
                1
            } else manager.divideMessage(draft.message).size
            require(parts in 1..Limits.MAX_SEGMENTS) { "Messages must use 1-6 SMS segments." }
            ReminderEntity(draft.id, draft.recipientId, draft.name, draft.phone, draft.message,
                draft.appointmentAt, draft.sendAt, draft.zone, subscriptionId = subscriptionId,
                parts = parts, updatedAt = now, members = PeopleCodec.encode(people))
        }
        transactions.approve(rows, now)
        platform.clearPrompt()
        scheduleOrReport()
    }

    suspend fun cancel(id: String): Boolean = locked {
        val cancelled = transactions.cancel(id, System.currentTimeMillis())
        scheduleOrReport()
        cancelled
    }

    suspend fun previewBroadcast(ids: Set<String>, template: String, subscriptionId: Int, delaySeconds: Long): BroadcastPreview = locked {
        housekeeping()
        Templates.validate(template)
        Broadcasts.validateDelay(delaySeconds)
        val settings = SettingsCodec.decode(dao.value("settings"))
        require(subscriptionId >= 0 && subscriptionId == settings.subscriptionId) { "Select and save a SIM in Setup first." }
        val selected = ids.map { id ->
            requireNotNull(dao.recipient(id)) { "A selected recipient was removed. Select recipients again." }.model()
        }
        val people = Broadcasts.recipients(selected, settings.country)
        val manager = platform.sms(subscriptionId)
        val messages = people.map {
            val message = Templates.render(template, listOf(it))
            Broadcasts.validateMessage(message)
            val parts = manager.divideMessage(message).size
            require(parts in 1..Limits.MAX_SEGMENTS) { "Shorten the rendered text for ${it.name} to at most 6 SMS segments." }
            BroadcastMessage(it, message, parts)
        }
        val now = System.currentTimeMillis()
        BroadcastPreview(UUID.randomUUID().toString(), messages, template, subscriptionId, delaySeconds, now,
            now + Broadcasts.WINDOW_MILLIS)
    }

    suspend fun approveBroadcast(preview: BroadcastPreview, acknowledged: Boolean) = locked {
        require(acknowledged) { "Read and acknowledge the consent, carrier and privacy warnings before sending." }
        housekeeping()
        val now = System.currentTimeMillis()
        require(now >= preview.preparedAt && now - preview.preparedAt < Broadcasts.PREVIEW_MILLIS &&
            preview.expiresAt == preview.preparedAt + Broadcasts.WINDOW_MILLIS) { "Preview expired. Review the announcement again." }
        val caps = capabilities()
        require(caps.ready) { caps.blockers.joinToString("\n") }
        require(platform.selectedBlocker(preview.subscriptionId, caps) == null) { "The previewed SIM is unavailable." }
        val settings = SettingsCodec.decode(dao.value("settings"))
        require(preview.subscriptionId == settings.subscriptionId) { "SIM selection changed. Review the announcement again." }
        Templates.validate(preview.template)
        Broadcasts.validateDelay(preview.delaySeconds)
        val people = preview.recipients.map {
            require(dao.recipient(it.id)?.model() == it) { "A recipient changed. Review the announcement again." }
            it
        }
        require(Broadcasts.recipients(people, settings.country) == people) { "Announcement numbers need a fresh preview." }
        val manager = platform.sms(preview.subscriptionId)
        val rows = preview.messages.map { rendered ->
            val person = rendered.recipient
            val message = Templates.render(preview.template, listOf(person))
            Broadcasts.validateMessage(message)
            require(message == rendered.message && manager.divideMessage(message).size == rendered.parts) {
                "Rendered message or SMS segment sizing changed. Review the announcement again."
            }
            ReminderEntity("broadcast:${preview.id}:${person.id}", person.id, person.name, person.phone, message,
                0, now, ZoneId.systemDefault().id, subscriptionId = preview.subscriptionId, parts = rendered.parts,
                updatedAt = now, members = PeopleCodec.encode(listOf(person)), broadcastId = preview.id,
                broadcastExpiresAt = preview.expiresAt, broadcastDelaySeconds = preview.delaySeconds)
        }
        transactions.approveBroadcast(rows, now)
        scheduleOrReport()
    }

    suspend fun stopBroadcast(id: String) = locked {
        transactions.stopBroadcast(id, System.currentTimeMillis())
        scheduleOrReport()
    }

    suspend fun saveRecipient(recipient: Recipient) = locked {
        val settings = SettingsCodec.decode(dao.value("settings"))
        val valid = Importer.recipient(recipient.id, recipient.name, recipient.phone, settings.country, recipient.firstName, recipient.lastName)
        transactions.saveRecipients(listOf(valid), System.currentTimeMillis())
        scheduleOrReport()
    }

    suspend fun importRecipients(text: String) = locked {
        val settings = SettingsCodec.decode(dao.value("settings"))
        database.runInTransaction {
            val people = Importer.recipients(text, settings.country, dao.recipients().map { it.model() })
            transactions.saveRecipients(people, System.currentTimeMillis())
        }
        scheduleOrReport()
    }

    suspend fun savePeople(people: List<Recipient>) = locked {
        val settings = SettingsCodec.decode(dao.value("settings"))
        require(people.size in 1..Limits.MAX_BATCH) { "Save 1-40 people at a time." }
        val valid = people.map {
            Importer.recipient(it.id, it.name, it.phone, settings.country, it.firstName, it.lastName)
        }
        transactions.saveRecipients(valid, System.currentTimeMillis())
        scheduleOrReport()
    }

    suspend fun deleteRecipient(id: String) = locked {
        database.runInTransaction {
            transactions.cancelAffected({ row -> row.people().any { it.id == id } }, System.currentTimeMillis())
            dao.deleteRecipient(id)
        }
        scheduleOrReport()
    }

    suspend fun saveSettings(settings: AppSettings) = locked {
        Templates.validate(settings.template)
        Templates.validate(settings.broadcastTemplate)
        Broadcasts.validateDelay(settings.broadcastDelaySeconds)
        require(settings.groupTemplates.keys.all { it in 2..Limits.MAX_GROUP }) { "Group template counts must be 2-${Limits.MAX_GROUP}." }
        settings.groupTemplates.values.forEach(Templates::validate)
        require(settings.leadMinutes in 1..1440) { "Lead time must be 1-1440 minutes." }
        require(settings.retentionDays in 1..365) { "History retention must be 1-365 days." }
        require(settings.promptDates.size <= 366) { "Use at most 366 one-off prompt dates." }
        require(settings.country.uppercase(java.util.Locale.ROOT) in
            com.google.i18n.phonenumbers.PhoneNumberUtil.getInstance().supportedRegions) { "Choose a valid country code." }
        database.runInTransaction {
            val previous = SettingsCodec.decode(dao.value("settings"))
            transactions.cancelAffected({ row ->
                val count = row.people().size
                row.broadcastId == null && (previous.leadMinutes != settings.leadMinutes ||
                    (if (count == 1) previous.template != settings.template
                    else previous.groupTemplates[count] != settings.groupTemplates[count]))
            }, System.currentTimeMillis())
            dao.putValue(StoredValue("settings", SettingsCodec.encode(settings)))
        }
        platform.clearPrompt()
        scheduleOrReport()
    }

    suspend fun markNoReminders(date: LocalDate) = locked {
        require(date >= LocalDate.now()) { "Choose today or a future date." }
        transactions.markNoReminders(date)
        platform.clearPrompt()
        scheduleOrReport()
    }

    suspend fun deleteHistory() = locked {
        transactions.deleteHistory()
        scheduleOrReport()
    }

    suspend fun acknowledgeUncertain(id: String): Boolean = locked {
        transactions.acknowledgeUncertain(id)
    }

    suspend fun deleteAll() = locked {
        transactions.deleteAll()
        platform.cancelWake()
        platform.cancelTimeout()
        platform.cancelPrompt()
        platform.clearPrompt()
        // Reset settings are persisted lazily; no fresh alarm is created by a delete action.
    }

    private fun housekeeping() {
        val settings = SettingsCodec.decode(dao.value("settings"))
        val caps = capabilities()
        val selection = caps.selectionForNewApprovals(settings.subscriptionId, settings.subscriptionAutomatic)
        if (caps.subscriptions.size == 1 && selection != settings.subscriptionId)
            dao.putValue(StoredValue("settings", SettingsCodec.encode(settings.copy(
                subscriptionId = selection, subscriptionAutomatic = true))))
        else if (settings.subscriptionAutomatic && caps.subscriptions.size != 1)
            dao.putValue(StoredValue("settings", SettingsCodec.encode(settings.copy(
                subscriptionId = -1, subscriptionAutomatic = false))))
        if (receiverFailed) {
            dao.putValue(StoredValue("background_diagnostic", "Background processing was interrupted. Review pending and unknown SMS outcomes; no automatic retry is performed."))
            receiverFailed = false
        }
        transactions.reconcile(System.currentTimeMillis(), owner,
            SettingsCodec.decode(dao.value("settings")).retentionDays)
        val unknown = dao.hasUnreviewedUnknown()
        if (unknown) platform.notifyStatus("Some SMS results are unknown. Review history; do not resend.")
    }

    private fun rateAvailableAt(now: Long): Long {
        val entries = dao.rateAttempts()
        entries.filter { it.value.toLong() <= now - RATE_WINDOW }.forEach { dao.deleteValue(it.key) }
        val attempts = entries.map { it.value.toLong() }.filter { it > now - RATE_WINDOW }.sorted()
        return if (attempts.size >= Limits.MAX_BATCH) attempts[attempts.size - Limits.MAX_BATCH] + RATE_WINDOW else now
    }

    private fun scheduleOrReport() {
        try {
            schedule()
        } catch (_: Exception) {
            dao.putValue(StoredValue("diagnostic", "Alarm scheduling failed. Open the app and review permissions; pending approval was not reported as scheduled."))
            platform.notifyStatus("Scheduling needs attention. Open the app to review status.")
            throw IllegalStateException("Alarm scheduling failed. Approval is persisted; review the app before relying on it.")
        }
    }

    private fun schedule() {
        val now = System.currentTimeMillis()
        val rows = dao.activeReminders()
        val caps = capabilities()
        val rateAt = rateAvailableAt(now)
        val candidates = rows.mapNotNull { row ->
            when (row.state) {
                "SUBMITTING" -> null
                "PENDING" -> {
                    val expires = row.expiresAt
                    if (!caps.ready) expires to false
                    else {
                        val blocked = platform.transportBlocker(row, caps)
                        val waiting = row.broadcastId != null && rows.any {
                            it.broadcastId == row.broadcastId && it.state == "SUBMITTING"
                        }
                        val next = maxOf(row.sendAt, rateAt, transactions.availableAt(row),
                            if (waiting) now + SERVICE_RECHECK else now + 1_000,
                            if (row.sendAt <= now && blocked != null) now + SERVICE_RECHECK else now + 1_000)
                        minOf(next, expires) to true
                    }
                }
                else -> null
            }
        }
        val earliest = candidates.minByOrNull { it.first }
        platform.cancelWake()
        if (earliest != null) platform.wake(maxOf(now + 1_000, earliest.first),
            earliest.second && platform.exactAllowed())
        // Callback classification survives exact-access revocation without downgrading SMS alarms.
        platform.cancelTimeout()
        rows.filter { it.state == "SUBMITTING" }.mapNotNull { it.deadline }.minOrNull()?.let {
            platform.timeout(maxOf(now + 1_000, it))
        }
        if (!caps.ready && rows.any { it.state == "PENDING" }) {
            dao.putValue(StoredValue("diagnostic", "Pending reminders are blocked by device capabilities. Open the app to review permissions and SIM availability."))
            platform.notifyStatus("Pending reminders are blocked. Open the app to review permissions and SIM availability.")
        } else {
            dao.deleteValue("diagnostic")
        }
        platform.cancelPrompt()
        val settings = SettingsCodec.decode(dao.value("settings"))
        if (settings.promptEnabled && platform.notificationAllowed(Platform.INPUT_CHANNEL)) {
            val covered = dao.coverage().map { LocalDate.parse(it.date) }.toSet()
            val next = Scheduling.nextPrompt(ZonedDateTime.now(),
                PromptSettings(settings.promptWeekdays, settings.promptTime, settings.promptDates), covered)
            if (next != null) {
                val target = next.toLocalDate().plusDays(1).toString()
                dao.putValue(StoredValue("prompt_target", target))
                platform.prompt(next.toInstant().toEpochMilli(), target)
            } else dao.deleteValue("prompt_target")
        } else dao.deleteValue("prompt_target")
    }

    internal suspend fun receiveAlarm(action: String?, target: String?, budget: Long) = locked {
        if (!withinBudget(budget)) return@locked
        housekeeping()
        if (action == Platform.PROMPT) {
            val settings = SettingsCodec.decode(dao.value("settings"))
            val tomorrow = LocalDate.now().plusDays(1)
            // The intended work date is captured in the alarm; late alarms never prompt for another date.
            if (target == tomorrow.toString() && target == dao.value("prompt_target") &&
                settings.promptEnabled && (tomorrow.dayOfWeek in settings.promptWeekdays || tomorrow in settings.promptDates) &&
                dao.coverage().none { it.date == target }) platform.notifyPrompt()
        } else if (action == Platform.DISPATCH || action == Platform.TIMEOUT) {
            dispatch(budget)
        }
        if (withinBudget(budget)) scheduleOrReport()
    }

    private fun dispatch(budget: Long) {
        val due = dao.activeReminders().filter { it.state == "PENDING" && it.sendAt <= System.currentTimeMillis() }
            .sortedBy { it.broadcastId != null }
            .take(Limits.MAX_BATCH)
        for (candidate in due) {
            if (!withinBudget(budget)) {
                // The existing deadline alarm is already armed before each SMS API invocation.
                armBudgetContinuation()
                return
            }
            val row = dao.reminder(candidate.id) ?: continue
            val now = System.currentTimeMillis()
            if (row.state != "PENDING") continue
            if (row.expired(now)) {
                transactions.finish(row.id, ReminderState.EXPIRED, "The safe sending window has passed.", now)
                continue
            }
            val caps = capabilities()
            val blocked = caps.blockers.firstOrNull() ?: platform.transportBlocker(row, caps)
            if (blocked != null) {
                dao.updateReminder(row.copy(detail = blocked))
                continue
            }
            if (rateAvailableAt(now) > now) {
                dao.updateReminder(row.copy(detail = "Local safety rate limit reached. Waiting only within the safe sending window."))
                continue
            }
            if (transactions.availableAt(row) > now) {
                dao.updateReminder(row.copy(detail = "Waiting for the approved delay between individual recipients."))
                continue
            }
            val manager = platform.sms(row.subscriptionId)
            val parts = try {
                if (row.group) arrayListOf(row.message) else manager.divideMessage(row.message)
            } catch (_: RuntimeException) {
                transactions.finish(row.id, ReminderState.FAILED, "SMS preparation failed before submission. No automatic retry.", now)
                continue
            }
            if (parts.size != row.parts || (!row.group && parts.size !in 1..Limits.MAX_SEGMENTS)) {
                transactions.finish(row.id, ReminderState.FAILED, "SMS segment sizing changed. No message submitted.", now)
                continue
            }
            if (!withinBudget(budget)) {
                armBudgetContinuation()
                return
            }
            val attempt = UUID.randomUUID().toString()
            // Fresh clock and permission/SIM checks immediately before the transactional claim and API.
            val before = System.currentTimeMillis()
            val fresh = capabilities()
            if (!fresh.ready || platform.transportBlocker(row, fresh) != null) continue
            val claimed = transactions.claim(row.id, attempt, owner, before) ?: continue
            try {
                // Persisted SUBMITTING and a deadline precede the real API. Any crash gap stays unknown.
                val deadline = dao.activeReminders().filter { it.state == "SUBMITTING" }
                    .mapNotNull { it.deadline }.minOrNull() ?: claimed.deadline!!
                platform.timeout(deadline)
                val sent = ArrayList(parts.indices.map { platform.callback(row.id, attempt, it, false) })
                val delivered = if (row.group) arrayListOf() else
                    ArrayList(parts.indices.map { platform.callback(row.id, attempt, it, true) })
                if (!withinBudget(budget)) {
                    transactions.finish(row.id, ReminderState.UNCERTAIN, "Dispatch interrupted after claim. Do not resend.", System.currentTimeMillis())
                    armBudgetContinuation()
                    return
                }
                val immediateNow = System.currentTimeMillis()
                if (!row.dispatchable(immediateNow)) {
                    transactions.finish(row.id, ReminderState.EXPIRED, "The safe sending window closed before submission.", immediateNow)
                    continue
                }
                val immediateCaps = capabilities()
                if (!immediateCaps.ready || platform.transportBlocker(row, immediateCaps) != null) {
                    transactions.finish(row.id, ReminderState.FAILED, "Device capability was lost before submission. No automatic retry.", immediateNow)
                    continue
                }
                val finalNow = System.currentTimeMillis()
                if (!row.dispatchable(finalNow)) {
                    transactions.finish(row.id, ReminderState.EXPIRED, "The safe sending window closed before submission.", finalNow)
                    continue
                }
                if (!withinBudget(budget)) {
                    transactions.finish(row.id, ReminderState.UNCERTAIN, "Dispatch interrupted after claim. Do not resend.", finalNow)
                    armBudgetContinuation()
                    return
                }
                if (row.group) manager.sendMultimediaMessage(context,
                    MmsPayloadProvider.uri(context, row.id, attempt), null, null, sent.single())
                else if (parts.size == 1) manager.sendTextMessage(row.phone, null, parts.single(), sent.single(), delivered.single())
                else manager.sendMultipartTextMessage(row.phone, null, parts, sent, delivered)
            } catch (_: SecurityException) {
                transactions.finish(row.id, ReminderState.FAILED, "Messaging permission rejected. No automatic retry.", System.currentTimeMillis())
            } catch (_: Exception) {
                transactions.finish(row.id, ReminderState.UNCERTAIN, "Message submission may have started. Do not resend.", System.currentTimeMillis())
            }
        }
    }

    internal fun mmsPayload(id: String, attempt: String): ByteArray {
        check(platform.unlocked()) { "Unlock required." }
        val row = requireNotNull(dao.reminder(id)) { "MMS payload unavailable." }
        require(row.group && row.attemptId == attempt && row.state in setOf("SUBMITTING", "UNCERTAIN") &&
            row.deadline?.let { System.currentTimeMillis() < it } == true) { "MMS payload unavailable." }
        return MmsPdu.encode(row.people().map { it.phone }, row.message, attempt)
    }

    private fun armBudgetContinuation() {
        val next = minOf(System.currentTimeMillis() + SERVICE_RECHECK,
            dao.activeReminders().filter { it.state == "SUBMITTING" }.mapNotNull { it.deadline }.minOrNull() ?: Long.MAX_VALUE)
        platform.timeout(maxOf(System.currentTimeMillis() + 1_000, next))
    }

    internal suspend fun receiveResult(id: String, attempt: String, index: Int, delivery: Boolean, code: Int,
        deliveryStatus: Int? = null) = locked {
        // Recover a previous process's crash gap before accepting late callbacks.
        housekeeping()
        transactions.callback(id, attempt, index, delivery, code, System.currentTimeMillis(), deliveryStatus)
        housekeeping()
        platform.notifyStatus("Message status updated. Open history to review transport results.")
        scheduleOrReport()
    }

    internal suspend fun reportReceiverFailure() {
        if (!platform.unlocked()) return
        try {
            locked {
                dao.putValue(StoredValue("background_diagnostic", "Background processing was interrupted. Open the app to review pending and unknown SMS outcomes."))
                platform.notifyStatus("Background processing needs attention. Open the app to review status.")
                armBudgetContinuation()
            }
        } catch (_: Exception) {
            platform.notifyStatus("Reminder storage is unavailable. Open the app; existing data has not been reset.")
        }
    }

    internal fun noteReceiverFailure() {
        receiverFailed = true
        platform.notifyStatus("Background processing was interrupted. Open the app to review SMS status.")
    }

    internal fun isUnlocked() = platform.unlocked()
    private fun withinBudget(deadline: Long) =
        !Thread.currentThread().isInterrupted && SystemClock.elapsedRealtime() < deadline - 500

    companion object {
        private const val SERVICE_RECHECK = 60_000L
        private const val RATE_WINDOW = 60 * 60_000L
    }
}
