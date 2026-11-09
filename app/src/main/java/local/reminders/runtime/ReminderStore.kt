package local.reminders.runtime

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import local.reminders.domain.*
import java.time.LocalDate
import java.util.concurrent.Callable

class ReminderStore internal constructor(private val database: () -> RuntimeDatabase) {
    suspend fun recipients(): List<Recipient> = withContext(Dispatchers.IO) {
        database().dao().recipients().map { it.model() }
    }
    suspend fun reminders(): List<ReminderRecord> = withContext(Dispatchers.IO) {
        val db = database()
        db.runInTransaction(Callable {
            val dao = db.dao()
            val reviewed = dao.reviewedReminders().map { it.key.removePrefix("reviewed:") }.toSet()
            dao.reminders().map { it.model(it.state == "UNCERTAIN" && it.id in reviewed) }
        })
    }
    suspend fun settings(): AppSettings = withContext(Dispatchers.IO) {
        SettingsCodec.decode(database().dao().value("settings"))
    }
    suspend fun coveredDates(): Set<LocalDate> = withContext(Dispatchers.IO) {
        database().dao().coverage().map { LocalDate.parse(it.date) }.toSet()
    }
    suspend fun diagnostic(): String? = withContext(Dispatchers.IO) {
        database().dao().value("background_diagnostic") ?: database().dao().value("diagnostic")
    }
}
