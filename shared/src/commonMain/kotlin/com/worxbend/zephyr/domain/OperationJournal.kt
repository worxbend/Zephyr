package com.worxbend.zephyr.domain

enum class OperationStatus(
    val label: String,
) {
    Running("Running"),
    Succeeded("Succeeded"),
    Failed("Failed"),
    Interrupted("Interrupted"),
    Indeterminate("Could not verify"),
}

enum class OperationStepStatus(
    val label: String,
) {
    Pending("Pending"),
    Running("Running"),
    Succeeded("Succeeded"),
    Failed("Failed"),
    Interrupted("Interrupted"),
    Indeterminate("Could not verify"),
    Skipped("Skipped"),
}

data class OperationStep(
    val index: Int,
    val command: PlannedSdkmanCommand,
    val status: OperationStepStatus = OperationStepStatus.Pending,
    val outcome: String? = null,
    val completedAtEpochMillis: Long? = null,
)

data class OperationJournalEntry(
    val id: Long,
    val transaction: SdkmanTransaction,
    val startedAtEpochMillis: Long,
    val completedAtEpochMillis: Long? = null,
    val status: OperationStatus = OperationStatus.Running,
    val outcome: String? = null,
    val steps: List<OperationStep> =
        transaction.commands.mapIndexed { index, command ->
            OperationStep(index = index, command = command)
        },
)

fun OperationJournalEntry.resumableCommands(): List<PlannedSdkmanCommand> =
    steps
        .filter {
            it.status == OperationStepStatus.Pending ||
                it.status == OperationStepStatus.Failed ||
                it.status == OperationStepStatus.Interrupted ||
                it.status == OperationStepStatus.Skipped
        }.sortedBy(OperationStep::index)
        .map(OperationStep::command)

fun OperationJournalEntry.hasIndeterminateSteps(): Boolean = steps.any { it.status == OperationStepStatus.Indeterminate }

fun OperationJournalEntry.resumeTransaction(): SdkmanTransaction? = transaction.withCommands(resumableCommands())

fun SdkmanTransaction.withCommands(commands: List<PlannedSdkmanCommand>): SdkmanTransaction? {
    if (commands.isEmpty()) return null
    return when (this) {
        is SdkmanTransaction.Install -> {
            commands.singleOrNull()?.toSingleTransaction()
        }

        is SdkmanTransaction.BatchInstall -> {
            commands
                .mapNotNull { command ->
                    command.takeIf { it.action == SdkmanCommandAction.Install }?.let {
                        InstallTarget(requireNotNull(it.candidate), requireNotNull(it.version))
                    }
                }.takeIf(List<InstallTarget>::isNotEmpty)
                ?.let(SdkmanTransaction::BatchInstall)
        }

        is SdkmanTransaction.SnapshotRestore -> {
            SdkmanTransaction.SnapshotRestore(commands)
        }

        is SdkmanTransaction.ToolchainActivation -> {
            SdkmanTransaction.ToolchainActivation(profileName, commands)
        }

        is SdkmanTransaction.UpdateActivation -> {
            val installs =
                commands
                    .filter { it.action == SdkmanCommandAction.Install }
                    .map { requireNotNull(it.candidate) to requireNotNull(it.version) }
                    .toSet()
            commands
                .filter { it.action == SdkmanCommandAction.SetDefault }
                .map {
                    UpdateActivationTarget(
                        requireNotNull(it.candidate),
                        requireNotNull(it.version),
                        (it.candidate to it.version) in installs,
                    )
                }.takeIf(List<UpdateActivationTarget>::isNotEmpty)
                ?.let(SdkmanTransaction::UpdateActivation)
        }

        is SdkmanTransaction.Uninstall -> {
            commands.singleOrNull()?.toSingleTransaction()
        }

        is SdkmanTransaction.BatchUninstall -> {
            commands
                .mapNotNull { command ->
                    command.takeIf { it.action == SdkmanCommandAction.Uninstall }?.let {
                        UninstallTarget(requireNotNull(it.candidate), requireNotNull(it.version))
                    }
                }.takeIf(List<UninstallTarget>::isNotEmpty)
                ?.let(SdkmanTransaction::BatchUninstall)
        }

        is SdkmanTransaction.SetDefault -> {
            commands.singleOrNull()?.toSingleTransaction()
        }

        is SdkmanTransaction.CleanLocalOnly -> {
            val candidate =
                commands.mapNotNull(PlannedSdkmanCommand::candidate).distinct().singleOrNull()
                    ?: return null
            SdkmanTransaction.CleanLocalOnly(
                candidate = candidate,
                versions = commands.mapNotNull(PlannedSdkmanCommand::version),
            )
        }

        SdkmanTransaction.RefreshMetadata -> {
            if (commands.singleOrNull()?.action == SdkmanCommandAction.UpdateMetadata) this else null
        }

        SdkmanTransaction.SelfUpdate -> {
            if (commands.singleOrNull()?.action == SdkmanCommandAction.SelfUpdate) this else null
        }
    }
}

private fun PlannedSdkmanCommand.toSingleTransaction(): SdkmanTransaction? =
    when (action) {
        SdkmanCommandAction.Install -> {
            SdkmanTransaction.Install(requireNotNull(candidate), requireNotNull(version))
        }

        SdkmanCommandAction.Uninstall -> {
            SdkmanTransaction.Uninstall(requireNotNull(candidate), requireNotNull(version))
        }

        SdkmanCommandAction.SetDefault -> {
            SdkmanTransaction.SetDefault(requireNotNull(candidate), requireNotNull(version))
        }

        SdkmanCommandAction.UpdateMetadata -> {
            SdkmanTransaction.RefreshMetadata
        }

        SdkmanCommandAction.SelfUpdate -> {
            SdkmanTransaction.SelfUpdate
        }
    }

data class JournalExportResult(
    val path: String,
    val exportedEntries: Int,
)

fun List<OperationJournalEntry>.searchOperationJournal(query: String): List<OperationJournalEntry> {
    if (query.isBlank()) return this
    return filter { entry ->
        buildString {
            append(entry.transaction.title)
            append(' ')
            append(entry.status.label)
            append(' ')
            append(entry.outcome.orEmpty())
            entry.transaction.commands.forEach { command ->
                append(' ')
                append(command.action.label)
                append(' ')
                append(command.candidate.orEmpty())
                append(' ')
                append(command.version.orEmpty())
            }
        }.contains(query, ignoreCase = true)
    }
}
