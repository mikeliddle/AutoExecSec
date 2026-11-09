package local.reminders.runtime

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.telephony.*
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

internal class Platform(private val context: Context) {
    private val alarms get() = requireNotNull(context.getSystemService(AlarmManager::class.java))
    private val notifications get() = requireNotNull(context.getSystemService(NotificationManager::class.java))

    fun channels() {
        notifications.createNotificationChannels(listOf(
            NotificationChannel(INPUT_CHANNEL, "Schedule input", NotificationManager.IMPORTANCE_DEFAULT),
            NotificationChannel(STATUS_CHANNEL, "Reminder dispatch status", NotificationManager.IMPORTANCE_DEFAULT)
        ).onEach { it.lockscreenVisibility = Notification.VISIBILITY_PRIVATE })
    }

    fun permission(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    fun exactAllowed() = Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()
    fun unlocked() = requireNotNull(context.getSystemService(UserManager::class.java)).isUserUnlocked
    fun notificationAllowed(channel: String): Boolean =
        (Build.VERSION.SDK_INT < 33 || permission(Manifest.permission.POST_NOTIFICATIONS)) &&
            NotificationManagerCompat.from(context).areNotificationsEnabled() &&
            notifications.getNotificationChannel(channel)?.importance?.let { it != NotificationManager.IMPORTANCE_NONE } == true

    fun capabilities(): Capabilities {
        val blockers = mutableListOf<String>()
        if (!unlocked()) blockers += "Unlock the device before using reminders."
        val feature = if (Build.VERSION.SDK_INT >= 33) PackageManager.FEATURE_TELEPHONY_MESSAGING else PackageManager.FEATURE_TELEPHONY
        if (!context.packageManager.hasSystemFeature(feature)) blockers += "This device does not support SMS."
        if (!permission(Manifest.permission.SEND_SMS))
            blockers += "Allow SMS permission. Android may require the installer to allowlist this restricted permission."
        val hasPhone = permission(Manifest.permission.READ_PHONE_STATE)
        if (!hasPhone) blockers += "Allow phone-state permission to verify the selected SIM (phone numbers are not requested)."
        var sims = emptyList<SimChoice>()
        if (hasPhone) {
            try {
                sims = requireNotNull(context.getSystemService(SubscriptionManager::class.java)).activeSubscriptionInfoList.orEmpty()
                    .filter { it.subscriptionId >= 0 }
                    .map { SimChoice(it.subscriptionId, "SIM ${it.simSlotIndex + 1} · ${it.displayName}") }
            } catch (_: SecurityException) {
                blockers += "Phone-state access is unavailable."
            } catch (_: RuntimeException) {
                blockers += "Active SIMs could not be verified."
            }
            if (sims.isEmpty()) blockers += "No active SMS subscription is available."
        }
        if (!exactAllowed()) blockers += "Allow exact alarms for approved reminder times."
        if (!notificationAllowed(INPUT_CHANNEL)) blockers += "Allow schedule-input notifications."
        if (!notificationAllowed(STATUS_CHANNEL)) blockers += "Allow dispatch-status notifications."
        return Capabilities(blockers.distinct(), sims)
    }

    fun selectedBlocker(id: Int, caps: Capabilities): String? {
        if (caps.subscriptions.none { it.id == id }) return "The approved SIM is no longer active. No other SIM will be used."
        val airplane = try {
            Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        } catch (_: SecurityException) {
            null
        }
        // Service-state access needs location on some Android versions. Permitted capabilities
        // cannot predict radio/carrier availability; authoritative SMS callbacks determine outcomes.
        return if (airplane == true) "Airplane mode is enabled. Waiting only within the safe sending window." else null
    }

    @Suppress("DEPRECATION")
    fun sms(id: Int): SmsManager = if (Build.VERSION.SDK_INT >= 31)
        requireNotNull(context.getSystemService(SmsManager::class.java)).createForSubscriptionId(id)
    else SmsManager.getSmsManagerForSubscriptionId(id)

    private fun alarmIntent(kind: String, target: String? = null): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java).setAction(kind)
        if (target != null) intent.putExtra("target_date", target)
        val request = when (kind) { DISPATCH -> 1; PROMPT -> 2; else -> 3 }
        return PendingIntent.getBroadcast(context, request, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun cancelWake() = alarms.cancel(alarmIntent(DISPATCH))
    fun cancelTimeout() = alarms.cancel(alarmIntent(TIMEOUT))
    fun cancelPrompt() = alarms.cancel(alarmIntent(PROMPT))
    fun wake(at: Long, exact: Boolean) {
        cancelWake()
        if (exact) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(DISPATCH))
        else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(DISPATCH))
    }

    fun prompt(at: Long, target: String) {
        cancelPrompt()
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(PROMPT, target))
    }

    fun timeout(at: Long) {
        cancelTimeout()
        alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, alarmIntent(TIMEOUT))
    }

    fun callback(id: String, attempt: String, part: Int, delivery: Boolean): PendingIntent {
        val intent = Intent(context, SmsResultReceiver::class.java)
            .setAction(if (delivery) DELIVERY else SENT)
            .setData(Uri.Builder().scheme("reminder").authority("callback")
                .appendPath(attempt).appendPath(part.toString()).appendPath(if (delivery) "delivery" else "sent").build())
            .putExtra("id", id).putExtra("attempt", attempt).putExtra("part", part)
        val mutability = if (!delivery) PendingIntent.FLAG_IMMUTABLE
            else if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or mutability)
    }

    fun notifyStatus(text: String) = notify(STATUS_CHANNEL, 10, "Reminder status", text, false)
    fun notifyPrompt() = notify(INPUT_CHANNEL, 11, "Tomorrow's reminders",
        "Import and approve tomorrow's schedule, or mark no reminders.", true)
    fun clearPrompt() = notifications.cancel(11)
    private fun notify(channel: String, id: Int, title: String, text: String, openImport: Boolean) {
        if (!notificationAllowed(channel)) return
        val intent = Intent().setClassName(context, "local.reminders.MainActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("open_import", openImport)
        val open = PendingIntent.getActivity(context, id, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title).setContentText(text)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(NotificationCompat.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("Appointment reminders")
                .setContentText("Open the app for status.").build())
            .setContentIntent(open).setAutoCancel(true).build()
        try { notifications.notify(id, notification) } catch (_: SecurityException) { /* Permission can change between check and post. */ }
    }

    companion object {
        const val INPUT_CHANNEL = "schedule-input"
        const val STATUS_CHANNEL = "dispatch-status"
        const val DISPATCH = "local.reminders.DISPATCH"
        const val TIMEOUT = "local.reminders.TIMEOUT"
        const val PROMPT = "local.reminders.PROMPT"
        const val SENT = "local.reminders.SENT"
        const val DELIVERY = "local.reminders.DELIVERY"
    }
}
