package com.worxbend.zephyr

import com.worxbend.zephyr.domain.OperationJournalEntry
import com.worxbend.zephyr.domain.OperationStatus
import com.worxbend.zephyr.domain.SdkmanTransaction
import com.worxbend.zephyr.settings.OperationNotificationPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class OperationNotificationsTest {
    @Test
    fun longRunningPolicyIgnoresQuickOperations() {
        assertNull(operationNotification(OperationNotificationPolicy.LongRunning, entry(completedAt = 9_999)))
        assertEquals(
            7,
            operationNotification(OperationNotificationPolicy.LongRunning, entry(completedAt = 10_000))?.entryId,
        )
    }

    @Test
    fun notificationNeverIncludesRepositoryOutcomeOrSensitivePaths() {
        val notification =
            operationNotification(
                OperationNotificationPolicy.AllCompletions,
                entry(completedAt = 2, status = OperationStatus.Failed, outcome = "Failed in /home/alex/.sdkman"),
            )

        assertFalse(notification!!.message.contains("/home/alex"))
        assertEquals(
            "1 reviewed step(s) finished with a failure. Open Task Center for details.",
            notification.message,
        )
    }

    private fun entry(
        completedAt: Long,
        status: OperationStatus = OperationStatus.Succeeded,
        outcome: String = "Installed",
    ) = OperationJournalEntry(
        id = 7,
        transaction = SdkmanTransaction.Install("java", "21-tem"),
        startedAtEpochMillis = 0,
        completedAtEpochMillis = completedAt,
        status = status,
        outcome = outcome,
    )
}
