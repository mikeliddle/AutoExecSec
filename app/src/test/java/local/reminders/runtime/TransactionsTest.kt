package local.reminders.runtime

import android.app.Activity
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import local.reminders.domain.ReminderState
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
        id, "person", "Private name", "+12025550123", "Private message",
        now + 3_600_000, now - 1_000, "UTC", subscriptionId = 17, parts = parts, updatedAt = now)

    private fun approveAndClaim(parts: Int = 2): ReminderEntity {
        tx.approve(listOf(row(parts = parts)), now)
        return requireNotNull(tx.claim("digest", "attempt", "process", now))
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
        tx.approve(listOf(row("pending")), now)
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
