package local.reminders

import android.app.Application
import local.reminders.runtime.ReminderRuntime

class ReminderApplication : Application() {
    val runtime: ReminderRuntime by lazy { ReminderRuntime(this) }
}
