package local.reminders.runtime

import local.reminders.domain.TextTemplate
import local.reminders.domain.TextTemplates
import org.json.JSONArray
import org.json.JSONObject

internal object TextTemplateStorage {
    const val KEY = "copy-templates"
    fun encode(templates: List<TextTemplate>): String {
        require(templates.size <= TextTemplates.MAX_TEMPLATES) { "Save at most 50 templates." }
        require(templates.map { it.id }.distinct().size == templates.size) { "Duplicate template identity." }
        templates.forEach(TextTemplates::validate)
        return JSONArray().apply {
            templates.forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("body", it.body)) }
        }.toString()
    }
    fun decode(raw: String?): List<TextTemplate> {
        if (raw == null) return emptyList()
        val array = JSONArray(raw)
        val templates = (0 until array.length()).map {
            val entry = array.getJSONObject(it)
            TextTemplate(entry.getString("id"), entry.getString("name"), entry.getString("body"))
        }
        encode(templates)
        return templates
    }
}
