package com.worxbend.zephyr

import com.worxbend.zephyr.domain.OperationJournalEntry
import com.worxbend.zephyr.domain.OperationStatus
import com.worxbend.zephyr.settings.OperationNotificationPolicy

internal data class OperationNotification(
    val entryId: Long,
    val title: String,
    val message: String,
)

internal fun operationNotification(
    policy: OperationNotificationPolicy,
    entry: OperationJournalEntry,
    longRunningThresholdMillis: Long = 10_000L,
): OperationNotification? {
    val completedAt = entry.completedAtEpochMillis ?: return null
    if (entry.status == OperationStatus.Running || policy == OperationNotificationPolicy.Off) return null
    val duration = (completedAt - entry.startedAtEpochMillis).coerceAtLeast(0)
    if (policy == OperationNotificationPolicy.LongRunning && duration < longRunningThresholdMillis) return null
    val stepCount = entry.transaction.commands.size
    val succeeded = entry.status == OperationStatus.Succeeded
    return OperationNotification(
        entryId = entry.id,
        title = if (succeeded) "Toolchain operation completed" else "Toolchain operation needs attention",
        message =
            if (succeeded) {
                "$stepCount reviewed step(s) completed in Zephyr."
            } else {
                "$stepCount reviewed step(s) finished with a failure. Open Task Center for details."
            },
    )
}
