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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import local.reminders.domain.*
import local.reminders.runtime.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class Page(val label: String) { QUEUE("Queue"), IMPORT("Import"), PEOPLE("People"), SETUP("Setup") }
private val pageIcons = listOf(Icons.AutoMirrored.Filled.List, Icons.Default.AddCircleOutline, Icons.Default.PeopleOutline, Icons.Default.Settings)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReminderApp(model: ReminderViewModel, state: UiState, openImport: Boolean, consumed: () -> Unit) {
    var page by remember { mutableStateOf(Page.QUEUE) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(openImport) { if (openImport) { page = Page.IMPORT; consumed() } }
    LaunchedEffect(state.feedback) {
        state.feedback?.let {
            snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Long)
            model.clearFeedback()
        }
    }
    BackHandler(page != Page.QUEUE) { page = Page.QUEUE }
    BoxWithConstraints {
        val wide = maxWidth >= 700.dp
        Scaffold(
            topBar = {
                TopAppBar(title = {
                    Text(if (page == Page.QUEUE) "Appointment reminders" else page.label)
                }, actions = {
                    IconButton(onClick = model::refresh, enabled = !state.busy) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh outcomes and capability checks")
                    }
                })
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                if (!wide) NavigationBar {
                    Page.entries.forEachIndexed { index, item ->
                        NavigationBarItem(selected = item == page, onClick = { page = item },
                            icon = { Icon(pageIcons[index], contentDescription = null) },
                            label = { Text(item.label) })
                    }
                }
            }
        ) { padding ->
            Row(Modifier.fillMaxSize().padding(padding).imePadding()) {
                if (wide) NavigationRail {
                    Page.entries.forEachIndexed { index, item ->
                        NavigationRailItem(selected = item == page, onClick = { page = item },
                            icon = { Icon(pageIcons[index], contentDescription = null) },
                            label = { Text(item.label) })
                    }
                }
                Box(Modifier.weight(1f), contentAlignment = Alignment.TopCenter) {
                    if (state.loading) {
                        CircularProgressIndicator(Modifier.padding(32.dp))
                    } else Column(Modifier.widthIn(max = 760.dp).fillMaxWidth()
                        .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        if (state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                        when (page) {
                            Page.QUEUE -> QueueScreen(model, state, { page = Page.IMPORT }, { page = Page.SETUP })
                            Page.IMPORT -> ImportScreen(model, state) { page = Page.QUEUE }
                            Page.PEOPLE -> PeopleScreen(model, state)
                            Page.SETUP -> SetupScreen(model, state)
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun Section(title: String, explanation: String? = null) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (explanation != null) Text(explanation, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Notice(title: String, body: String, error: Boolean = false) {
    Surface(color = if (error) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.secondaryContainer,
        shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun Confirm(title: String, detail: String, action: String, dismiss: () -> Unit, accept: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(detail) },
        confirmButton = { TextButton(onClick = { dismiss(); accept() }) { Text(action) } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Keep unchanged") } })
}

private fun stamp(epoch: Long, zone: String) = Instant.ofEpochMilli(epoch).atZone(ZoneId.of(zone))
    .format(DateTimeFormatter.ofPattern("EEE, MMM d \u00b7 h:mm a", Locale.US))

@Composable
private fun QueueScreen(model: ReminderViewModel, state: UiState, import: () -> Unit, setup: () -> Unit) {
    val pending = state.reminders.filter { it.state == ReminderState.PENDING }
    val blockers = state.capabilities?.blockers ?: listOf("Capability checks have not completed. Refresh or reopen the app.")
    if (blockers.isNotEmpty()) {
        Notice("Automatic sending is blocked", blockers.joinToString("\n"), true)
        OutlinedButton(onClick = setup) { Text("Review phone setup") }
    } else Notice("Phone checks passed", "Only explicitly approved reminders can send. Delivery timing and carrier acceptance are not guaranteed.")
    state.diagnostic?.let { Notice("Background processing needs attention", it, true) }
    Section("Your reminder queue", if (pending.isEmpty()) "No approved reminders are waiting." else
        "${pending.size} approved reminders waiting. Each row shows the frozen recipient and message.")
    Button(onClick = import, enabled = !state.busy) {
        Icon(Icons.Default.Add, null); Spacer(Modifier.width(8.dp)); Text("Prepare a schedule")
    }
    var noDate by remember { mutableStateOf(LocalDate.now().plusDays(1).toString()) }
    var markConfirm by remember { mutableStateOf(false) }
    Section("Nothing to send?", "Mark a date only after checking its schedule. This suppresses the preceding evening's input prompt.")
    OutlinedTextField(noDate, { noDate = it }, label = { Text("Work date · yyyy-MM-dd") }, singleLine = true,
        modifier = Modifier.fillMaxWidth())
    OutlinedButton(onClick = { markConfirm = true }, enabled = !state.busy) { Text("Mark no reminders") }
    if (markConfirm) Confirm("Mark this date as no reminders?",
        "This suppresses the input prompt for $noDate. It will not recall submitted texts or cancel already approved reminders.",
        "Mark date", { markConfirm = false }, { model.markNoReminders(noDate) })
    if (state.coveredDates.isNotEmpty()) Text(
        "Prepared / no-reminder dates: ${state.coveredDates.sorted().takeLast(14).joinToString()}",
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    HorizontalDivider()
    if (state.reminders.isEmpty()) {
        Section("Start with People, then Import",
            "Add stable recipient IDs and phone numbers, paste the documented schedule format, then review every text before approving. Nothing sends from an import alone.")
    } else {
        state.reminders.sortedWith(compareBy<ReminderRecord> { it.state != ReminderState.PENDING }.thenBy { it.sendAt }).forEach { record ->
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
            Text(record.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = MaterialTheme.shapes.small) {
                Text(record.state.name.lowercase().replaceFirstChar { it.uppercase() },
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelMedium)
            }
        }
        Text("${record.phone} · SIM ${record.subscriptionId}", style = MaterialTheme.typography.bodyMedium)
        Text("Appointment: ${stamp(record.appointmentAt, record.zone)}\nSend: ${stamp(record.sendAt, record.zone)}\nZone: ${record.zone}",
            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(record.message, style = MaterialTheme.typography.bodyLarge)
        Text("${record.parts} SMS segment(s) · ${if (record.delivered) "Delivery reported" else "Delivery not confirmed"}",
            style = MaterialTheme.typography.labelMedium)
        if (record.detail.isNotEmpty()) Text(record.detail, style = MaterialTheme.typography.bodySmall)
        if (record.state == ReminderState.UNCERTAIN) Notice("Manual review required",
            "Some or all segments may have been submitted. Do not resend blindly. Check the phone's SMS history; a carrier can still deliver later.", true)
        if (record.state == ReminderState.UNCERTAIN && !record.reviewed) {
            OutlinedButton(onClick = { confirming = "acknowledge" }, enabled = enabled) { Text("Acknowledge outcome review") }
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
private fun ImportScreen(model: ReminderViewModel, state: UiState, approved: () -> Unit) {
    var text by remember { mutableStateOf("") }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.readFile(uri) { text = it }
    }
    if (state.review.isEmpty()) {
        Section("Prepare, review, approve", "Paste only the schedule fields below. The original paste or selected file is not stored in the app.")
        Notice("Supported CSV format", "recipient,date,time,zone\nalex,2027-01-12,14:00,America/New_York\n\nUse a stable ID or exact name. Dates are explicit; times are HH:mm or h:mm AM/PM. No prose or Google Docs parsing.")
        OutlinedTextField(text, { text = it }, label = { Text("Schedule CSV") },
            modifier = Modifier.fillMaxWidth(), minLines = 7, maxLines = 12)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { picker.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel")) },
                enabled = !state.busy) { Text("Choose CSV") }
            Button(onClick = { model.parseSchedule(text) { text = "" } }, enabled = !state.busy && text.isNotBlank()) { Text("Review schedule") }
        }
        Text("Up to 40 appointments / 128 KiB per import. Unrelated columns are discarded. An invalid row blocks the entire preview. Nothing is approved yet.",
            style = MaterialTheme.typography.bodySmall)
        if (state.recipients.isEmpty()) Notice("Add recipients first", "Open People to add stable IDs, names and phone numbers. A schedule can only be approved after every row has an exact recipient.")
    } else ReviewScreen(model, state, approved)
}

@Composable
private fun ReviewScreen(model: ReminderViewModel, state: UiState, approved: () -> Unit) {
    val context = LocalContext.current
    var selecting by remember { mutableStateOf<Int?>(null) }
    var checked by remember(state.review, state.settings) { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val sms = remember(state.settings.subscriptionId) {
        if (Build.VERSION.SDK_INT >= 31) context.getSystemService(SmsManager::class.java)
            .createForSubscriptionId(state.settings.subscriptionId)
        else {
            @Suppress("DEPRECATION")
            SmsManager.getSmsManagerForSubscriptionId(state.settings.subscriptionId)
        }
    }
    val draftResult = try { model.drafts() to null } catch (error: IllegalArgumentException) {
        emptyList<ReminderDraft>() to error.message
    } catch (_: DateTimeException) {
        emptyList<ReminderDraft>() to "Appointment date, time or zone is invalid."
    }
    val drafts = draftResult.first
    val selectedSim = state.capabilities?.subscriptions?.singleOrNull { it.id == state.settings.subscriptionId }
    val approvalReady = state.capabilities?.ready == true && selectedSim != null
    var segmentError: String? = null
    val segments = drafts.associate { draft ->
        val count = sms.divideMessage(draft.message).size
        if (count > Limits.MAX_SEGMENTS) segmentError = "A message exceeds ${Limits.MAX_SEGMENTS} segments. Shorten the template and import again."
        draft.id to count
    }
    var unappliedEdits = false
    Section("Check every text", "These exact values will be frozen when approved. Changing recipient, template or appointment requires fresh approval.")
    state.review.forEach { row ->
        var editingTime by remember(row.source.line) { mutableStateOf(false) }
        var date by remember(row.source) { mutableStateOf(row.source.date) }
        var time by remember(row.source) { mutableStateOf(row.source.time) }
        var zone by remember(row.source) { mutableStateOf(row.source.zone) }
        if (date != row.source.date || time != row.source.time || zone != row.source.zone) unappliedEdits = true
        val person = state.recipients.singleOrNull { it.id == row.recipientId }
        val draft = drafts.singleOrNull { it.recipientId == person?.id && it.appointmentAt == Importer.appointment(row.source).toInstant().toEpochMilli() }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Row ${row.source.line} · ${row.source.date} ${row.source.time}", style = MaterialTheme.typography.titleMedium)
            Text("Source recipient: ${row.source.hint}", style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = { selecting = row.source.line }, enabled = !state.busy) {
                Text(if (person == null) "Resolve recipient" else "${person.name} · ${person.id}")
            }
            TextButton(onClick = { editingTime = !editingTime }, enabled = !state.busy) { Text("Change appointment") }
            if (editingTime) {
                OutlinedTextField(date, { date = it }, label = { Text("Date · yyyy-MM-dd") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(time, { time = it }, label = { Text("Time · HH:mm or h:mm AM/PM") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(zone, { zone = it }, label = { Text("Appointment time zone") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedButton(onClick = { model.updateAppointment(row.source.line, date, time, zone) }, enabled = !state.busy) { Text("Update preview") }
            }
            if (person != null) Text("To: ${person.phone}", style = MaterialTheme.typography.titleMedium)
            if (draft != null) {
                Text(draft.message, style = MaterialTheme.typography.bodyLarge)
                Text("Appointment: ${stamp(draft.appointmentAt, draft.zone)}\nSend: ${stamp(draft.sendAt, draft.zone)}\nZone: ${draft.zone}",
                    style = MaterialTheme.typography.bodyMedium)
                Text("${segments.getValue(draft.id)} SMS segment(s)", style = MaterialTheme.typography.labelLarge)
            }
        }
        HorizontalDivider()
    }
    if (draftResult.second != null) Notice("Preview needs attention", draftResult.second!!, true)
    if (unappliedEdits) Notice("Apply appointment edits first", "Tap Update preview for each changed appointment. Approval uses only the displayed validated preview.", true)
    if (segmentError != null) Notice("Message too long", segmentError!!, true)
    if (!approvalReady) Notice("Approval is blocked",
        (state.capabilities?.blockers.orEmpty() + if (selectedSim == null) listOf("Select and save an active SIM in Setup before approving.") else emptyList())
            .joinToString("\n").ifEmpty { "Phone capability checks have not completed." }, true)
    Text("SIM: ${selectedSim?.label ?: "Choose a SIM in Setup"}\nLead time: ${state.settings.leadMinutes} minutes",
        style = MaterialTheme.typography.bodyMedium)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, { checked = it })
        Text("I checked every number, message and time. I authorize automatic SMS through this SIM. Carrier charges may apply.",
            style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
    }
    Button(onClick = { confirm = true }, modifier = Modifier.fillMaxWidth(),
        enabled = !state.busy && checked && !unappliedEdits && drafts.isNotEmpty() && draftResult.second == null && segmentError == null && approvalReady) {
        Text("Approve ${state.review.size} reminders")
    }
    TextButton(onClick = model::clearReview, enabled = !state.busy) { Text("Discard preview") }
    if (confirm) Confirm("Approve automated sending?", "Approve ${state.review.size} frozen reminders. They may send unattended at the reviewed times, through the selected SIM. Submission is not a guarantee of delivery.",
        "Approve batch", { confirm = false }, { model.approve(approved) })
    selecting?.let { line ->
        AlertDialog(onDismissRequest = { selecting = null }, title = { Text("Choose the exact recipient") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    if (state.recipients.isEmpty()) Text("Add a recipient in People, then import again.")
                    state.recipients.forEach { person ->
                        TextButton(onClick = { model.selectRecipient(line, person.id); selecting = null },
                            modifier = Modifier.fillMaxWidth()) { Text("${person.name}\n${person.id} · ${person.phone}") }
                    }
                }
            }, confirmButton = { TextButton(onClick = { selecting = null }) { Text("Close") } })
    }
}

@Composable
private fun PeopleScreen(model: ReminderViewModel, state: UiState) {
    var editing by remember { mutableStateOf<Recipient?>(null) }
    var id by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var csv by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf<Recipient?>(null) }
    var saveConfirm by remember { mutableStateOf(false) }
    var importConfirm by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) model.readFile(uri) { csv = it }
    }
    Section(if (editing == null) "Add a recipient" else "Edit recipient",
        "Country for local numbers: ${state.settings.country}. Stable IDs avoid name ambiguity. Numbers are normalized to international format, not verified as SMS-capable.")
    OutlinedTextField(id, { id = it }, enabled = editing == null, label = { Text("Stable ID · e.g. alex") },
        singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(name, { name = it }, label = { Text("Full name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(phone, { phone = it }, label = { Text("Phone number") }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone), modifier = Modifier.fillMaxWidth())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Button(onClick = { saveConfirm = true }, enabled = !state.busy && id.isNotBlank() && name.isNotBlank() && phone.isNotBlank()) { Text("Save recipient") }
        if (editing != null) TextButton(onClick = { editing = null; id = ""; name = ""; phone = "" }) { Text("Stop editing") }
    }
    if (saveConfirm) Confirm("Save this recipient?", "Any pending reminders for a changed recipient will be cancelled and require reapproval. The number is not verified as belonging to this person.",
        "Save", { saveConfirm = false }, {
            model.saveRecipient(id, name, phone) { editing = null; id = ""; name = ""; phone = "" }
        })
    HorizontalDivider()
    Section("Import recipients", "CSV header: id,name,phone. Quoted commas are supported. Extra columns are discarded; at most 40 rows per import.")
    OutlinedTextField(csv, { csv = it }, label = { Text("Recipient CSV") }, minLines = 3, maxLines = 8, modifier = Modifier.fillMaxWidth())
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = { picker.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel")) }, enabled = !state.busy) { Text("Choose CSV") }
        Button(onClick = { importConfirm = true }, enabled = !state.busy && csv.isNotBlank()) { Text("Import people") }
    }
    if (importConfirm) Confirm("Import recipient list?",
        "The file is fully validated before saving. Matching IDs update existing people and cancel affected pending reminders. If a storage error interrupts saving, some rows may be saved; check the list before repeating.",
        "Import", { importConfirm = false }, { model.importRecipients(csv) { csv = "" } })
    HorizontalDivider()
    Section("Saved people", "${state.recipients.size} recipients stored only in the encrypted app database.")
    state.recipients.forEach { person ->
        ListItem(headlineContent = { Text(person.name) },
            supportingContent = { Text("${person.id} · ${person.phone}") },
            trailingContent = {
                Row {
                    IconButton(onClick = { editing = person; id = person.id; name = person.name; phone = person.phone }, enabled = !state.busy) {
                        Icon(Icons.Default.Edit, "Edit ${person.name}")
                    }
                    IconButton(onClick = { delete = person }, enabled = !state.busy) {
                        Icon(Icons.Default.DeleteOutline, "Delete ${person.name}")
                    }
                }
            })
        HorizontalDivider()
    }
    delete?.let { person ->
        Confirm("Delete ${person.name}?", "Delete the app-private recipient and cancel their pending reminders. Submitted messages and system SMS history are outside this deletion.",
            "Delete recipient", { delete = null }, { model.deleteRecipient(person.id) })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SetupScreen(model: ReminderViewModel, state: UiState) {
    val context = LocalContext.current
    var settings by remember(state.settings) { mutableStateOf(state.settings) }
    var country by remember(state.settings) { mutableStateOf(state.settings.country) }
    var lead by remember(state.settings) { mutableStateOf(state.settings.leadMinutes.toString()) }
    var promptTime by remember(state.settings) { mutableStateOf(state.settings.promptTime.toString()) }
    var oneOff by remember(state.settings) { mutableStateOf(state.settings.promptDates.sorted().joinToString("\n")) }
    var retention by remember(state.settings) { mutableStateOf(state.settings.retentionDays.toString()) }
    var saveConfirm by remember { mutableStateOf(false) }
    var deletion by remember { mutableStateOf<String?>(null) }
    var validation by remember { mutableStateOf<String?>(null) }
    var simPicker by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { model.refresh() }
    val savedSimAvailable = state.capabilities?.subscriptions?.any { it.id == state.settings.subscriptionId } == true
    Section("Phone feasibility", "Automatic sending needs all checks below. These are runtime checks, not proof that a carrier or phone manufacturer will deliver reliably.")
    state.diagnostic?.let { Notice("Background diagnostic", it, true) }
    Notice(if (state.capabilities?.ready == true && savedSimAvailable) "Ready for batch approval" else "Setup needs attention",
        (state.capabilities?.blockers.orEmpty() + if (!savedSimAvailable) listOf("Select and save an active SIM for new approvals.") else emptyList())
            .joinToString("\n").ifEmpty { "Required permissions and selected active SIM are available. Radio/carrier service still requires device testing." },
        state.capabilities?.ready != true || !savedSimAvailable)
    Notice("SMS permission is installer-restricted",
        "SEND_SMS is dangerous and hard-restricted. The installer must allowlist it before Android can grant it. Sideloading alone is not a guarantee. Do not assume an APK can send until the authorized install path is tested.")
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
    Text("No default SMS role is required when SEND_SMS is legitimately granted. No internet, contact access or SMS reading permissions. SMS is not RCS.",
        style = MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    Section("SIM & message", "The approved SIM ID is frozen per reminder. Changing the selection never silently reroutes an approved message.")
    OutlinedButton(onClick = { simPicker = true }, enabled = !state.busy) {
        Text(state.capabilities?.subscriptions?.singleOrNull { it.id == settings.subscriptionId }?.label ?: "Choose an active SIM")
    }
    if (simPicker) AlertDialog(onDismissRequest = { simPicker = false }, title = { Text("Select active SIM") }, text = {
        Column {
            if (state.capabilities?.subscriptions.isNullOrEmpty()) Text("Grant SIM access and insert/enable a SIM, then refresh. No automatic default selection.")
            state.capabilities?.subscriptions?.forEach { sim ->
                TextButton(onClick = { settings = settings.copy(subscriptionId = sim.id); simPicker = false }) {
                    Text("${sim.label} · ID ${sim.id}")
                }
            }
        }
    }, confirmButton = { TextButton(onClick = { simPicker = false }) { Text("Close") } })
    OutlinedTextField(country, { country = it.uppercase(Locale.ROOT) }, label = { Text("Phone country · two-letter code") },
        supportingText = { Text("US, GB, CA, AU, etc. Used for new local phone numbers.") }, singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(lead, { lead = it }, label = { Text("Lead time in minutes") },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
    OutlinedTextField(settings.template, { settings = settings.copy(template = it) }, label = { Text("Reminder template") },
        minLines = 3, modifier = Modifier.fillMaxWidth())
    Text("Tokens: {firstName}, {time}, {dayPeriod}. Time includes AM/PM. Appointments before 6 PM use “today”; from 6 PM use “tonight”, in the appointment zone. Send and appointment must be on the same date; reduce lead time for early appointments.",
        style = MaterialTheme.typography.bodySmall)
    HorizontalDivider()
    Section("Night-before input prompt", "Reminder-work weekdays below notify on the preceding evening, even when no appointments have been imported. Approved or explicitly no-reminder dates suppress the prompt.")
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
    HorizontalDivider()
    Section("Privacy & retention", "The entire Room database is encrypted with a Keystore-protected key. Background sending is available after first unlock; screen authentication protects only viewing, not each send.")
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
    if (validation != null) Notice("Check settings", validation!!, true)
    Button(onClick = { saveConfirm = true }, modifier = Modifier.fillMaxWidth(), enabled = !state.busy) { Text("Save setup") }
    if (saveConfirm) Confirm("Save these settings?", "Changes to template or lead time cancel pending approvals. They must be imported and reviewed again. SIM selection affects only new approvals.",
        "Save settings", { saveConfirm = false }, {
            try {
                val minutes = lead.toLongOrNull() ?: throw IllegalArgumentException("Lead time must be a number.")
                require(minutes in 1..1440) { "Lead time must be 1-1440 minutes." }
                val days = retention.toIntOrNull() ?: throw IllegalArgumentException("Retention must be a number.")
                require(days in 1..365) { "Retention must be 1-365 days." }
                require(country in com.google.i18n.phonenumbers.PhoneNumberUtil.getInstance().supportedRegions) { "Choose a valid two-letter country." }
                Templates.validate(settings.template)
                val time = LocalTime.parse(promptTime)
                require(time.second == 0 && time.nano == 0) { "Prompt time must use HH:mm." }
                val dates = oneOff.lines().filter { it.isNotBlank() }.map { LocalDate.parse(it.trim()) }.toSet()
                require(dates.size <= 366) { "Use at most 366 additional dates." }
                validation = null
                model.saveSettings(settings.copy(country = country, leadMinutes = minutes, promptTime = time,
                    promptDates = dates, retentionDays = days))
            } catch (error: IllegalArgumentException) {
                validation = error.message
            } catch (_: DateTimeException) {
                validation = "Use HH:mm for the evening time and yyyy-MM-dd for additional dates."
            }
        })
    HorizontalDivider()
    Section("Delete local data", "Cancellation may lose a race to dispatch. A submitted text cannot be recalled. Active submissions and unreviewed uncertain outcomes block full deletion; record manual review in Queue first.")
    OutlinedButton(onClick = { deletion = "history" }, enabled = !state.busy) { Text("Delete completed history") }
    OutlinedButton(onClick = { deletion = "all" }, enabled = !state.busy) { Text("Delete recipients, settings & reminders") }
    deletion?.let { what ->
        Confirm(if (what == "all") "Delete local app data?" else "Delete completed history?",
            "This cannot be undone. Pending reminders will be cancelled for full deletion. Submitted SMS and system SMS history remain. Duplicate-protection fingerprints remain to prevent accidental repeat sending.",
            "Delete local data", { deletion = null }, if (what == "all") model::deleteAll else model::deleteHistory)
    }
    HorizontalDivider()
    Section("Reliability limits", "Phone off, force-stop, before first unlock, restricted background activity, missing SIM/service or revoked permissions can prevent sending. Missed reminders expire after 15 minutes or at the appointment, whichever comes first. No blind retries.")
    Text("Review your manufacturer's battery/auto-start restrictions manually. This app does not request battery-exemption or run an always-on service. Delivery reports may be unavailable; a submitted message can arrive late.",
        style = MaterialTheme.typography.bodySmall)
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
