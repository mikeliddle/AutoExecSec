package local.reminders.runtime

import android.app.Notification
import android.app.NotificationManager
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import local.reminders.ReminderApplication
import local.reminders.domain.*
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.*
import java.util.UUID

/** No SMS calls, permission changes, device settings, or deletion of existing app data. */
@RunWith(AndroidJUnit4::class)
class DeviceRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun unavailableSimRejectsApprovalWithoutCreatingAnAttempt() = runBlocking {
        val runtime = (context.applicationContext as ReminderApplication).runtime
        val capabilities = runtime.capabilities()
        assertFalse("This opt-in test requires a phone with missing capabilities.", capabilities.ready)
        assertTrue("This opt-in test requires no active SIM.", capabilities.subscriptions.isEmpty())
        val before = runtime.store.reminders()
        val row = ScheduleRow(2, "synthetic_guard", LocalDate.now().plusDays(7).toString(), "14:00", "UTC")
        val draft = Scheduling.draft(row, Recipient("synthetic_guard", "Synthetic Guard", "+12025550101"),
            Limits.DEFAULT_TEMPLATE, 120, Instant.now())
        val error = assertThrows(IllegalArgumentException::class.java) {
            runBlocking { runtime.approve(listOf(draft), -1) }
        }
        assertTrue(capabilities.blockers.any { error.message!!.contains(it) })
        assertFalse(runtime.capabilities().ready)
        assertTrue(runtime.capabilities().subscriptions.isEmpty())
        assertNotNull(Platform(context).selectedBlocker(-1, runtime.capabilities()))
        assertEquals(before, runtime.store.reminders())
    }

    @Test fun keystoreKeyReopensEncryptedStateAndTransactionsStayLocal() {
        val name = "synthetic-transactions-${UUID.randomUUID()}.db"
        val firstKey = DatabaseKey.load(context)
        val secondKey = DatabaseKey.load(context)
        assertArrayEquals(firstKey, secondKey)
        System.loadLibrary("sqlcipher")
        fun open(key: ByteArray) = Room.databaseBuilder(context, RuntimeDatabase::class.java, name)
            .openHelperFactory(SupportOpenHelperFactory(key.copyOf())).build()
        var db: RuntimeDatabase? = null
        try {
            db = open(firstKey)
            val now = System.currentTimeMillis()
            val row = ReminderEntity("synthetic_pending", "synthetic_person", "SYNTHETIC_DEVICE_STATE",
                "+12025550101", "Synthetic offline preview only", now + 86_400_000, now + 79_200_000,
                "UTC", subscriptionId = -1, parts = 1, updatedAt = now)
            val tx = Transactions(db)
            tx.approve(listOf(row), now)
            assertThrows(IllegalArgumentException::class.java) { tx.approve(listOf(row), now) }
            assertTrue(tx.cancel(row.id, now))
            tx.approve(listOf(row.copy(message = "Edited synthetic preview")), now)
            tx.cancelAffected({ it.recipientId == row.recipientId }, now)
            assertEquals("CANCELLED", db.dao().reminder(row.id)!!.state)
            tx.approve(listOf(row.copy(message = "Reapproved synthetic preview")), now)
            assertNull(tx.claim(row.id, "not-due", "test", now))
            assertTrue(db.dao().rateAttempts().isEmpty())
            db.close()
            db = null
            db = open(secondKey)
            assertEquals("Reapproved synthetic preview", db.dao().reminder(row.id)!!.message)
            assertEquals("PENDING", db.dao().reminder(row.id)!!.state)
            assertFalse(context.getDatabasePath(name).readBytes().toString(Charsets.ISO_8859_1)
                .contains("SYNTHETIC_DEVICE_STATE"))
        } finally {
            db?.close()
            context.deleteDatabase(name)
            firstKey.fill(0)
            secondKey.fill(0)
        }
    }

    @Test fun grantedPrivatePromptNotificationAndIndependentNightBeforeLogic() {
        val platform = Platform(context).also { it.channels() }
        assertTrue("Notification access must already be enabled; this test never grants it.",
            platform.notificationAllowed(Platform.INPUT_CHANNEL))
        val tomorrow = LocalDate.now().plusDays(1)
        val beforeEvening = tomorrow.minusDays(1).atTime(19, 0).atZone(ZoneId.systemDefault())
        val settings = PromptSettings(emptySet(), LocalTime.of(20, 0), setOf(tomorrow))
        assertEquals(tomorrow.minusDays(1).atTime(20, 0).atZone(beforeEvening.zone),
            Scheduling.nextPrompt(beforeEvening, settings, emptySet()))
        assertNull(Scheduling.nextPrompt(beforeEvening, settings, setOf(tomorrow)))
        platform.notifyPrompt()
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        val posted = manager.activeNotifications.single { it.id == 11 }.notification
        assertEquals(Platform.INPUT_CHANNEL, posted.channelId)
        assertEquals(Notification.VISIBILITY_PRIVATE, posted.visibility)
        assertEquals("Tomorrow's reminders", posted.extras.getString(Notification.EXTRA_TITLE))
        assertEquals("Import and approve tomorrow's schedule, or mark no reminders.",
            posted.extras.getString(Notification.EXTRA_TEXT))
        assertNotNull(posted.contentIntent)
        // Keep the generic prompt visible for the user to inspect; no reminder was approved.
    }
}
