package local.reminders.runtime

import android.app.Activity
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import local.reminders.domain.ReminderState
import local.reminders.domain.Recipient
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.SQLiteMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
@ConscryptMode(ConscryptMode.Mode.OFF)
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class TransactionsTest {
    private lateinit var db: RuntimeDatabase
    private lateinit var tx: Transactions
    private val now = 1_800_000_000_000L

    @Before fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(),
            RuntimeDatabase::class.java).allowMainThreadQueries().build()
        tx = Transactions(db)
    }
    @After fun close() = db.close()

    private fun row(id: String = "digest", parts: Int = 2) = ReminderEntity(
        id, if (id in setOf("digest", "pending")) "person" else "person_$id", "Private name", "+12025550123", "Private message",
        now + 3_600_000, now - 1_000, "UTC", subscriptionId = 17, parts = parts, updatedAt = now)

    private fun approveAndClaim(parts: Int = 2): ReminderEntity {
        tx.approve(listOf(row(parts = parts)), now)
        return requireNotNull(tx.claim("digest", "attempt", "process", now))
    }

    private fun group() = row(parts = 1).copy(members = PeopleCodec.encode(listOf(
        Recipient("person", "Alex Example", "+12025550101", "Alex", "Example"),
        Recipient("sam", "Sam Example", "+12025550102", "Sam", "Example"))))

    private fun broadcast(count: Int = 3, batch: String = "batch") = (1..count).map {
        row("$batch:$it", parts = 1).copy(phone = "+1202555${it.toString().padStart(4, '0')}",
            appointmentAt = 0, sendAt = now, broadcastId = batch,
            broadcastExpiresAt = now + local.reminders.domain.Broadcasts.WINDOW_MILLIS,
            broadcastDelaySeconds = 5)
    }

    @Test fun hundredRecipientBroadcastIsIndependentOfAppointmentCapAndCoverage() {
        tx.approve((1..40).map { row("appointment:$it") }, now)
        val coverage = db.dao().coverage()
        tx.approveBroadcast(broadcast(100), now)
        assertEquals(140, db.dao().activeReminders().size)
        assertEquals(coverage, db.dao().coverage())
        assertThrows(IllegalArgumentException::class.java) { tx.approveBroadcast(broadcast(batch = "second"), now) }
        assertThrows(IllegalArgumentException::class.java) { tx.approve(listOf(row("extra")), now) }
        tx.stopBroadcast("batch", now + 1)
        assertEquals(40, db.dao().activeReminders().size)
        assertEquals(coverage, db.dao().coverage())
    }

    @Test fun duplicateNumbersOrMixedBroadcastPayloadsRollBackApproval() {
        val rows = broadcast()
        assertThrows(IllegalArgumentException::class.java) {
            tx.approveBroadcast(listOf(rows[0], rows[1].copy(phone = rows[0].phone)), now)
        }
        assertThrows(IllegalArgumentException::class.java) {
            tx.approveBroadcast(listOf(rows[0], rows[1].copy(subscriptionId = 99)), now)
        }
        assertTrue(db.dao().reminders().isEmpty())
    }

    @Test fun broadcastWaitsForEachSubmissionAndStopsOnlyRemainingQueuedRows() {
        val rows = broadcast()
        tx.approveBroadcast(rows, now)
        val first = tx.claim(rows[0].id, "attempt", "process", now)!!
        assertNull(tx.claim(rows[1].id, "too-soon", "process", now))
        tx.callback(first.id, "attempt", 0, false, Activity.RESULT_OK, now + 1)
        assertNull(tx.claim(rows[1].id, "delay-not-reached", "process", now + 5_000))
        assertNotNull(tx.claim(rows[1].id, "second", "process", now + 5_001))
        tx.stopBroadcast("batch", now + 5_002)
        assertEquals(listOf("SENT", "SUBMITTING", "CANCELLED"), rows.map { db.dao().reminder(it.id)!!.state })
        assertNull(tx.claim(rows[2].id, "after-stop", "process", now + 5_003))
        assertEquals(2, db.dao().rateAttempts().size)
        assertTrue(db.dao().coverage().isEmpty())
    }

    @Test fun frozenDelaySurvivesRecoveryDuplicateCallbacksAndHistoryDeletion() {
        val rows = broadcast().mapIndexed { index, row ->
            row.copy(broadcastDelaySeconds = 37, parts = if (index == 0) 2 else 1)
        }
        tx.approveBroadcast(rows, now)
        tx.claim(rows[0].id, "attempt", "process", now)
        tx.callback(rows[0].id, "attempt", 0, false, Activity.RESULT_OK, now + 20)
        assertEquals(now, tx.availableAt(rows[1]))
        assertNull(tx.claim(rows[1].id, "incomplete-parts", "process", now + 50))
        tx.callback(rows[0].id, "attempt", 1, false, Activity.RESULT_OK, now + 100)
        assertEquals(now + 37_100, tx.availableAt(rows[1]))
        tx.callback(rows[0].id, "attempt", 1, false, Activity.RESULT_OK, now + 500)
        db.dao().putValue(StoredValue("settings", SettingsCodec.encode(AppSettings(broadcastDelaySeconds = 1))))
        tx.reconcile(now + 1_000, "new-process", 30)
        tx.deleteHistory()
        assertNull(db.dao().reminder(rows[0].id))
        assertEquals(now + 37_100, Transactions(db).availableAt(rows[1]))
        assertNull(tx.claim(rows[1].id, "early", "new-process", now + 37_099))
        assertNotNull(tx.claim(rows[1].id, "on-boundary", "new-process", now + 37_100))
    }

    @Test fun personalizedMessagesAndSegmentCountsStayFrozenPerRecipient() {
        val rows = broadcast(2).mapIndexed { index, row ->
            row.copy(message = "Personalized name $index", parts = index + 1)
        }
        tx.approveBroadcast(rows, now)
        assertEquals(rows.map { it.message }, db.dao().reminders().map { it.message })
        assertEquals(listOf(1, 2), db.dao().reminders().map { it.parts })
        assertTrue(db.dao().reminders().all { !it.group })
        tx.saveRecipients(listOf(Recipient(rows[0].recipientId, "Changed", rows[0].phone)), now + 1)
        tx.saveRecipients(listOf(Recipient(rows[0].recipientId, "Changed again", rows[0].phone)), now + 2)
        assertEquals("CANCELLED", db.dao().reminder(rows[0].id)!!.state)
        assertEquals(rows[0].message, db.dao().reminder(rows[0].id)!!.message)
        assertEquals("PENDING", db.dao().reminder(rows[1].id)!!.state)
    }

    @Test fun broadcastFailureAndCrashRecoveryStopRemainderWithoutAutomaticRetry() {
        val rows = broadcast()
        tx.approveBroadcast(rows, now)
        tx.claim(rows[0].id, "attempt", "old-process", now)
        tx.reconcile(now + 1, "new-process", 30)
        assertEquals(listOf("UNCERTAIN", "CANCELLED", "CANCELLED"), rows.map { db.dao().reminder(it.id)!!.state })
        tx.callback(rows[0].id, "attempt", 0, false, Activity.RESULT_OK, now + 2)
        assertEquals("UNCERTAIN", db.dao().reminder(rows[0].id)!!.state)
        assertNull(tx.claim(rows[1].id, "retry", "new-process", now + 3))
        assertTrue(tx.acknowledgeUncertain(rows[0].id))
        tx.deleteHistory()
        assertNull(db.dao().value("broadcast-next:batch"))
        assertThrows(IllegalArgumentException::class.java) { tx.approveBroadcast(rows, now + 4) }
        val next = broadcast(batch = "new-batch")
        tx.approveBroadcast(next, now + 4)
        tx.claim(next[0].id, "failed", "new-process", now + 4)
        tx.callback(next[0].id, "failed", 0, false, 1, now + 5)
        assertEquals(listOf("FAILED", "CANCELLED", "CANCELLED"), next.map { db.dao().reminder(it.id)!!.state })
    }

    @Test fun broadcastWindowIsNotAppointmentGraceAndExpiresWithoutChangingAppointments() {
        val rows = broadcast()
        tx.approveBroadcast(rows, now)
        tx.approve(listOf(row()), now)
        tx.reconcile(now + 16 * 60_000, "process", 30)
        assertEquals("EXPIRED", db.dao().reminder("digest")!!.state)
        assertTrue(rows.all { db.dao().reminder(it.id)!!.state == "PENDING" })
        tx.reconcile(rows[0].expiresAt, "process", 30)
        assertTrue(rows.all { db.dao().reminder(it.id)!!.state in setOf("EXPIRED", "CANCELLED") })
        assertNull(tx.claim(rows[0].id, "expired", "process", rows[0].expiresAt))
    }

    @Test fun changingAnyGroupMemberCancelsWholePendingApprovalAndPreservesFrozenPayload() {
        val group = group()
        val people = group.people()
        tx.saveRecipients(people, now)
        tx.approve(listOf(group), now)
        tx.saveRecipients(listOf(people[1].copy(lastName = "Changed")), now + 1)
        val cancelled = db.dao().reminder(group.id)!!
        assertEquals("CANCELLED", cancelled.state)
        assertEquals(people, cancelled.people())
        assertTrue(db.dao().coverage().isEmpty())
    }

    @Test fun mmsHasOneSendResultNoDeliveryReportAndNoRetargetOrRetry() {
        tx.approve(listOf(group()), now)
        val claimed = tx.claim("digest", "attempt", "process", now)!!
        assertEquals(now + Outcomes.MMS_CALLBACK_TIMEOUT, claimed.deadline!!)
        tx.callback("digest", "attempt", 0, true, Activity.RESULT_OK, now + 1, DeliveryReports.COMPLETE)
        assertFalse(db.dao().reminder("digest")!!.delivered)
        tx.callback("digest", "attempt", 0, false, Activity.RESULT_OK, now + 1)
        val sent = db.dao().reminder("digest")!!
        assertEquals("SENT", sent.state)
        assertTrue(sent.detail.contains("Per-person delivery"))
        assertEquals(17, sent.subscriptionId)
        assertFalse(tx.cancel("digest", now + 2))
        tx.deleteHistory()
        assertThrows(IllegalArgumentException::class.java) { tx.approve(listOf(group().copy(id = "changed-group")), now + 2) }
    }

    @Test fun overlappingGroupAndIndividualCannotBeApprovedTogetherOrAroundPendingGroup() {
        val group = group()
        val individual = row("individual").copy(recipientId = "sam")
        assertThrows(IllegalArgumentException::class.java) { tx.approve(listOf(group, individual), now) }
        assertTrue(db.dao().reminders().isEmpty())
        tx.approve(listOf(group), now)
        assertThrows(IllegalArgumentException::class.java) { tx.approve(listOf(individual), now) }
        assertTrue(tx.cancel(group.id, now))
        tx.approve(listOf(individual), now)
    }

    @Test fun peopleImportPreservesUnchangedApprovalsButFirstNameChangesRequireReapproval() {
        val person = Recipient("person", "Mary Ann Example", "+12025550101", "Mary Ann")
        tx.saveRecipients(listOf(person), now)
        tx.approve(listOf(row()), now)
        tx.saveRecipients(listOf(person), now + 1)
        assertEquals("PENDING", db.dao().reminder("digest")!!.state)
        assertEquals("Mary Ann", db.dao().recipient("person")!!.model().firstName)
        tx.saveRecipients(listOf(person.copy(firstName = "Mary")), now + 2)
        assertEquals("CANCELLED", db.dao().reminder("digest")!!.state)
        assertEquals("Private message", db.dao().reminder("digest")!!.message)
        assertEquals("UTC", db.dao().reminder("digest")!!.zone)
    }

    @Test fun invalidPeopleBatchLeavesPeopleAndPendingApprovalUntouched() {
        val person = Recipient("person", "Alex Example", "+12025550101")
        tx.saveRecipients(listOf(person), now)
        tx.approve(listOf(row()), now)
        assertThrows(IllegalArgumentException::class.java) {
            tx.saveRecipients(listOf(person.copy(name = "Changed"), person.copy(phone = "+12025550102")), now + 1)
        }
        assertEquals(person, db.dao().recipient("person")!!.model())
        assertEquals("PENDING", db.dao().reminder("digest")!!.state)
    }

    @Test fun claimIsTransactionalAndLocksCancellationAndSubscription() {
        val claimed = approveAndClaim()
        assertEquals("SUBMITTING", claimed.state)
        assertEquals(17, claimed.subscriptionId)
        assertEquals(now + Outcomes.CALLBACK_TIMEOUT, requireNotNull(claimed.deadline))
        assertEquals(now, requireNotNull(claimed.attemptAt))
        assertFalse(tx.cancel(claimed.id, now))
        assertNull(tx.claim(claimed.id, "other-attempt", "process", now))
        assertEquals(1, db.dao().tombstone(claimed.id))
        assertEquals(1, db.dao().rateAttempts().size)
    }

    @Test fun cancellationAllowsReapprovalButProcessedHistoryDeletionDoesNot() {
        tx.approve(listOf(row()), now)
        assertTrue(tx.cancel("digest", now))
        tx.approve(listOf(row()), now)
        tx.claim("digest", "attempt", "process", now)
        tx.callback("digest", "attempt", 0, false, Activity.RESULT_OK, now)
        tx.callback("digest", "attempt", 1, false, Activity.RESULT_OK, now)
        tx.deleteHistory()
        assertNull(db.dao().reminder("digest"))
        assertEquals(1, db.dao().rateAttempts().size)
        assertThrows(IllegalArgumentException::class.java) { tx.approve(listOf(row()), now) }
    }

    @Test fun duplicateBatchRollsBackAllRows() {
        tx.approve(listOf(row()), now)
        assertThrows(IllegalArgumentException::class.java) {
            tx.approve(listOf(row("new"), row()), now)
        }
        assertNull(db.dao().reminder("new"))
        assertEquals(1, db.dao().reminders().size)
    }

    @Test fun pendingCapIncludesSubmitting() {
        tx.approve((1..40).map { row("id$it") }, now)
        tx.claim("id1", "attempt", "process", now)
        assertThrows(IllegalArgumentException::class.java) { tx.approve(listOf(row("new")), now) }
    }

    @Test fun allAuthoritativeSuccessesRequiredForSent() {
        approveAndClaim()
        tx.callback("digest", "stale", 0, false, Activity.RESULT_OK, now)
        tx.callback("digest", "attempt", 9, false, Activity.RESULT_OK, now)
        assertEquals("SUBMITTING", db.dao().reminder("digest")!!.state)
        tx.callback("digest", "attempt", 1, false, Activity.RESULT_OK, now)
        assertEquals("SUBMITTING", db.dao().reminder("digest")!!.state)
        tx.callback("digest", "attempt", 0, false, Activity.RESULT_OK, now)
        assertEquals("SENT", db.dao().reminder("digest")!!.state)
        assertFalse(db.dao().reminder("digest")!!.delivered)
        tx.callback("digest", "attempt", 0, true, Activity.RESULT_OK, now, DeliveryReports.COMPLETE)
        tx.callback("digest", "attempt", 1, true, Activity.RESULT_OK, now, DeliveryReports.COMPLETE)
        assertTrue(db.dao().reminder("digest")!!.delivered)
    }

    @Test fun allExplicitFailuresBecomeFailedAndNeverReclaim() {
        approveAndClaim()
        tx.callback("digest", "attempt", 0, false, 1, now)
        tx.callback("digest", "attempt", 1, false, 2, now)
        assertEquals("FAILED", db.dao().reminder("digest")!!.state)
        assertNull(tx.claim("digest", "new", "process", now))
        assertThrows(IllegalArgumentException::class.java) { tx.approve(listOf(row()), now) }
    }

    @Test fun mixedCallbacksAreUncertainAndDuplicateCallbackCannotOverwrite() {
        approveAndClaim()
        tx.callback("digest", "attempt", 0, false, Activity.RESULT_OK, now)
        tx.callback("digest", "attempt", 0, false, 1, now)
        tx.callback("digest", "attempt", 1, false, 1, now)
        assertEquals("UNCERTAIN", db.dao().reminder("digest")!!.state)
        tx.deleteHistory()
        assertNotNull(db.dao().reminder("digest"))
        assertThrows(IllegalStateException::class.java) { tx.deleteAll() }
    }

    @Test fun timeoutRemainsUncertainDespiteLateSuccesses() {
        approveAndClaim()
        tx.reconcile(now + Outcomes.CALLBACK_TIMEOUT, "process", 30)
        tx.callback("digest", "attempt", 0, false, Activity.RESULT_OK, now + Outcomes.CALLBACK_TIMEOUT + 1)
        tx.callback("digest", "attempt", 1, false, Activity.RESULT_OK, now + Outcomes.CALLBACK_TIMEOUT + 1)
        assertEquals("UNCERTAIN", db.dao().reminder("digest")!!.state)
    }

    @Test fun callbackAtDeadlineDoesNotRaceIntoSuccess() {
        approveAndClaim(parts = 1)
        tx.callback("digest", "attempt", 0, false, Activity.RESULT_OK, now + Outcomes.CALLBACK_TIMEOUT)
        assertEquals("UNCERTAIN", db.dao().reminder("digest")!!.state)
    }

    @Test fun processRecoveryMarksCrashGapUncertainWithoutRetry() {
        approveAndClaim()
        tx.reconcile(now + 1, "new-process", 30)
        assertEquals("UNCERTAIN", db.dao().reminder("digest")!!.state)
        assertNull(tx.claim("digest", "new-attempt", "new-process", now + 1))
    }

    @Test fun submittingPreventedFromDeleteAll() {
        approveAndClaim()
        assertThrows(IllegalStateException::class.java) { tx.deleteAll() }
        assertEquals("SUBMITTING", db.dao().reminder("digest")!!.state)
    }

    @Test fun expiryBlocksReimportAndRemovesApprovalCoverage() {
        tx.approve(listOf(row()), now)
        tx.reconcile(now + 16 * 60_000, "process", 30)
        assertEquals("EXPIRED", db.dao().reminder("digest")!!.state)
        assertTrue(db.dao().coverage().isEmpty())
        assertThrows(IllegalArgumentException::class.java) { tx.approve(listOf(row()), now) }
    }

    @Test fun inputChangesCancelOnlyPendingAndPreserveTerminalOutcomes() {
        approveAndClaim()
        tx.callback("digest", "attempt", 0, false, Activity.RESULT_OK, now)
        tx.callback("digest", "attempt", 1, false, Activity.RESULT_OK, now)
        tx.approve(listOf(row("pending").copy(appointmentAt = now + 86_400_000, sendAt = now + 85_000_000)), now)
        tx.cancelAffected({ it.recipientId == "person" }, now)
        assertEquals("SENT", db.dao().reminder("digest")!!.state)
        assertEquals("CANCELLED", db.dao().reminder("pending")!!.state)
    }

    @Test fun retentionRetainsUnknownAndPreservesProcessedTombstones() {
        approveAndClaim()
        tx.reconcile(now + 1, "new-process", 30)
        tx.approve(listOf(row("failed")), now)
        tx.finish("failed", ReminderState.FAILED, "Preparation failed.", now)
        tx.reconcile(now + 31 * 86_400_000L, "new-process", 30)
        assertNull(db.dao().reminder("failed"))
        assertEquals(1, db.dao().tombstone("failed"))
        assertEquals("UNCERTAIN", db.dao().reminder("digest")!!.state)
    }

    @Test fun explicitNoneCoverageSurvivesCancellation() {
        tx.approve(listOf(row()), now)
        val date = appointmentDate(row())
        db.dao().putCoverage(Coverage(date, "none"))
        tx.cancel("digest", now)
        assertEquals("none", db.dao().coverage().single().source)
    }

    @Test fun acknowledgingUnknownEnablesFullDeletionWithoutPermittingResend() {
        approveAndClaim()
        tx.reconcile(now + 1, "new-process", 30)
        assertThrows(IllegalStateException::class.java) { tx.deleteAll() }
        assertTrue(tx.acknowledgeUncertain("digest"))
        assertEquals("UNCERTAIN", db.dao().reminder("digest")!!.state)
        assertFalse(db.dao().hasUnreviewedUnknown())
        assertTrue(runBlocking { ReminderStore { db }.reminders().single().reviewed })
        assertNull(tx.claim("digest", "retry", "new-process", now + 2))
        assertThrows(IllegalArgumentException::class.java) { tx.approve(listOf(row()), now + 2) }
        tx.deleteAll()
        assertNull(db.dao().reminder("digest"))
        assertNull(db.dao().value("reviewed:digest"))
        assertEquals(1, db.dao().tombstone("digest"))
        assertThrows(IllegalArgumentException::class.java) { tx.approve(listOf(row()), now + 2) }
    }

    @Test fun acknowledgementIsIdempotentAndLateCallbacksNeverPromoteUnknown() {
        approveAndClaim()
        tx.reconcile(now + 1, "new-process", 30)
        assertTrue(tx.acknowledgeUncertain("digest"))
        assertTrue(tx.acknowledgeUncertain("digest"))
        tx.callback("digest", "attempt", 0, false, Activity.RESULT_OK, now + 2)
        tx.callback("digest", "attempt", 1, false, Activity.RESULT_OK, now + 2)
        assertEquals("UNCERTAIN", db.dao().reminder("digest")!!.state)
        assertEquals("true", db.dao().value("reviewed:digest"))
        assertEquals(1, db.dao().tombstone("digest"))
    }

    @Test fun acknowledgementRefusesNonUnknownAndMissingRecords() {
        tx.approve(listOf(row()), now)
        assertFalse(tx.acknowledgeUncertain("digest"))
        assertFalse(tx.acknowledgeUncertain("missing"))
        assertFalse(tx.acknowledgeUncertain("x".repeat(129)))
        tx.claim("digest", "attempt", "process", now)
        assertFalse(tx.acknowledgeUncertain("digest"))
        assertNull(db.dao().value("reviewed:digest"))
        assertThrows(IllegalStateException::class.java) { tx.deleteAll() }
    }

    @Test fun historyDeletionDeletesReviewedUnknownButRetainsUnreviewedUnknown() {
        tx.approve(listOf(row(), row("unreviewed")), now)
        tx.claim("digest", "first-attempt", "process", now)
        tx.claim("unreviewed", "second-attempt", "process", now)
        tx.reconcile(now + 1, "new-process", 30)
        assertTrue(tx.acknowledgeUncertain("digest"))
        tx.deleteHistory()
        assertNull(db.dao().reminder("digest"))
        assertEquals(1, db.dao().tombstone("digest"))
        assertEquals("UNCERTAIN", db.dao().reminder("unreviewed")!!.state)
        assertTrue(db.dao().hasUnreviewedUnknown())
        assertThrows(IllegalStateException::class.java) { tx.deleteAll() }
    }

    @Test fun reviewedUnknownStillRequiresExplicitDeletionRatherThanRetentionCleanup() {
        approveAndClaim()
        tx.reconcile(now + 1, "new-process", 30)
        tx.acknowledgeUncertain("digest")
        tx.reconcile(now + 31 * 86_400_000L, "new-process", 30)
        assertEquals("UNCERTAIN", db.dao().reminder("digest")!!.state)
        assertEquals("true", db.dao().value("reviewed:digest"))
    }

    @Test fun noRemindersSuppressesPromptWithoutCancellingApprovedPendingSms() {
        tx.approve(listOf(row()), now)
        tx.markNoReminders(java.time.LocalDate.parse(appointmentDate(row())))
        assertEquals("PENDING", db.dao().reminder("digest")!!.state)
        assertEquals("none", db.dao().coverage().single().source)
        assertNotNull(tx.claim("digest", "attempt", "process", now))
    }

    @Test fun deliveryResultOkWithoutAnAuthoritativeStatusIsNotProofOfDelivery() {
        approveAndClaim()
        tx.callback("digest", "attempt", 0, true, Activity.RESULT_OK, now)
        tx.callback("digest", "attempt", 1, true, Activity.RESULT_OK, now)
        tx.callback("digest", "attempt", 0, true, Activity.RESULT_OK, now, -1)
        assertFalse(db.dao().reminder("digest")!!.delivered)
        assertTrue(results(db.dao().reminder("digest")!!.deliveryResults, 2).all { it == null })
        assertEquals("SUBMITTING", db.dao().reminder("digest")!!.state)
    }

    @Test fun pendingAndErrorDeliveryReportsCanBeFollowedByFinalCompleteReports() {
        approveAndClaim()
        tx.callback("digest", "attempt", 0, true, Activity.RESULT_OK, now, 0x20)
        tx.callback("digest", "attempt", 1, true, Activity.RESULT_OK, now, 0x40)
        assertFalse(db.dao().reminder("digest")!!.delivered)
        tx.callback("digest", "attempt", 0, true, Activity.RESULT_OK, now, DeliveryReports.COMPLETE)
        assertFalse(db.dao().reminder("digest")!!.delivered)
        tx.callback("digest", "attempt", 1, true, Activity.RESULT_OK, now, DeliveryReports.COMPLETE)
        assertTrue(db.dao().reminder("digest")!!.delivered)
        assertEquals("SUBMITTING", db.dao().reminder("digest")!!.state)
        tx.callback("digest", "attempt", 1, true, Activity.RESULT_OK, now, 0x20)
        assertTrue(db.dao().reminder("digest")!!.delivered)
    }
}
