@file:OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)

package local.reminders.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import local.reminders.domain.*
import java.time.LocalDate
import java.time.LocalTime

@Composable
internal fun Details(label: String, content: @Composable ColumnScope.() -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { expanded = !expanded }) {
            Text(label, modifier = Modifier.weight(1f))
            Icon(if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
        }
        if (expanded) content()
    }
}

@Composable
private fun TableHeading(title: String, enabled: Boolean, read: (android.net.Uri, (String) -> Unit) -> Unit,
    load: (String) -> Boolean) {
    var menu by remember { mutableStateOf(false) }
    var text by remember { mutableStateOf("") }
    val focus = LocalFocusManager.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) read(uri) { load(it) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        OutlinedButton(onClick = { focus.clearFocus(); menu = !menu }, enabled = enabled) {
            Icon(Icons.Default.FileUpload, null)
            Spacer(Modifier.width(8.dp))
            Text("Import")
        }
    }
    if (menu) Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = {
            menu = false
            picker.launch(arrayOf("text/*", "application/csv", "application/vnd.ms-excel"))
        }, enabled = enabled) { Text("Choose CSV file") }
        Text("Or paste CSV")
        OutlinedTextField(text, { text = it }, label = { Text("$title CSV") }, enabled = enabled,
            minLines = 5, maxLines = 10, modifier = Modifier.fillMaxWidth())
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { if (load(text)) { menu = false; text = "" } },
                enabled = enabled && text.isNotBlank()) { Text("Load rows") }
            TextButton(onClick = { menu = false }) { Text("Cancel import") }
        }
    }
}

@Composable
private fun Cell(value: String, label: String, enabled: Boolean, change: (String) -> Unit,
    modifier: Modifier = Modifier, error: String? = null, keyboard: KeyboardType = KeyboardType.Text,
    trailing: (@Composable () -> Unit)? = null) {
    OutlinedTextField(value, change, label = { Text(label) }, enabled = enabled,
        singleLine = true, isError = error != null,
        supportingText = error?.let { { Text(it) } }, keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        trailingIcon = trailing, modifier = modifier)
}

@Composable
private fun RowHeading(index: Int, kind: String, enabled: Boolean, remove: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("$kind $index", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
        IconButton(onClick = remove, enabled = enabled, modifier = Modifier.size(48.dp)) {
            Icon(Icons.Default.DeleteOutline, "Remove $kind $index")
        }
    }
}

@Composable
internal fun PeopleScreen(model: ReminderViewModel, state: UiState, openSchedule: () -> Unit = {}, showEditor: () -> Unit = {}) {
    var save by remember { mutableStateOf(false) }
    var delete by remember { mutableStateOf<Recipient?>(null) }
    TableHeading("People", !state.busy, model::readFile, model::loadPeopleTable)
    Text("Add names and numbers. Local numbers use ${state.settings.country}.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    state.peopleInput.forEachIndexed { index, row -> key(row.key) {
        fun error(field: String) = state.peopleProblems.firstOrNull { it.key == row.key && it.field == field }?.detail
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RowHeading(index + 1, "Person", !state.busy) { model.removePerson(row.key) }
            if (row.legacyName != null) Cell(row.legacyName, "Full name · person ${index + 1}", !state.busy,
                { model.updatePerson(row.copy(legacyName = it)) }, Modifier.fillMaxWidth(), error("name"))
            BoxWithConstraints {
                val wide = maxWidth >= 540.dp
                val stacked = maxWidth < 320.dp || LocalDensity.current.fontScale > 1.3f
                FlowRow(maxItemsInEachRow = if (stacked) 1 else if (wide) 3 else 2,
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Cell(row.firstName, "First name · ${index + 1}", !state.busy,
                        { model.updatePerson(row.copy(firstName = it)) }, Modifier.weight(1f), error("firstName") ?: error("name"))
                    Cell(row.lastName, "Last name · ${index + 1}", !state.busy,
                        { model.updatePerson(row.copy(lastName = it)) }, Modifier.weight(1f), error("lastName"))
                    Cell(row.phone, "Phone · ${index + 1}", !state.busy,
                        { model.updatePerson(row.copy(phone = it)) }, Modifier.weight(1.2f), error("phone"), KeyboardType.Phone)
                }
            }
            HorizontalDivider()
        }
    } }
    state.peopleProblems.filter { it.key.isEmpty() }.forEach { Text(it.detail, color = MaterialTheme.colorScheme.error) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = model::addPerson, enabled = !state.busy && state.peopleInput.size < Limits.MAX_BATCH) { Text("Add person") }
        Button(onClick = { if (model.validatePeopleTable()) save = true }, enabled = !state.busy) { Text("Save people") }
    }
    if (save) Confirm("Save people?", "Changed people lose pending approval. Check every number; it is not verified as belonging to this person.",
        "Save people", { save = false }, model::savePeopleTable)
    if (state.recipients.isNotEmpty()) {
        TextButton(onClick = openSchedule, enabled = !state.busy) { Text("Continue to schedule") }
    }
    if (state.incomingPeople != null) Confirm("Replace unsaved rows?", "The imported rows will replace your unsaved People table. Saved people are unchanged until you save.",
        "Replace rows", { model.resolveTableImport(true, false) }, { model.resolveTableImport(true, true) })
    Details("CSV formats & identity") {
        Text("FirstName,LastName,phone\nAlex,Example,2025550101\n\nLegacy id,name,phone also works. Import loads editable rows, not saved people. Exact name + number reuses a saved identity. To change a saved number, use Edit below. Up to 40 rows.")
    }
    Section("Saved people", "${state.recipients.size} saved")
    state.recipients.forEach { person ->
        ListItem(headlineContent = { Text(person.name) }, supportingContent = { Text(person.phone) },
            trailingContent = {
                Row {
                    IconButton(onClick = { model.editPerson(person); showEditor() }, enabled = !state.busy) { Icon(Icons.Default.Edit, "Edit ${person.name}") }
                    IconButton(onClick = { delete = person }, enabled = !state.busy) { Icon(Icons.Default.DeleteOutline, "Delete ${person.name}") }
                }
            })
    }
    delete?.let { person -> Confirm("Delete ${person.name}?",
        "Their pending reminders will be cancelled. Submitted messages and system history are not deleted.",
        "Delete person", { delete = null }, { model.deleteRecipient(person.id) }) }
}

@Composable
internal fun ScheduleTable(model: ReminderViewModel, state: UiState) {
    val context = LocalContext.current
    var selecting by remember { mutableStateOf<Pair<String, Int>?>(null) }
    var search by remember { mutableStateOf("") }
    TableHeading("Schedule", !state.busy, model::readFile, model::loadAppointmentTable)
    Text("Add appointments, then review each message. Nothing sends yet.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    state.appointmentInput.forEachIndexed { index, row -> key(row.key) {
        fun error(field: String) = state.appointmentProblems.firstOrNull { it.key == row.key && it.field == field }?.detail
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RowHeading(index + 1, "Appointment", !state.busy) { model.removeAppointment(row.key) }
            FlowRow(maxItemsInEachRow = if (LocalDensity.current.fontScale > 1.3f) 1 else 2,
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Cell(row.date, "Date · ${index + 1}", !state.busy, { model.updateAppointmentInput(row.copy(date = it)) },
                    Modifier.weight(1f), error("date"), trailing = {
                        IconButton(enabled = !state.busy, onClick = {
                            val date = row.date.takeIf { it.matches(Regex("\\d{4}-\\d{2}-\\d{2}")) }
                                ?.let { runCatching { LocalDate.parse(it) }.getOrNull() } ?: LocalDate.now()
                            DatePickerDialog(context, { _, y, m, d ->
                                model.updateAppointmentInput(row.copy(date = LocalDate.of(y, m + 1, d).toString()))
                            }, date.year, date.monthValue - 1, date.dayOfMonth).show()
                        }) { Icon(Icons.Default.DateRange, "Choose date for appointment ${index + 1}") }
                    })
                Cell(row.time, "Time · ${index + 1}", !state.busy, { model.updateAppointmentInput(row.copy(time = it)) },
                    Modifier.weight(1f), error("time"), trailing = {
                        IconButton(enabled = !state.busy, onClick = {
                            val time = runCatching { Importer.appointment(row.source(index + 1)).toLocalTime() }.getOrNull() ?: LocalTime.of(14, 0)
                            TimePickerDialog(context, { _, h, m ->
                                model.updateAppointmentInput(row.copy(time = "%02d:%02d".format(h, m)))
                            }, time.hour, time.minute, false).show()
                        }) { Icon(Icons.Default.Schedule, "Choose time for appointment ${index + 1}") }
                    })
            }
            row.names.forEachIndexed { member, name ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Cell(name, "Name ${member + 1} · appointment ${index + 1}", !state.busy,
                        { value -> model.updateAppointmentInput(row.copy(names = row.names.mapIndexed { i, old -> if (i == member) value else old })) },
                        Modifier.weight(1f), error("name$member"), trailing = {
                            IconButton(enabled = !state.busy, onClick = { search = ""; selecting = row.key to member }) {
                                Icon(Icons.Default.PersonSearch, "Choose person ${member + 1} for appointment ${index + 1}")
                            }
                        })
                    if (member > 0) IconButton(onClick = {
                        model.updateAppointmentInput(row.copy(names = row.names.filterIndexed { i, _ -> i != member }))
                    }, enabled = !state.busy) { Icon(Icons.Default.RemoveCircleOutline, "Remove group member ${member + 1} from appointment ${index + 1}") }
                }
            }
            TextButton(onClick = { model.updateAppointmentInput(row.copy(names = row.names + "")) },
                enabled = !state.busy && row.names.size < Limits.MAX_GROUP) { Text("Add group member") }
            Text("Zone: ${row.zone}", style = MaterialTheme.typography.labelMedium)
            Details("Change time zone") {
                Cell(row.zone, "Time zone · ${index + 1}", !state.busy,
                    { model.updateAppointmentInput(row.copy(zone = it)) }, Modifier.fillMaxWidth(), error("zone"))
            }
            if (error("zone") != null) Text(error("zone")!!, color = MaterialTheme.colorScheme.error)
            HorizontalDivider()
        }
    } }
    state.appointmentProblems.filter { it.key.isEmpty() }.forEach { Text(it.detail, color = MaterialTheme.colorScheme.error) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedButton(onClick = model::addAppointment, enabled = !state.busy && state.appointmentInput.size < Limits.MAX_BATCH) { Text("Add appointment") }
        Button(onClick = model::reviewTable, enabled = !state.busy) { Text("Review messages") }
    }
    if (state.incomingAppointments != null) Confirm("Replace unsaved rows?", "Imported appointments will replace your unsaved Schedule table. No reminders are approved.",
        "Replace rows", { model.resolveTableImport(false, false) }, { model.resolveTableImport(false, true) })
    Details("CSV formats & matching") {
        Text("Date,Time,Name,Name2\n2027-01-12,14:00,Alex Example,Sam Example\n\nName2 through Name20 are optional. Exact saved names only; ambiguous names are resolved in review. Legacy recipient,date,time,zone is supported. Import captures the phone zone once; later zone changes do not reinterpret these rows. Groups use one shared MMS, not separate texts.")
    }
    selecting?.let { (key, member) ->
        AlertDialog(onDismissRequest = { selecting = null }, title = { Text("Choose person") },
            text = {
                Column {
                    OutlinedTextField(search, { search = it }, label = { Text("Find person") }, singleLine = true)
                    androidx.compose.foundation.lazy.LazyColumn(Modifier.heightIn(max = 320.dp)) {
                        val matches = state.recipients.filter { it.name.contains(search, true) || it.phone.contains(search) }
                        items(matches.size) { i ->
                            val person = matches[i]
                            TextButton(onClick = {
                                state.appointmentInput.singleOrNull { it.key == key }?.let { row ->
                                    model.updateAppointmentInput(row.copy(names = row.names.mapIndexed { n, old ->
                                        if (n == member) person.name else old
                                    }, nameOnly = true))
                                }
                                selecting = null
                            }, modifier = Modifier.fillMaxWidth()) { Text("${person.name}\n${person.phone}") }
                        }
                        if (matches.isEmpty()) item { Text("No people found. Add them in People.") }
                    }
                }
            }, confirmButton = { TextButton(onClick = { selecting = null }) { Text("Close") } })
    }
}
