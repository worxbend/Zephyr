package com.worxbend.zephyr.application.operation

import com.worxbend.zephyr.data.SdkmanRepository
import com.worxbend.zephyr.domain.CommandOutcome
import com.worxbend.zephyr.domain.CommandOutcomeStatus
import com.worxbend.zephyr.domain.OperationStepStatus
import com.worxbend.zephyr.domain.PlannedSdkmanCommand
import com.worxbend.zephyr.domain.SdkmanCommandAction
import com.worxbend.zephyr.domain.SdkmanSelfUpdateStatus
import com.worxbend.zephyr.domain.SdkmanTransaction
import kotlinx.coroutines.CancellationException

/** Executes one durable plan step; never retries a mutation or performs presentation refreshes. */
internal class OperationExecution(private val repository: SdkmanRepository) {
    suspend fun execute(transaction: SdkmanTransaction, command: PlannedSdkmanCommand): CommandOutcome =
        try {
            when (command.action) {
                SdkmanCommandAction.Install -> repository.install(requireNotNull(command.candidate), requireNotNull(command.version))
                SdkmanCommandAction.Uninstall -> if (transaction is SdkmanTransaction.CleanLocalOnly) {
                    // Singleton calls retain each receipt while keeping repository evidence/default/protection checks.
                    repository.cleanLocalOnly(requireNotNull(command.candidate), listOf(requireNotNull(command.version)))
                } else {
                    repository.uninstall(requireNotNull(command.candidate), requireNotNull(command.version))
                }
                SdkmanCommandAction.SetDefault -> repository.setDefault(requireNotNull(command.candidate), requireNotNull(command.version))
                SdkmanCommandAction.UpdateMetadata -> repository.refreshCandidateMetadata()
                SdkmanCommandAction.SelfUpdate -> when (val result = repository.selfUpdate()) {
                    SdkmanSelfUpdateStatus.NotChecked -> CommandOutcome(false, "SDKMAN update was not checked.", CommandOutcomeStatus.Indeterminate)
                    SdkmanSelfUpdateStatus.UpToDate -> CommandOutcome(true, "SDKMAN is up to date.")
                    SdkmanSelfUpdateStatus.Updated -> CommandOutcome(true, "SDKMAN was updated.")
                    is SdkmanSelfUpdateStatus.Failed -> CommandOutcome(false, result.message)
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // A thrown adapter error does not prove the external effect did not happen.
            CommandOutcome(false, failure.message ?: "Task outcome could not be verified.", CommandOutcomeStatus.Indeterminate)
        }
}

internal fun CommandOutcome.operationStepStatus(): OperationStepStatus = when (status) {
    CommandOutcomeStatus.Indeterminate -> OperationStepStatus.Indeterminate
    CommandOutcomeStatus.Failed -> OperationStepStatus.Failed
    CommandOutcomeStatus.Applied,
    CommandOutcomeStatus.AppliedWithWarning,
    CommandOutcomeStatus.AlreadySatisfied,
    -> OperationStepStatus.Succeeded
}
