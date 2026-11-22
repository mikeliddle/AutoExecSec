package local.reminders.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.PersistableBundle
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import local.reminders.domain.TextTemplate
import local.reminders.domain.TextTemplates
import java.util.UUID

@Composable
internal fun TextTemplatesScreen(
    templates: List<TextTemplate>,
    busy: Boolean,
    save: (TextTemplate) -> Unit,
    delete: (String) -> Unit
) {
    val context = LocalContext.current
    var selected by remember { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<TextTemplate?>(null) }
    var pending by remember { mutableStateOf<TextTemplate?>(null) }
    var deleting by remember { mutableStateOf<TextTemplate?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    val chosen = templates.singleOrNull { it.id == selected }

    Text("Write once, fill in the fields, copy anywhere.",
        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (editor == null) {
        Button(onClick = {
            editor = TextTemplate(UUID.randomUUID().toString(), "", "")
            status = null
        }, enabled = !busy && templates.size < TextTemplates.MAX_TEMPLATES) { Text("Create template") }
        if (templates.isEmpty()) {
            Section("Your first reusable template", "Try: Hello {name}, your meeting is at {time}.")
            Text("Each different variable creates one field. Repeated variables reuse the same value.")
        } else {
            Section("Saved templates")
            templates.sortedBy { it.name.lowercase() }.forEach { template ->
                OutlinedButton(onClick = { selected = template.id; status = null },
                    enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                    Text(template.name, modifier = Modifier.weight(1f))
                    if (selected == template.id) Text("Selected", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        chosen?.let { template ->
            HorizontalDivider()
            Section(template.name)
            val names = remember(template.body) { TextTemplates.variables(template.body) }
            var values by remember(template.id, template.body) { mutableStateOf(emptyMap<String, String>()) }
            val missing = names.filter { values[it].isNullOrBlank() }
            val output = try {
                TextTemplates.render(template.body, names.associateWith { values[it].takeUnless { value -> value.isNullOrBlank() } ?: "{$it}" })
            } catch (error: IllegalArgumentException) {
                null
            }
            names.forEachIndexed { index, name ->
                OutlinedTextField(values[name].orEmpty(), {
                    values = values + (name to it)
                    status = null
                }, label = { Text(name) }, enabled = !busy, minLines = 1, maxLines = 4,
                    keyboardOptions = KeyboardOptions(imeAction = if (index == names.lastIndex) ImeAction.Done else ImeAction.Next),
                    modifier = Modifier.fillMaxWidth())
            }
            Section("Preview", if (missing.isEmpty()) null else "Fill in ${missing.joinToString(", ")} to copy.")
            if (output != null) Text(output, style = MaterialTheme.typography.bodyLarge)
            else Text("Generated text exceeds 64,000 characters. Shorten the field values.",
                color = MaterialTheme.colorScheme.error)
            Button(onClick = {
                val exact = TextTemplates.render(template.body, values)
                val clip = ClipData.newPlainText("Auto ExecSec template", exact)
                clip.description.extras =
                    PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
                try {
                    val clipboard = context.getSystemService(ClipboardManager::class.java)
                    if (clipboard == null) status = "Copy failed: clipboard is unavailable."
                    else {
                        clipboard.setPrimaryClip(clip)
                        status = "Copied. Paste into your chosen app."
                    }
                } catch (error: SecurityException) {
                    status = "Copy was blocked: ${error.message ?: "clipboard access denied"}"
                }
            }, enabled = !busy && missing.isEmpty() && output != null, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.ContentCopy, null)
                Spacer(Modifier.width(8.dp))
                Text("Copy output")
            }
            Text("Field values are not saved. Other apps can retain copied text.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                TextButton(onClick = { editor = template; status = null }, enabled = !busy) { Text("Edit template") }
                TextButton(onClick = { deleting = template }, enabled = !busy) { Text("Delete template") }
            }
        }
    } else editor?.let { draft ->
        val original = templates.singleOrNull { it.id == draft.id }
        Section(if (original == null) "Create template" else "Edit template")
        OutlinedTextField(draft.name, { editor = draft.copy(name = it); status = null },
            label = { Text("Template name") }, singleLine = true, enabled = !busy, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(draft.body, { editor = draft.copy(body = it); status = null },
            label = { Text("Template text") }, supportingText = { Text("Use {name}. Labels are case-sensitive; repeat them anywhere.") },
            minLines = 5, maxLines = 12, enabled = !busy, modifier = Modifier.fillMaxWidth())
        val validation = try { TextTemplates.validate(draft); null }
        catch (error: IllegalArgumentException) { error.message }
        if (validation != null) Text(validation, color = MaterialTheme.colorScheme.error)
        else Text("Fields: ${TextTemplates.variables(draft.body).joinToString().ifEmpty { "None - copy the text as written" }}",
            style = MaterialTheme.typography.bodyMedium)
        Button(onClick = { save(draft); selected = draft.id }, enabled = !busy && validation == null) { Text("Save template") }
        val saved = original?.name == draft.name.trim() && original.body == draft.body
        if (saved) Text("Saved locally.", style = MaterialTheme.typography.labelLarge)
        TextButton(onClick = {
            if (saved || draft == original || (original == null && draft.name.isEmpty() && draft.body.isEmpty())) editor = null
            else pending = draft
        }, enabled = !busy) { Text("Back to templates") }
    }
    status?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    pending?.let {
        Confirm("Discard template edits?", "Saved templates are unchanged. Your unsaved edits will be lost.",
            "Discard edits", { pending = null }, { editor = null })
    }
    deleting?.let { template ->
        Confirm("Delete ${template.name}?", "The saved definition will be removed. Text already copied elsewhere is not deleted.",
            "Delete template", { deleting = null }, { delete(template.id) })
    }
}
