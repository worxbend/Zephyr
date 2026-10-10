package com.worxbend.zephyr

import com.worxbend.zephyr.domain.OperationJournalEntry
import com.worxbend.zephyr.domain.OperationStatus
import com.worxbend.zephyr.domain.SdkmanTransaction
import com.worxbend.zephyr.runtime.OperationCompletionTracker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OperationCompletionTrackerTest {
    private fun entry(id: Long, completed: Long? = 2L) = OperationJournalEntry(
        id = id, transaction = SdkmanTransaction.Install("gradle", "8.0"), startedAtEpochMillis = 1L,
        completedAtEpochMillis = completed,
        status = if (completed == null) OperationStatus.Running else OperationStatus.Succeeded,
    )

    @Test
    fun hydratedHistoryNeverBecomesLiveCompletion() {
        val tracker = OperationCompletionTracker()
        assertTrue(tracker.observe(listOf(entry(1L))).isEmpty())
        assertTrue(tracker.observe(listOf(entry(1L))).isEmpty())
    }

    @Test
    fun aNewCompletionIsDeliveredOnlyOnceEvenIfRunningSnapshotWasConflated() {
        val tracker = OperationCompletionTracker()
        tracker.observe(listOf(entry(1L)))
        val live = entry(2L)
        assertEquals(listOf(live), tracker.observe(listOf(live, entry(1L))))
        assertTrue(tracker.observe(listOf(live, entry(1L))).isEmpty())
    }

    @Test
    fun existingRunningEntryCompletesAfterHydration() {
        val tracker = OperationCompletionTracker()
        tracker.observe(listOf(entry(1L, null)))
        assertEquals(listOf(entry(1L)), tracker.observe(listOf(entry(1L))))
    }

    @Test
    fun disabledPolicyCanConsumeCompletionsWithoutReplayingThemWhenEnabled() {
        val tracker = OperationCompletionTracker()
        tracker.observe(emptyList())
        tracker.observe(listOf(entry(1L))) // Observed while notification policy is off.
        assertTrue(tracker.observe(listOf(entry(1L))).isEmpty())
    }
}
