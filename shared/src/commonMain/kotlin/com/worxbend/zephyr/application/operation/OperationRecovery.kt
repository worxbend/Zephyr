package com.worxbend.zephyr.application.operation

import com.worxbend.zephyr.data.CommandSatisfaction
import com.worxbend.zephyr.data.SdkmanRepository
import com.worxbend.zephyr.domain.OperationJournalEntry
import com.worxbend.zephyr.domain.OperationStatus
import com.worxbend.zephyr.domain.OperationStepStatus
import kotlinx.coroutines.CancellationException

/** Evidence reconciliation only. The coordinator supplies exclusive lifecycle ownership. */
internal class OperationRecovery(
    private val repository: SdkmanRepository,
    private val clock: () -> Long,
) {
    suspend fun reconcile(entry: OperationJournalEntry, includeFailed: Boolean): OperationJournalEntry {
        val steps = entry.steps.map { step ->
            if (step.status == OperationStepStatus.Succeeded || step.status == OperationStepStatus.Skipped ||
                (!includeFailed && step.status == OperationStepStatus.Failed)
            ) {
                step
            } else {
                val satisfaction = try {
                    repository.commandSatisfaction(step.command)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    CommandSatisfaction.Indeterminate
                }
                when (satisfaction) {
                    CommandSatisfaction.Satisfied -> step.copy(
                        status = OperationStepStatus.Succeeded,
                        outcome = "Verified from current SDKMAN state.",
                        completedAtEpochMillis = step.completedAtEpochMillis ?: clock(),
                    )
                    CommandSatisfaction.Unsatisfied -> step.copy(
                        status = when {
                            step.status == OperationStepStatus.Pending -> OperationStepStatus.Pending
                            includeFailed && step.status == OperationStepStatus.Failed -> OperationStepStatus.Failed
                            else -> OperationStepStatus.Interrupted
                        },
                        outcome = "Current SDKMAN state does not satisfy this step.",
                        completedAtEpochMillis = null,
                    )
                    CommandSatisfaction.Indeterminate -> step.copy(
                        status = OperationStepStatus.Indeterminate,
                        outcome = "Current SDKMAN state could not verify this step.",
                        completedAtEpochMillis = null,
                    )
                }
            }
        }
        val allSucceeded = steps.all { it.status == OperationStepStatus.Succeeded }
        return entry.copy(
            completedAtEpochMillis = if (allSucceeded) entry.completedAtEpochMillis ?: clock() else entry.completedAtEpochMillis,
            status = if (allSucceeded) OperationStatus.Succeeded else OperationStatus.Interrupted,
            outcome = if (allSucceeded) "Every task step was verified after restart."
                else "Task execution was interrupted. Review the remaining steps before resuming.",
            steps = steps,
        )
    }
}
