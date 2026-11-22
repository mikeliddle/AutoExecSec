package local.reminders.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import local.reminders.domain.Broadcasts
import local.reminders.domain.ReminderState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
internal fun BroadcastScreen(model: ReminderViewModel, state: UiState, openPeople: () -> Unit = {}) {
    var message by remember { mutableStateOf(state.announcementDraft?.message ?: state.settings.broadcastTemplate) }
    var delaySeconds by remember { mutableStateOf(state.announcementDraft?.delay ?: state.settings.broadcastDelaySeconds.toString()) }
    var selected by remember { mutableStateOf(state.announcementDraft?.selected ?: emptySet()) }
    var search by remember { mutableStateOf(state.announcementDraft?.search.orEmpty()) }
    SideEffect { model.rememberAnnouncement(AnnouncementInput(message, delaySeconds, selected, search)) }
    var confirm by remember { mutableStateOf(false) }
    var stop by remember { mutableStateOf<String?>(null) }
    var review by remember { mutableStateOf<String?>(null) }
    val preview = state.broadcastPreview
    val batches = state.reminders.filter { it.broadcastId != null }.groupBy { requireNotNull(it.broadcastId) }
    val active = batches.values.any { rows ->
        rows.any { it.state in setOf(ReminderState.PENDING, ReminderState.SUBMITTING) }
    }
    LaunchedEffect(active) {
        while (active) {
            delay(5_000)
            model.refresh()
        }
    }

    Text("Separate SMS to each person. Replies are private in Messages, not a shared group.",
        style = MaterialTheme.typography.bodyMedium)
    val safety: @Composable () -> Unit = {
    Notice("Consent and carrier permission are your responsibility",
        "Send only occasional, expected messages with each recipient's consent. Saved People are not consent. Exclude opt-outs and honor applicable law. " +
            "Check your actual plan: consent and unlimited texts do not permit automated/bulk messaging.",
        error = true)
    Notice("Sending can be blocked or charged",
        "Carrier filtering or service suspension, Android limits/confirmations and SMS/multipart charges can apply. No count, rate or delivery is guaranteed. These warnings grant no carrier permission or legal waiver.")
    Notice("Local-only is not end-to-end encryption",
        "No app cloud gateway; SMS is not end-to-end encrypted. Messaging apps, backups and providers can retain copies. Android SMS access is not Google Play approval.")
    Text("At most 40 submission attempts/hour, shared with appointment reminders. " +
        "100 numbers can take over two hours. This is an app safeguard, not a carrier allowance. " +
        "Queued texts expire 24 hours after preview; delivery may still occur later after submission. " +
        "Only one recipient is submitted at a time; a failed or uncertain send stops the remainder.",
        style = MaterialTheme.typography.bodyMedium)
    Text("Delay is a minimum after submission results, not delivery. Android and throttling can add waits; pacing grants no carrier permission.",
        style = MaterialTheme.typography.bodySmall)
    }
    if (preview == null) Details("Consent, carrier & privacy") { safety() } else safety()

    if (preview == null) {
        OutlinedTextField(message, { message = it }, label = { Text("Announcement message") },
            minLines = 3, maxLines = 8, enabled = !state.busy && !active, modifier = Modifier.fillMaxWidth())
        Details("Templates & pacing") {
        Text("Use {FirstName} (or {firstName}/{Firstname}) and {LastName} for each person's name. Appointment fields {time}/{dayPeriod} do not apply here. Preview checks every field.")
        OutlinedTextField(delaySeconds, { delaySeconds = it }, label = { Text("Delay between recipients (seconds, 1-3,600)") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            enabled = !state.busy && !active, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { model.saveBroadcastPreferences(message, delaySeconds) },
                enabled = !state.busy && message.isNotBlank(), modifier = Modifier.weight(1f)) { Text("Save template & delay") }
            TextButton(onClick = {
                message = state.settings.broadcastTemplate
                delaySeconds = state.settings.broadcastDelaySeconds.toString()
            }, enabled = !state.busy && !active) { Text("Load saved") }
        }
        TextButton(onClick = { message = state.settings.template }, enabled = !state.busy && !active) {
            Text("Copy appointment template")
        }
        }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Recipients", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            TextButton(onClick = openPeople, enabled = !state.busy) { Text("People & import") }
        }
        OutlinedTextField(search, { search = it }, label = { Text("Find recipients by name or number") },
            singleLine = true, modifier = Modifier.fillMaxWidth())
        val filtered = state.recipients.filter { it.name.contains(search, ignoreCase = true) || it.phone.contains(search) }
        Text("${selected.size} people selected; duplicate phone numbers will receive only one SMS.",
            style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { selected = filtered.take(Broadcasts.MAX_RECIPIENTS).map { it.id }.toSet() },
                enabled = !state.busy && !active && filtered.isNotEmpty()) { Text("Select first 100 matches") }
            TextButton(onClick = { selected = emptySet() }, enabled = !state.busy && !active) { Text("Clear") }
        }
        if (state.recipients.isEmpty()) Text("No recipients yet. Add people in People before reviewing.")
        else if (filtered.isEmpty()) Text("No matching recipients.")
        filtered.forEach { person ->
            val checked = person.id in selected
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                .toggleable(checked, enabled = !state.busy && !active, role = Role.Checkbox,
                    onValueChange = { selected = if (it) selected + person.id else selected - person.id })
                .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked, onCheckedChange = null)
                Spacer(Modifier.width(12.dp))
                Text("${person.name}\n${person.phone}", style = MaterialTheme.typography.bodyLarge)
            }
        }
        Button(onClick = { model.previewBroadcast(selected, message, delaySeconds) },
            enabled = !state.busy && !active && selected.isNotEmpty() && message.isNotBlank(),
            modifier = Modifier.fillMaxWidth()) { Text("Review individual SMS") }
        if (active) Text("Finish or stop the active announcement before preparing another.")
    } else {
        var consent by remember(preview.id) { mutableStateOf(false) }
        var terms by remember(preview.id) { mutableStateOf(false) }
        val sim = state.capabilities?.subscriptions?.singleOrNull { it.id == preview.subscriptionId }
        val ready = state.capabilities?.ready == true && sim != null &&
            state.settings.subscriptionId == preview.subscriptionId
        Section("Review ${preview.recipients.size} individual SMS",
            "Check every rendered message and number. Nothing sends until you acknowledge and confirm.")
        Text("SIM: ${sim?.label ?: "Unavailable"} (subscription ${preview.subscriptionId})\n" +
            "${preview.totalSegments} total SMS segments.\n" +
            "Minimum delay: ${preview.delaySeconds} seconds after each recipient's submission results.\n" +
            "${preview.delaySeconds * (preview.recipients.size - 1)} seconds of configured waits, before throttle/Android delays. " +
            "Long waits can leave queued recipients expired rather than sent.\n" +
            "Queued sends expire: ${DateTimeFormatter.ofPattern("EEE, MMM d, h:mm a z").format(
                Instant.ofEpochMilli(preview.expiresAt).atZone(ZoneId.systemDefault()))}",
            style = MaterialTheme.typography.bodyMedium)
        Text("Preview authorization expires after 15 minutes. Review again if it expires.",
            style = MaterialTheme.typography.bodySmall)
        preview.messages.forEach {
            Text("${it.recipient.name}\nTo: ${it.recipient.phone}\n${it.parts} SMS segment(s)",
                style = MaterialTheme.typography.bodyMedium)
            Text(it.message, style = MaterialTheme.typography.bodyLarge)
            HorizontalDivider()
        }
        if (!ready) Notice("Sending is blocked",
            (state.capabilities?.blockers.orEmpty() + "Select the previewed active SIM in Setup, then review again.")
                .joinToString("\n"), error = true)
        Acknowledgment(consent, { consent = it }, !state.busy,
            "I verified every number and consent for these intended messages, excluded opt-outs, and will honor opt-outs and applicable law.")
        Acknowledgment(terms, { terms = it }, !state.busy,
            "I checked my actual carrier/plan terms for this use and accept the carrier, Android, charges and privacy warnings above. " +
                "Consent/unlimited texting are not permission for bulk sending; these disclaimers grant no permission or legal waiver.")
        Button(onClick = { confirm = true }, enabled = !state.busy && !active && ready && consent && terms,
            modifier = Modifier.fillMaxWidth()) { Text("Send ${preview.recipients.size} separate SMS") }
        TextButton(onClick = { model.clearBroadcastPreview(); confirm = false }, enabled = !state.busy) { Text("Back to compose") }
        if (confirm) Confirm("Send announcement?",
            "Authorize ${preview.recipients.size} separate SMS (${preview.totalSegments} segments) through ${sim?.label}. " +
                "Minimum delay: ${preview.delaySeconds} seconds after each recipient's submission results. " +
                "They may continue in the background under the shared local throttle until the displayed expiry. " +
                "Stop affects only queued sends; submitted texts cannot be recalled. Delivery and carrier acceptance are not guaranteed.",
            "Send announcement", { confirm = false }, { model.approveBroadcast(consent && terms) })
    }

    if (batches.isNotEmpty()) {
        HorizontalDivider()
        Section("Individual outcomes", "Sent means all segments accepted, not guaranteed delivery. No automatic retries. " +
            "If failed or uncertain, inspect the normal messaging app before considering any new send.")
    }
    batches.entries.sortedByDescending { it.value.first().sendAt }.forEach { (id, rows) ->
        val counts = rows.groupingBy { it.state }.eachCount()
        Text("Announcement to ${rows.size} numbers - ${rows.first().broadcastDelaySeconds}s minimum delay",
            style = MaterialTheme.typography.titleMedium)
        Text(counts.entries.joinToString(" | ") { "${it.key.name.lowercase()}: ${it.value}" },
            style = MaterialTheme.typography.labelLarge)
        if (rows.any { it.state == ReminderState.PENDING })
            OutlinedButton(onClick = { stop = id }, enabled = !state.busy) { Text("Stop remaining queued sends") }
        rows.forEach { row ->
            Text("${row.name} - ${row.phone}\n${row.state.name.lowercase()} - " +
                (if (row.delivered) "delivery reported" else "delivery not confirmed") +
                if (row.detail.isBlank()) "" else "\n${row.detail}", style = MaterialTheme.typography.bodyMedium)
            Text(row.message, style = MaterialTheme.typography.bodyLarge)
            if (row.state == ReminderState.UNCERTAIN && !row.reviewed)
                TextButton(onClick = { review = row.id }, enabled = !state.busy) {
                    Text("Record manual outcome review")
                }
        }
        HorizontalDivider()
    }
    stop?.let { id ->
        Confirm("Stop remaining queued sends?",
            "Only recipients still pending are stopped. Already submitted SMS cannot be recalled; uncertain outcomes are not retried.",
            "Stop remaining", { stop = null }, { model.stopBroadcast(id) })
    }
    review?.let { id ->
        Confirm("Record your manual review?",
            "Confirm only after checking the phone's SMS history and considering late delivery. " +
                "This records review, not delivery; the outcome stays uncertain. It permits local deletion, not resending.",
            "Record review", { review = null }, { model.acknowledgeUncertain(id) })
    }
}

@Composable
private fun Acknowledgment(checked: Boolean, change: (Boolean) -> Unit, enabled: Boolean, text: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(checked, enabled = enabled, role = Role.Checkbox, onValueChange = change)
        .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onCheckedChange = null)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
}
