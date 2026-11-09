package local.reminders.runtime

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.telephony.SmsMessage
import kotlinx.coroutines.runBlocking
import local.reminders.ReminderApplication
import java.util.concurrent.*
import java.util.concurrent.atomic.AtomicBoolean

internal object ReceiverWork {
    private val executor = ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
        ArrayBlockingQueue(512), { task -> Thread(task, "reminder-receiver") },
        ThreadPoolExecutor.AbortPolicy())
    private val timeouts = Executors.newSingleThreadScheduledExecutor { task ->
        Thread(task, "reminder-receiver-deadline")
    }

    fun run(receiver: BroadcastReceiver, context: Context, block: suspend (ReminderRuntime, Long) -> Unit) {
        val runtime = (context.applicationContext as ReminderApplication).runtime
        if (!runtime.isUnlocked()) return
        val pending = receiver.goAsync()
        val finished = AtomicBoolean(false)
        val deadline = SystemClock.elapsedRealtime() + 7_500
        val work = FutureTask {
            try {
                runBlocking {
                    if (SystemClock.elapsedRealtime() < deadline - 500) block(runtime, deadline)
                    else runtime.reportReceiverFailure()
                }
            } catch (_: Exception) {
                // Never log exceptions from providers: some include recipients or message content.
                if (!Thread.currentThread().isInterrupted) runBlocking { runtime.reportReceiverFailure() }
            } finally {
                if (finished.compareAndSet(false, true)) pending.finish()
            }
        }
        val timeout = timeouts.schedule({
            runtime.noteReceiverFailure()
            work.cancel(true)
            if (finished.compareAndSet(false, true)) pending.finish()
        }, 7_500, TimeUnit.MILLISECONDS)
        try {
            executor.execute {
                try { work.run() } finally { timeout.cancel(false) }
            }
        } catch (_: RejectedExecutionException) {
            runtime.noteReceiverFailure()
            work.cancel(false)
            timeout.cancel(false)
            if (finished.compareAndSet(false, true)) pending.finish()
            // Existing attempts still have persisted deadlines, and callbacks never authorize a resend.
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Platform.DISPATCH, Platform.PROMPT, Platform.TIMEOUT)) return
        val action = intent.action
        val target = intent.getStringExtra("target_date")
        ReceiverWork.run(this, context) { runtime, deadline -> runtime.receiveAlarm(action, target, deadline) }
    }
}

class SmsResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Platform.SENT && intent.action != Platform.DELIVERY) return
        val id = intent.getStringExtra("id") ?: return
        val attempt = intent.getStringExtra("attempt") ?: return
        val part = intent.getIntExtra("part", -1)
        val code = resultCode
        val delivery = intent.action == Platform.DELIVERY
        val status = if (delivery) DeliveryReports.status(intent) else null
        ReceiverWork.run(this, context) { runtime, _ -> runtime.receiveResult(id, attempt, part, delivery, code, status) }
    }
}

internal object DeliveryReports {
    // TP-Status 0 means received by the destination SME; SmsMessage exposes no public constant.
    const val COMPLETE = 0

    fun status(intent: Intent): Int? = try {
        val pdu = intent.getByteArrayExtra("pdu")
        val format = intent.getStringExtra("format")
        if (pdu == null || pdu.size !in 1..4096 || format !in setOf("3gpp", "3gpp2")) null
        else {
            val message = SmsMessage.createFromPdu(pdu, format)
            if (message?.isStatusReportMessage == true) message.status.takeIf { it in 0..255 } else null
        }
    } catch (_: RuntimeException) {
        null
    }
}

class RecoveryReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_USER_UNLOCKED,
                Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED,
                "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED")) return
        ReceiverWork.run(this, context) { runtime, _ -> runtime.reconcile() }
    }
}
