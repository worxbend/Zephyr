package com.worxbend.zephyr.domain

enum class BatchItemStatus(
    val label: String,
) {
    Pending("Pending"),
    Running("Running"),
    Succeeded("Succeeded"),
    Failed("Failed"),
    Skipped("Skipped"),
}

data class BatchInstallProgress(
    val target: InstallTarget,
    val status: BatchItemStatus = BatchItemStatus.Pending,
    val outcome: String? = null,
)

data class BatchUninstallProgress(
    val target: UninstallTarget,
    val status: BatchItemStatus = BatchItemStatus.Pending,
    val outcome: String? = null,
)

data class SnapshotRestoreProgress(
    val command: PlannedSdkmanCommand,
    val status: BatchItemStatus = BatchItemStatus.Pending,
    val outcome: String? = null,
)
