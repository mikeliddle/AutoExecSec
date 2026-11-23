@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package local.reminders.ui

import android.Manifest
import android.app.KeyguardManager
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.telephony.SmsManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import kotlinx.coroutines.launch
import local.reminders.domain.*
import local.reminders.runtime.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class Page(val label: String, val tabLabel: String = label) {
    QUEUE("Queue"), IMPORT("Schedule"), BROADCAST("Announcements", "Announce"),
    PEOPLE("People"), SETUP("Setup"), TEMPLATES("Templates")
}
private val tabs = listOf(
    Page.QUEUE to Icons.AutoMirrored.Filled.List, Page.IMPORT to Icons.Default.AddCircleOutline,
    Page.BROADCAST to Icons.Default.Sms, Page.PEOPLE to Icons.Default.PeopleOutline,
    Page.TEMPLATES to Icons.Default.Description
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderApp(model: ReminderViewModel, state: UiState, openImport: Boolean, consumed: () -> Unit) {
    var history by rememberSaveable { mutableStateOf(arrayListOf(Page.QUEUE.name)) }
    val page = Page.valueOf(history.last())
    val showUp = page == Page.SETUP ||
        (page == Page.IMPORT && state.review.isNotEmpty()) ||
        (page == Page.BROADCAST && state.broadcastPreview != null)
    var editingTemplate by remember { mutableStateOf(false) }
    fun open(target: Page) {
        if (target == Page.QUEUE) history = arrayListOf(Page.QUEUE.name)
        else if (target.name == history.getOrNull(history.lastIndex - 1)) history = ArrayList(history.dropLast(1))
        else if (target != page) history = ArrayList(history + target.name)
    }
    fun selectTab(target: Page) {
        if (target == Page.TEMPLATES || page == Page.TEMPLATES) open(target)
        else if (target != page) history = if (target == Page.QUEUE) arrayListOf(Page.QUEUE.name)
            else arrayListOf(Page.QUEUE.name, target.name)
    }
    fun back() {
        if (state.busy) return
        when {
            page == Page.IMPORT && state.review.isNotEmpty() -> model.backToTable()
            page == Page.BROADCAST && state.broadcastPreview != null -> model.clearBroadcastPreview()
            history.size > 1 -> history = ArrayList(history.dropLast(1))
        }
    }
    val scrollStates = Page.entries.associateWith { rememberScrollState() }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(openImport, page) {
        if (openImport && !(page == Page.TEMPLATES && editingTemplate)) { open(Page.IMPORT); consumed() }
    }
    LaunchedEffect(state.feedback) {
        state.feedback?.let {
            snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Long)
            model.clearFeedback()
        }
    }
    BackHandler(page != Page.QUEUE) { back() }
    BoxWithConstraints {
        val wide = maxWidth >= 700.dp
        Scaffold(
            topBar = {
                TopAppBar(title = {
                    Text(if (page == Page.QUEUE) "Auto ExecSec" else page.label)
                }, navigationIcon = {
                    if (showUp) IconButton(onClick = ::back, enabled = !state.busy) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Go back")
                    }
                }, actions = {
                    if (page != Page.SETUP) IconButton(onClick = { open(Page.SETUP) },
                        enabled = !state.busy && !(page == Page.TEMPLATES && editingTemplate)) {
                        Icon(Icons.Default.Settings, contentDescription = "Open setup")
                    }
                    if (page == Page.QUEUE || page == Page.SETUP) IconButton(onClick = model::refresh, enabled = !state.busy) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh outcomes and capability checks")
                    }
                })
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!wide) NavigationBar {
                    tabs.forEach { (item, icon) ->
                        NavigationBarItem(selected = item == page, onClick = { selectTab(item) },
                            enabled = page != Page.TEMPLATES || !editingTemplate || item == page,
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(item.tabLabel) })
                    }
                }
            }
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding).imePadding()) {
                if (wide) NavigationRail {
                    tabs.forEach { (item, icon) ->
                        NavigationRailItem(selected = item == page, onClick = { selectTab(item) },
                            enabled = page != Page.TEMPLATES || !editingTemplate || item == page,
                            icon = { Icon(icon, contentDescription = null) },
                            label = { Text(item.tabLabel) })
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                    if (state.loading) {
                        CircularProgressIndicator(Modifier.padding(32.dp))
                    } else key(page) { Column(Modifier.widthIn(max = 760.dp).fillMaxWidth()
                        .verticalScroll(scrollStates.getValue(page)).padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        val openSetup: () -> Unit = {
                            scope.launch { scrollStates.getValue(Page.SETUP).scrollTo(0) }
                            open(Page.SETUP)
                        }
                        when (page) {
                            Page.QUEUE -> QueueScreen(model, state, { open(Page.IMPORT) }, { open(Page.PEOPLE) },
                                openSetup, { open(Page.BROADCAST) })
                            Page.IMPORT -> ImportScreen(model, state, { open(Page.PEOPLE) }, openSetup,
                                { open(Page.TEMPLATES) }) { open(Page.QUEUE) }
                            Page.BROADCAST -> BroadcastScreen(model, state, { open(Page.PEOPLE) }, openSetup)
                            Page.PEOPLE -> PeopleScreen(model, state, openSchedule = { open(Page.IMPORT) }) {
                                scope.launch { scrollStates.getValue(Page.PEOPLE).animateScrollTo(0) }
                            }
                            Page.SETUP -> SetupScreen(model, state)
                            Page.TEMPLATES -> TextTemplatesScreen(state.textTemplates, state.busy,
                                model::saveTextTemplate, model::deleteTextTemplate,
                                onEditing = { editingTemplate = it },
                                resetScroll = { scope.launch { scrollStates.getValue(Page.TEMPLATES).scrollTo(0) } })
                        } }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
internal fun Section(title: String, explanation: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
        if (explanation != null) Text(explanation, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun Notice(title: String, body: String, error: Boolean = false) {
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, modifier = Modifier.semantics { heading() }, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
internal fun Confirm(title: String, detail: String, action: String, dismiss: () -> Unit, accept: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(detail) },
        confirmButton = { TextButton(onClick = { accept(); dismiss() }) { Text(action) } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Keep unchanged") } })
}

private fun stamp(epoch: Long, zone: String) = Instant.ofEpochMilli(epoch).atZone(ZoneId.of(zone))
    .format(DateTimeFormatter.ofPattern("EEE, MMM d \u00b7 h:mm a", Locale.US))

private val UiState.sendingReady: Boolean
    get() = capabilities?.let { it.ready && it.subscriptions.any { sim -> sim.id == settings.subscriptionId } } == true

private val UiState.sendingBlockers: List<String>
    get() {
        val phone = capabilities ?: return listOf("Phone checks have not completed. Refresh to check again.")
        return phone.blockers + if (phone.subscriptions.isNotEmpty() &&
            phone.subscriptions.none { it.id == settings.subscriptionId }) listOf("Choose the SIM to use for new messages.") else emptyList()
    }

@Composable
private fun QueueScreen(model: ReminderViewModel, state: UiState, import: () -> Unit, people: () -> Unit,
    setup: () -> Unit, announcements: () -> Unit) {
    val appointments = state.reminders.filter { it.broadcastId == null }
    val pending = appointments.filter { it.state == ReminderState.PENDING }
    val batchPending = state.reminders.count { it.broadcastId != null &&
        it.state in setOf(ReminderState.PENDING, ReminderState.SUBMITTING) }
    val (nextLabel, nextAction) = when {
        state.recipients.isEmpty() -> "Add people" to people
        state.review.isNotEmpty() -> "Review reminders" to import
        else -> "Add or edit appointments" to import
    }
    Section("Prepare reminders", "Add people and appointments, then check each message. Preparing a schedule does not send anything.")
    Button(onClick = nextAction, enabled = !state.busy) { Text(nextLabel) }
    if (state.sendingReady) {
        Text("Phone ready for approved messages", style = MaterialTheme.typography.labelLarge)
    } else {
        Text("Previewing is available, but approval requires phone setup.", style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = setup, enabled = !state.busy) { Text("Set up sending") }
    }
    state.diagnostic?.let { Notice("Background processing needs attention", it, true) }
    if (batchPending > 0) {
        Notice("Announcement in progress", "$batchPending separate SMS outcomes pending.")
        TextButton(onClick = announcements, enabled = !state.busy) { Text("View announcement outcomes") }
    }
    Section("Your reminder queue", if (pending.isEmpty()) "No reminders waiting." else "${pending.size} approved reminders waiting.")
    var noDate by remember { mutableStateOf(LocalDate.now().plusDays(1).toString()) }
    var markConfirm by remember { mutableStateOf(false) }
    Details("No reminders for a date") {
    Text("Mark a checked date to skip its night-before input prompt.")
    OutlinedTextField(noDate, { noDate = it }, label = { Text("Work date · yyyy-MM-dd") }, singleLine = true,
        modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = { markConfirm = true }, enabled = !state.busy) { Text("Mark no reminders") }
    if (markConfirm) Confirm("Mark this date as no reminders?",
        "This suppresses the input prompt for $noDate. It will not recall submitted texts or cancel already approved reminders.",
        "Mark date", { markConfirm = false }, { model.markNoReminders(noDate) })
    if (state.coveredDates.isNotEmpty()) Text(
        "Prepared / no-reminder dates: ${state.coveredDates.sorted().takeLast(14).joinToString()}",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider()
    if (appointments.isNotEmpty()) {
        appointments.sortedWith(compareBy<ReminderRecord> { it.state != ReminderState.PENDING }.thenBy { it.sendAt }).forEach { record ->
            ReminderItem(record, !state.busy, { model.cancel(record.id) }, { model.edit(record, import) },
                { model.acknowledgeUncertain(record.id) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun ReminderItem(record: ReminderRecord, enabled: Boolean, cancel: () -> Unit, edit: () -> Unit, acknowledge: () -> Unit) {
    var confirming by remember(record.id) { mutableStateOf<String?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(record.recipients.joinToString(", ") { it.name }, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                Text(record.state.name.lowercase().replaceFirstChar { it.uppercase() },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        Text(record.recipients.joinToString("\n") { "${it.name}: ${it.phone}" } + "\nSIM ${record.subscriptionId}", style = MaterialTheme.typography.bodyMedium)
        Text("Appointment: ${stamp(record.appointmentAt, record.zone)}\nSend: ${stamp(record.sendAt, record.zone)}\nZone: ${record.zone}",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(record.message, style = MaterialTheme.typography.bodyLarge)
        Text(if (record.group) "Shared group MMS · per-person delivery unavailable · replies in Messages" else
            "${record.parts} SMS segment(s) · ${if (record.delivered) "Delivery reported" else "Delivery not confirmed"}",
            style = MaterialTheme.typography.labelMedium)
        if (record.detail.isNotEmpty()) Text(record.detail, style = MaterialTheme.typography.bodySmall)
        if (record.state == ReminderState.UNCERTAIN) Notice("Check your messaging history before sending again",
            "Some or all of this message may have been submitted. A carrier can still deliver it later. Recording review does not confirm delivery.", true)
        if (record.state == ReminderState.UNCERTAIN && !record.reviewed) {
            OutlinedButton(onClick = { confirming = "acknowledge" }, enabled = enabled) { Text("Record manual review") }
        } else if (record.state == ReminderState.UNCERTAIN) {
            Text("Manual review recorded · outcome remains uncertain", style = MaterialTheme.typography.labelMedium)
        }
        if (record.state == ReminderState.PENDING) FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { confirming = "edit" }, enabled = enabled) { Text("Edit & reapprove") }
            TextButton(onClick = { confirming = "cancel" }, enabled = enabled) { Text("Cancel") }
        }
    }
    confirming?.let { action ->
        if (action == "acknowledge") Confirm("Record your manual review?",
            "Confirm only after checking the phone's SMS history and considering the risk of late delivery. This records review, not delivery; the outcome stays uncertain and duplicate protection remains. It permits later local data deletion, not resending.",
            "Record review", { confirming = null }, acknowledge)
        else Confirm(if (action == "edit") "Cancel approval before editing?" else "Cancel this reminder?",
                "This works only while pending. Dispatch and cancellation are protected against races; an SMS already submitted cannot be recalled.",
                if (action == "edit") "Cancel & edit" else "Cancel reminder", { confirming = null },
                if (action == "edit") edit else cancel)
    }
}

@Composable
private fun ImportScreen(model: ReminderViewModel, state: UiState, people: () -> Unit, setup: () -> Unit,
    templates: () -> Unit, approved: () -> Unit) {
    if (state.review.isEmpty()) {
        if (state.recipients.isEmpty()) {
            Notice("Add people before review", "Save names and numbers so you can match each appointment to a recipient.")
            OutlinedButton(onClick = people, enabled = !state.busy) { Text("Add people") }
        }
        ScheduleTable(model, state)
    } else ReviewScreen(model, state, setup, templates, approved)
}

@Composable
private fun ReviewScreen(model: ReminderViewModel, state: UiState, setup: () -> Unit,
    templates: () -> Unit, approved: () -> Unit) {
    val context = LocalContext.current
    var selecting by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    val sms = remember(state.settings.subscriptionId) {
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java)
            .createForSubscriptionId(state.settings.subscriptionId)
        else {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(state.settings.subscriptionId)
        }
    }
    val draftResult = try { model.drafts(state) to null } catch (error: IllegalArgumentException) {
        emptyList<ReminderDraft>() to error.message
    } catch (_: DateTimeException) {
        emptyList<ReminderDraft>() to "Appointment date, time or zone is invalid."
    }
    val drafts = draftResult.first
    var checked by remember(drafts, state.settings, state.scheduledTemplate) { mutableStateOf(false) }
    var confirm by remember(drafts, state.settings, state.scheduledTemplate) { mutableStateOf(false) }
    val selectedSim = state.capabilities?.subscriptions?.singleOrNull { it.id == state.settings.subscriptionId }
    val approvalReady = state.sendingReady
    var segmentError: String? = null
    val segments = drafts.associate { draft ->
        val count = if (draft.group) 1 else sms.divideMessage(draft.message).size
        if (draft.group) {
            val error = try {
                val bytes = MmsPdu.encode(draft.recipients.map { it.phone }, draft.message, "00000000-0000-0000-0000-000000000000").size
                state.capabilities?.mms?.get(state.settings.subscriptionId)?.check(draft.recipients.size, bytes)
                    ?: if (state.capabilities?.mms?.containsKey(state.settings.subscriptionId) != true) "Carrier MMS capability has not been verified." else null
            } catch (error: IllegalArgumentException) { error.message }
            if (error != null) segmentError = error
        } else if (count > Limits.MAX_SEGMENTS) segmentError = "A message exceeds ${Limits.MAX_SEGMENTS} segments. Shorten the template and import again."
        draft.id to count
    }
    var unappliedEdits = false
    ScheduleTemplatePicker(state, model::selectScheduledTemplate, templates)
    Section("Check every text", "These exact values will be frozen when approved. Changing recipient, template or appointment requires fresh approval.")
    state.review.forEachIndexed { rowIndex, row ->
        var editingTime by remember(row.source.line) { mutableStateOf(false) }
        var date by remember(row.source) { mutableStateOf(row.source.date) }
        var time by remember(row.source) { mutableStateOf(row.source.time) }
        var zone by remember(row.source) { mutableStateOf(row.source.zone) }
        if (date != row.source.date || time != row.source.time || zone != row.source.zone) unappliedEdits = true
        val draft = drafts.getOrNull(rowIndex)
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Row ${row.source.line} · ${row.source.date} ${row.source.time}", style = MaterialTheme.typography.titleMedium)
            Text("Appointment zone: ${row.source.zone}", style = MaterialTheme.typography.bodyMedium)
            row.recipientIds.forEachIndexed { member, id ->
                val person = state.recipients.singleOrNull { it.id == id }
                Text("Source recipient ${member + 1}: ${row.source.hints[member]}", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { selecting = row.source.line to member }, enabled = !state.busy) {
                    Text(if (person == null) "Resolve recipient" else person.name)
                }
                if (person != null) Text("To: ${person.phone}", style = MaterialTheme.typography.titleMedium)
            }
            TextButton(onClick = { editingTime = !editingTime }, enabled = !state.busy) { Text("Change appointment") }
            if (editingTime) {
                OutlinedTextField(date, { date = it }, label = { Text("Date · yyyy-MM-dd") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(time, { time = it }, label = { Text("Time · HH:mm or h:mm AM/PM") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(zone, { zone = it }, label = { Text("Appointment time zone") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { model.updateAppointment(row.source.line, date, time, zone) }, enabled = !state.busy) { Text("Update preview") }
            }
            if (draft != null) {
                Text(draft.message, style = MaterialTheme.typography.bodyLarge)
                Text("Appointment: ${stamp(draft.appointmentAt, draft.zone)}\nSend: ${stamp(draft.sendAt, draft.zone)}\nZone: ${draft.zone}",
                    style = MaterialTheme.typography.bodyMedium)
                Text(if (draft.group) "One shared group MMS · ${draft.recipients.size} recipients" else
                    "${segments.getValue(draft.id)} SMS segment(s)", style = MaterialTheme.typography.labelLarge)
            }
        }
        HorizontalDivider()
    }
    if (draftResult.second != null) Notice("Preview needs attention", draftResult.second!!, true)
    if (unappliedEdits) Notice("Apply appointment edits first", "Tap Update preview for each changed appointment. Approval uses only the displayed validated preview.", true)
    if (segmentError != null) Notice("Message transport is blocked", segmentError!!, true)
    if (state.review.any { it.recipientIds.size > 1 }) Notice("Shared conversation",
        "Everyone sees the other recipients' numbers and replies. Replies stay in your normal Messages app. MMS may need mobile data and carrier charges. Carrier send success is not per-person delivery or proof of a shared thread. Android/carrier transport can retry or arrive late after submission; no SMS fallback or app retry.")
    if (!approvalReady) {
        Notice("Approval is blocked", state.sendingBlockers.joinToString("\n"), true)
        OutlinedButton(onClick = setup, enabled = !state.busy && !unappliedEdits) { Text("Set up sending") }
    }
    Text("SIM: ${selectedSim?.label ?: "Choose a SIM in Setup"}" +
        (if (state.capabilities?.subscriptions?.size == 1 && selectedSim != null) " (only active SIM, selected automatically for new approvals)" else "") +
        "\nLead time: ${state.settings.leadMinutes} minutes",
        style = MaterialTheme.typography.bodyMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, { checked = it })
        Text("I checked every number, message and time. I authorize automatic SMS or shared group MMS as previewed through this SIM, including number/reply visibility for groups. Carrier charges may apply.",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
    Button(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth(),
        enabled = !state.busy && checked && !unappliedEdits && drafts.isNotEmpty() && draftResult.second == null && segmentError == null && approvalReady) {
        Text("Approve ${state.review.size} reminders")
    }
    TextButton(onClick = model::backToTable, enabled = !state.busy) { Text("Back to table") }
    if (confirm) Confirm("Approve automated sending?", "Approve ${state.review.size} frozen reminders. They may send unattended at the reviewed times, through the selected SIM. Submission is not a guarantee of delivery.",
        "Approve reminders", { confirm = false },
        { model.approve(drafts, state.settings.subscriptionId, state.scheduledTemplate, approved) })
    selecting?.let { (line, member) ->
        AlertDialog(onDismissRequest = { selecting = null }, title = { Text("Choose the exact recipient") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (state.recipients.isEmpty()) Text("Add a recipient in People, then import again.")
                    state.recipients.forEach { person ->
                        TextButton(onClick = { model.selectRecipient(line, person.id, member); selecting = null },
                            modifier = Modifier.fillMaxWidth()) { Text("${person.name}\n${person.phone}") }
                    }
                }
            }, confirmButton = { TextButton(onClick = { selecting = null }) { Text("Close") } })
    }
}

@Composable
internal fun ScheduleTemplatePicker(state: UiState, select: (ScheduledTemplate?) -> Unit, openTemplates: () -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    val active = state.scheduledTemplate
    Section("Reminder template", active?.definition?.name ?: "Using your default appointment messages.")
    if (state.textTemplates.isEmpty() && active == null) {
        OutlinedButton(onClick = openTemplates, enabled = !state.busy) { Text("Create a reusable template") }
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { choosing = true }, enabled = !state.busy && state.textTemplates.isNotEmpty()) {
                Text(if (active == null) "Load template" else "Change template")
            }
            if (active != null) TextButton(onClick = { select(null) }, enabled = !state.busy) { Text("Use defaults") }
        }
    }
    if (active != null) {
        Text("Applies only to this batch. Appointment time and supported name fields fill in for each reminder.")
        if (active.fields.isNotEmpty()) Text("These values are shared by all reminders in this batch.")
        active.fields.forEach { field ->
            OutlinedTextField(active.values[field].orEmpty(), { select(active.copy(values = active.values + (field to it))) },
                label = { Text(field) }, enabled = !state.busy, modifier = Modifier.fillMaxWidth())
        }
    }
    if (choosing) AlertDialog(onDismissRequest = { choosing = false }, title = { Text("Load template for this batch") },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                state.textTemplates.sortedBy { it.name.lowercase() }.forEach { template ->
                    TextButton(onClick = { select(ScheduledTemplate(template)); choosing = false },
                        modifier = Modifier.fillMaxWidth()) { Text(template.name) }
                }
            }
        }, confirmButton = { TextButton(onClick = { choosing = false }) { Text("Cancel") } })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupScreen(model: ReminderViewModel, state: UiState) {
    val context = LocalContext.current
    val preferences = state.settings.copy(subscriptionId = -1, subscriptionAutomatic = false)
    var settings by remember(preferences) { mutableStateOf(preferences) }
    var country by remember(preferences) { mutableStateOf(preferences.country) }
    var lead by remember(preferences) { mutableStateOf(preferences.leadMinutes.toString()) }
    var promptTime by remember(preferences) { mutableStateOf(preferences.promptTime.toString()) }
    var oneOff by remember(preferences) { mutableStateOf(preferences.promptDates.sorted().joinToString("\n")) }
    var retention by remember(preferences) { mutableStateOf(preferences.retentionDays.toString()) }
    var saveConfirm by remember { mutableStateOf(false) }
    var deletion by remember { mutableStateOf<String?>(null) }
    var validation by remember { mutableStateOf<String?>(null) }
    var simPicker by remember { mutableStateOf(false) }
    var templateCount by remember { mutableStateOf("2") }
    var groupTemplate by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.refresh() }
    Section("Phone setup", "Needed to approve messages for automatic sending.")
    Notice(if (state.sendingReady) "Ready to send approved messages" else "Setup needs attention",
        state.sendingBlockers.joinToString("\n").ifEmpty { "Permissions and SIM are ready. Carrier acceptance and timing are not guaranteed." },
        !state.sendingReady)
    val phoneAccess: @Composable () -> Unit = {
    Button(onClick = {
        val needed = mutableListOf(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= 33) needed += Manifest.permission.POST_NOTIFICATIONS
        permission.launch(needed.toTypedArray())
    }, enabled = !state.busy) { Text("Request SMS, SIM & notification access") }
    if (Build.VERSION.SDK_INT >= 31) OutlinedButton(onClick = {
        context.startActivity(Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, "package:${context.packageName}".toUri()))
    }) { Text("Open exact-alarm access") }
    OutlinedButton(onClick = {
        context.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
    }) { Text("Open notification settings") }
    }
    if (state.sendingReady) Details("Manage Android access") { phoneAccess() } else phoneAccess()
    Details("Permission & carrier help") {
        Text("SMS access requires installer allowlisting and Android approval; sideloading alone is not enough. No default SMS role, app internet, contacts or inbox access. Groups use carrier MMS, not RCS; replies stay in Messages.")
    }
    HorizontalDivider()
    Section("Sending SIM")
    val subscriptions = state.capabilities?.subscriptions.orEmpty()
    val selectedSim = subscriptions.singleOrNull { it.id == state.settings.subscriptionId }
    if (subscriptions.isEmpty()) Text("No active SIM available to select.")
    else if (subscriptions.size == 1 && selectedSim != null) Text(
        "${selectedSim.label} · only active SIM")
    else {
        if (selectedSim != null) Text("Selected: ${selectedSim.label}")
        OutlinedButton(onClick = { simPicker = true }, enabled = !state.busy) {
            Text(if (selectedSim == null) "Choose sending SIM" else "Change sending SIM")
        }
    }
    if (simPicker) AlertDialog(onDismissRequest = { simPicker = false }, title = { Text("Choose sending SIM") }, text = {
        Column {
            Text("This affects new approvals only. Existing approvals keep their SIM.")
            subscriptions.forEach { sim ->
                TextButton(onClick = {
                    model.selectSendingSim(sim.id)
                    simPicker = false
                }, enabled = !state.busy) {
                    Text("Use ${sim.label} · ID ${sim.id}")
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { simPicker = false }) { Text("Close") } })
    Text("Approved reminders keep their SIM; they never silently switch.", style = MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    Section("Preferences")
    Details("Appointment messages & timing") {
    OutlinedTextField(country, { country = it.uppercase(Locale.ROOT) }, label = { Text("Phone country · two-letter code") },
        supportingText = { Text("Local numbers use this region. + numbers keep their country code.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(lead, { lead = it }, label = { Text("Lead time in minutes") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
    state.capabilities?.mms?.get(state.settings.subscriptionId)?.let { mms ->
        Notice("Group MMS capability", mms.blocker ?: "Carrier permits up to ${minOf(mms.maxRecipients, Limits.MAX_GROUP)} recipients and ${mms.maxBytes} payload bytes. Mobile data/APN availability and shared replies still need carrier testing.",
            mms.blocker != null)
    }
    OutlinedTextField(settings.template, { settings = settings.copy(template = it) }, label = { Text("Template · 1 recipient (SMS)") },
        minLines = 3, modifier = Modifier.fillMaxWidth())
    Section("Group templates", "Choose an exact recipient count. Missing templates block approval; individual text is never silently reused for a group.")
    OutlinedTextField(templateCount, { templateCount = it }, label = { Text("Recipient count · 2-${Limits.MAX_GROUP}") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = {
        groupTemplate = settings.groupTemplates[templateCount.toIntOrNull()].orEmpty()
    }) { Text("Load count template") }
    OutlinedTextField(groupTemplate, { groupTemplate = it }, label = { Text("Group template to set") },
        minLines = 3, modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = {
        try {
            val count = templateCount.toIntOrNull() ?: throw IllegalArgumentException("Enter a recipient count.")
            require(count in 2..Limits.MAX_GROUP) { "Count must be 2-${Limits.MAX_GROUP}." }
            Templates.validate(groupTemplate)
            settings = settings.copy(groupTemplates = settings.groupTemplates + (count to groupTemplate))
            validation = null
        } catch (error: IllegalArgumentException) { validation = error.message }
    }, enabled = !state.busy) { Text("Set count template") }
    settings.groupTemplates.toSortedMap().forEach { (count, template) ->
        Text("$count recipients: $template", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { settings = settings.copy(groupTemplates = settings.groupTemplates - count) },
            enabled = !state.busy) { Text("Remove $count-recipient template") }
    }
    Text("Set the count template, then Save preferences. Tokens: {FirstName} (also {firstName} or {Firstname}) joins first names with commas. {LastName} uses one shared explicit surname; missing or different surnames block approval and show a warning. Use {FirstName} for mixed-surname groups. {time} includes AM/PM; {dayPeriod} is today before 6 PM and tonight thereafter in the appointment zone. Send and appointment must be on the same date.",
        style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider()
    Details("Night-before prompt") {
    Text("Notify the preceding evening for these work days. Prepared or no-reminder dates skip the prompt.")
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(settings.promptEnabled, { settings = settings.copy(promptEnabled = it) })
        Spacer(Modifier.width(12.dp)); Text("Enable evening input prompt")
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DayOfWeek.entries.forEach { day ->
            FilterChip(selected = day in settings.promptWeekdays, onClick = {
                settings = settings.copy(promptWeekdays = if (day in settings.promptWeekdays) settings.promptWeekdays - day else settings.promptWeekdays + day)
            }, label = { Text(day.name.take(3).lowercase().replaceFirstChar { it.uppercase() }) })
        }
    }
    OutlinedTextField(promptTime, { promptTime = it }, label = { Text("Evening local time · HH:mm") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(oneOff, { oneOff = it }, label = { Text("Additional work dates · yyyy-MM-dd, one per line") },
        minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth())
    Text("Prompts use one-shot inexact idle-capable alarms, so Android may delay them. Appointment SMS uses exact-alarm access but idle throttling and phone state can still delay dispatch.",
        style = MaterialTheme.typography.bodySmall)
    }
    HorizontalDivider()
    Details("Privacy & retention") {
    Text("Encrypted local database. Background sends work after first device unlock; viewing authentication is not per-send approval.")
    val secure = context.getSystemService(KeyguardManager::class.java).isDeviceSecure
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(settings.screenLock, { settings = settings.copy(screenLock = it) }, enabled = secure)
        Spacer(Modifier.width(12.dp)); Text("Require device credential to view")
    }
    if (!secure) Text("Set a device PIN/password first to enable the optional viewing lock.", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(retention, { retention = it }, label = { Text("Completed history retention · days, 1-365") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
    Text("Uncertain outcomes remain for manual review. Deleting history keeps non-reversible duplicate-protection fingerprints. Screenshots are disabled. Backups and device transfer exclude app data.",
        style = MaterialTheme.typography.bodySmall)
    Notice("Outside app-private protection",
        "Source files and clipboard, Android's system SMS history/backups, carriers and recipient devices are separate. Android records non-default apps' sent SMS in the system provider. Deleting local app data does not delete or recall those messages.")
    }
    if (validation != null) Notice("Check settings", validation!!, true)
    Button(onClick = { saveConfirm = true }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) { Text("Save preferences") }
    if (saveConfirm) Confirm("Save these settings?", "Changes to template or lead time cancel pending approvals. They must be imported and reviewed again. SIM selection affects only new approvals.",
        "Save settings", { saveConfirm = false }, {
            try {
                val minutes = lead.toLongOrNull() ?: throw IllegalArgumentException("Lead time must be a number.")
                require(minutes in 1..1440) { "Lead time must be 1-1440 minutes." }
                val days = retention.toIntOrNull() ?: throw IllegalArgumentException("Retention must be a number.")
                require(days in 1..365) { "Retention must be 1-365 days." }
                require(country in com.google.i18n.phonenumbers.PhoneNumberUtil.getInstance().supportedRegions) { "Choose a valid two-letter country." }
                Templates.validate(settings.template)
                settings.groupTemplates.values.forEach(Templates::validate)
                val time = LocalTime.parse(promptTime)
                require(time.second == 0 && time.nano == 0) { "Prompt time must use HH:mm." }
                val dates = oneOff.lines().filter { it.isNotBlank() }.map { LocalDate.parse(it.trim()) }.toSet()
                require(dates.size <= 366) { "Use at most 366 additional dates." }
                validation = null
                model.saveSettings(settings.copy(subscriptionId = state.settings.subscriptionId,
                    subscriptionAutomatic = state.settings.subscriptionAutomatic, country = country, leadMinutes = minutes, promptTime = time,
                    promptDates = dates, retentionDays = days))
            } catch (error: IllegalArgumentException) {
                validation = error.message
            } catch (_: DateTimeException) {
                validation = "Use HH:mm for the evening time and yyyy-MM-dd for additional dates."
            }
        })
    HorizontalDivider()
    state.diagnostic?.let { diagnostic -> Details("Background diagnostic") { Notice("Background processing needs attention", diagnostic, true) } }
    Details("Delete local data") {
    Text("Submitted texts cannot be recalled. Active submissions and unreviewed uncertain outcomes block full deletion; review them in Queue first.")
    OutlinedButton(onClick = { deletion = "history" }, enabled = !state.busy) { Text("Delete completed history") }
    OutlinedButton(onClick = { deletion = "all" }, enabled = !state.busy) { Text("Delete recipients, settings & reminders") }
    deletion?.let { what ->
        Confirm(if (what == "all") "Delete local app data?" else "Delete completed history?",
            "This cannot be undone. Pending reminders will be cancelled for full deletion. Submitted SMS and system SMS history remain. Duplicate-protection fingerprints remain to prevent accidental repeat sending.",
            "Delete local data", { deletion = null }, if (what == "all") model::deleteAll else model::deleteHistory)
    }
    }
    HorizontalDivider()
    Details("Reliability limits") {
    Text("Phone off, force-stop, before first unlock, restricted background activity, missing SIM/service or revoked permissions can prevent sending. Missed reminders expire after 15 minutes or at the appointment, whichever comes first. No blind retries.")
    Text("Review your manufacturer's battery/auto-start restrictions manually. This app does not request battery-exemption or run an always-on service. Delivery reports may be unavailable; a submitted message can arrive late.",
        style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun LockedScreen(error: String? = null, unlock: () -> Unit) {
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.systemBarsPadding().padding(28.dp), verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Lock, null, modifier = Modifier.size(40.dp))
            Spacer(Modifier.height(20.dp))
            Text("Your schedule is private", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(12.dp))
            Text("Unlock to view recipients and reminders. Previously approved background sends do not require this viewing unlock.",
                style = MaterialTheme.typography.bodyMedium)
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(24.dp))
            Button(onClick = unlock) { Text("Unlock with device credential") }
        }
    }
}
