package local.reminders.runtime

import android.Manifest
import android.app.Application
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowAlarmManager
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
class PlatformTest {
    @Test fun renamedDebugPackageKeepsComponentClassesAndIsolatesMmsAuthority() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("fyi.mllab.autoexecsec.debug", context.packageName)
        val uri = MmsPayloadProvider.uri(context, "a".repeat(64), "00000000-0000-0000-0000-000000000001")
        val provider = requireNotNull(context.packageManager.resolveContentProvider(uri.authority!!, 0))
        assertEquals("${context.packageName}.mms", provider.authority)
        assertEquals(MmsPayloadProvider::class.java.name, provider.name)
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        assertNull(context.packageManager.resolveContentProvider("local.reminders.mms", 0))

        shadowOf(context as Application).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        Platform(context).also { it.channels(); it.notifyPrompt() }
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        val notification = requireNotNull(shadowOf(manager).getNotification(11))
        val intent = shadowOf(notification.contentIntent).savedIntent
        assertEquals(context.packageName, intent.component!!.packageName)
        assertEquals("local.reminders.MainActivity", intent.component!!.className)
        assertNotNull(context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY))
        assertTrue(intent.getBooleanExtra("open_import", false))
    }

    @Test fun broadcastApprovalWithoutWarningAcknowledgmentCannotReachStorageOrDispatch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val runtime = ReminderRuntime(context)
        val preview = BroadcastPreview("preview", emptyList(), "Must not send", 17, 5, 0, 0)
        val error = assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { runtime.approveBroadcast(preview, acknowledged = false) }
        }
        assertTrue(error.message!!.contains("acknowledge"))
    }

    @Test fun deniedPermissionsGiveActionableBlockersWithoutDefaultSim() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).denyPermissions(Manifest.permission.SEND_SMS, Manifest.permission.READ_PHONE_STATE,
            Manifest.permission.POST_NOTIFICATIONS)
        val platform = Platform(context).also { it.channels() }
        val caps = platform.capabilities()
        assertFalse(caps.ready)
        assertTrue(caps.subscriptions.isEmpty())
        assertTrue(caps.blockers.any { it.contains("installer") })
        assertTrue(caps.blockers.any { it.contains("phone-state") })
        assertNotNull(platform.selectedBlocker(1234, caps))
    }

    @Test fun genericNotificationsHaveSeparatePrivateChannels() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Platform(context).channels()
        val manager = requireNotNull(context.getSystemService(NotificationManager::class.java))
        assertNotEquals(Platform.INPUT_CHANNEL, Platform.STATUS_CHANNEL)
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE,
            manager.getNotificationChannel(Platform.INPUT_CHANNEL).lockscreenVisibility)
        assertEquals(android.app.Notification.VISIBILITY_PRIVATE,
            manager.getNotificationChannel(Platform.STATUS_CHANNEL).lockscreenVisibility)
    }

    @Test fun settingsRoundTripKeepsUiLockAndPromptCoverageInputs() {
        val original = AppSettings(screenLock = true, subscriptionId = 31, subscriptionAutomatic = true,
            groupTemplates = mapOf(2 to "Hello {FirstName}", 3 to "The {LastName} group"),
            promptDates = setOf(java.time.LocalDate.of(2026, 12, 1)),
            promptWeekdays = setOf(java.time.DayOfWeek.MONDAY),
            promptTime = java.time.LocalTime.of(19, 30))
        assertEquals(original, SettingsCodec.decode(SettingsCodec.encode(original)))
    }

    @Test fun batchTemplateAndDelayRoundTripAndLegacySettingsUseDefaults() {
        val settings = AppSettings(broadcastTemplate = "Hi {FirstName} {LastName}.", broadcastDelaySeconds = 37)
        assertEquals(settings, SettingsCodec.decode(SettingsCodec.encode(settings)))
        val legacy = org.json.JSONObject(SettingsCodec.encode(settings)).apply {
            remove("broadcastTemplate"); remove("broadcastDelaySeconds")
        }
        assertEquals(AppSettings().broadcastTemplate, SettingsCodec.decode(legacy.toString()).broadcastTemplate)
        assertEquals(5L, SettingsCodec.decode(legacy.toString()).broadcastDelaySeconds)
    }

    @Test fun exactCountTemplatesAndCarrierMmsChecksFailClosed() {
        val settings = AppSettings(groupTemplates = mapOf(2 to "Hello {FirstName}"))
        assertEquals(settings.template, settings.templateFor(1))
        assertEquals("Hello {FirstName}", settings.templateFor(2))
        assertThrows(IllegalArgumentException::class.java) { settings.templateFor(3) }
        val legacy = org.json.JSONObject(SettingsCodec.encode(settings)).apply { remove("groupTemplates") }
        assertTrue(SettingsCodec.decode(legacy.toString()).groupTemplates.isEmpty())
        val config = MmsCapability(2, 1024)
        assertNull(config.check(2, 1024))
        assertNotNull(config.check(3, 100))
        assertNotNull(config.check(2, 1025))
        assertNotNull(MmsCapability(blocker = "Unavailable").check(2, 10))
        val platform = Platform(ApplicationProvider.getApplicationContext<Context>())
        val caps = Capabilities(emptyList(), listOf(SimChoice(17, "SIM")))
        assertNotNull(platform.mmsBlocker(17, listOf("+12025550101", "+12025550102"), "Test", "test", caps))
    }

    @Test fun soleSimIsAutomaticButMultipleNeedAChoiceAndFrozenSimNeverSwitches() {
        val single = Capabilities(emptyList(), listOf(SimChoice(17, "Only SIM")))
        assertEquals(17, single.selectionForNewApprovals(-1))
        assertEquals(17, single.selectionForNewApprovals(3))
        val multiple = single.copy(subscriptions = single.subscriptions + SimChoice(21, "Second SIM"))
        assertEquals(-1, multiple.selectionForNewApprovals(-1))
        assertEquals(-1, multiple.selectionForNewApprovals(3))
        assertEquals(21, multiple.selectionForNewApprovals(21))
        assertEquals(-1, multiple.selectionForNewApprovals(17, automatic = true))
        assertEquals(17, single.selectionForNewApprovals(17, automatic = true))
        assertEquals(-1, single.copy(subscriptions = emptyList()).selectionForNewApprovals(17))
        val platform = Platform(ApplicationProvider.getApplicationContext<Context>())
        assertNotNull(platform.selectedBlocker(3, single))
    }

    @Test fun defaultRegionIsUsAndSavedExplicitRegionSurvives() {
        assertEquals("US", SettingsCodec.decode(null).country)
        val explicit = AppSettings(country = "GB")
        assertEquals("GB", SettingsCodec.decode(SettingsCodec.encode(explicit)).country)
        val legacy = org.json.JSONObject(SettingsCodec.encode(explicit.copy(subscriptionId = 17)))
            .apply { remove("simAutomatic") }.toString()
        assertFalse(SettingsCodec.decode(legacy).subscriptionAutomatic)
        assertEquals(17, SettingsCodec.decode(legacy).subscriptionId)
    }

    @Test fun airplaneModeIsReadOnlyPreflightForTheApprovedSim() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 1)
        val caps = Capabilities(emptyList(), listOf(SimChoice(17, "SIM 1")))
        assertTrue(Platform(context).selectedBlocker(17, caps)!!.contains("Airplane mode"))
        assertEquals(1, Settings.Global.getInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON))
    }

    @Test
    @Config(sdk = [26], application = Application::class)
    fun selectedSimDoesNotRequireLocationToPredictService() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        shadowOf(context).denyPermissions(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)
        Settings.Global.putInt(context.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0)
        val caps = Capabilities(emptyList(), listOf(SimChoice(17, "SIM 1")))
        assertNull(Platform(context).selectedBlocker(17, caps))
        assertNotNull(Platform(context).selectedBlocker(-1, caps))
    }

    @Test fun deliveryReportRequiresAValidPduAndExplicitFormat() {
        assertNull(DeliveryReports.status(Intent()))
        assertNull(DeliveryReports.status(Intent().putExtra("pdu", statusReportPdu(0))))
        assertNull(DeliveryReports.status(Intent().putExtra("pdu", statusReportPdu(0)).putExtra("format", "unknown")))
        assertNull(DeliveryReports.status(Intent().putExtra("pdu", byteArrayOf(0)).putExtra("format", "3gpp")))
        assertNull(DeliveryReports.status(Intent().putExtra("pdu", ByteArray(4097)).putExtra("format", "3gpp")))
    }

    @Test fun deliveryReportParserDistinguishesCompletePendingAndFailed() {
        for (status in listOf(0, 32, 64)) {
            val intent = Intent().putExtra("pdu", statusReportPdu(status)).putExtra("format", "3gpp")
            assertEquals(status, requireNotNull(DeliveryReports.status(intent)))
        }
    }

    @Test fun onlyDeliveryCallbacksAreMutableForFrameworkReportExtras() {
        val platform = Platform(ApplicationProvider.getApplicationContext<Context>())
        assertTrue(platform.callback("digest", "attempt", 0, false).isImmutable)
        assertFalse(platform.callback("digest", "attempt", 0, true).isImmutable)
    }

    @Test fun submissionTimeoutNeverReplacesOrDowngradesExactDispatch() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val platform = Platform(context)
        val alarms = shadowOf(requireNotNull(context.getSystemService(AlarmManager::class.java)))
        ShadowAlarmManager.setCanScheduleExactAlarms(true)
        val dispatchAt = 1_800_000_000_000L
        platform.wake(dispatchAt, true)
        platform.timeout(dispatchAt - 60_000)
        platform.prompt(dispatchAt + 60_000, "2027-01-12")
        platform.timeout(dispatchAt - 30_000)

        assertEquals(3, alarms.scheduledAlarms.size)
        val dispatch = alarms.scheduledAlarms.single {
            shadowOf(it.operation).savedIntent.action == Platform.DISPATCH
        }
        val timeout = alarms.scheduledAlarms.single {
            shadowOf(it.operation).savedIntent.action == Platform.TIMEOUT
        }
        assertEquals(dispatchAt, dispatch.triggerAtMs)
        assertEquals(0L, dispatch.windowLengthMs)
        assertTrue(dispatch.isAllowWhileIdle)
        assertEquals(dispatchAt - 30_000, timeout.triggerAtMs)
        assertNotEquals(0L, timeout.windowLengthMs)
        assertTrue(timeout.isAllowWhileIdle)

        platform.cancelTimeout()
        assertEquals(2, alarms.scheduledAlarms.size)
        assertTrue(alarms.scheduledAlarms.contains(dispatch))
        platform.cancelWake()
        assertEquals(Platform.PROMPT, shadowOf(alarms.scheduledAlarms.single().operation).savedIntent.action)
    }

    private fun statusReportPdu(status: Int): ByteArray {
        val timestamp = listOf(0x62, 0x10, 0x10, 0, 0, 0, 0)
        // Synthetic GSM SMS-STATUS-REPORT: empty SMSC, reference 7, destination 1234.
        return (listOf(0, 2, 7, 4, 0x81, 0x21, 0x43) + timestamp + timestamp + status)
            .map { it.toByte() }.toByteArray()
    }
}
