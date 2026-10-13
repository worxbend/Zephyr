package com.worxbend.zephyr

import com.worxbend.zephyr.domain.Candidate
import com.worxbend.zephyr.domain.InstallTarget

internal enum class ProjectTargetStatus(
    val label: String,
) {
    Current("Current default"),
    DefaultChange("Default change required"),
    Install("Install required"),
}

internal data class ProjectTargetDiff(
    val target: InstallTarget,
    val status: ProjectTargetStatus,
)

internal enum class ProjectWorkspaceStatus(
    val label: String,
) {
    Ready("Ready"),
    DefaultsDiffer("Defaults differ"),
    Missing("Missing versions"),
}

internal fun projectWorkspaceStatus(diff: List<ProjectTargetDiff>): ProjectWorkspaceStatus =
    when {
        diff.any { it.status == ProjectTargetStatus.Install } -> ProjectWorkspaceStatus.Missing
        diff.any { it.status == ProjectTargetStatus.DefaultChange } -> ProjectWorkspaceStatus.DefaultsDiffer
        else -> ProjectWorkspaceStatus.Ready
    }

internal fun compareProjectToolchain(
    targets: List<InstallTarget>,
    candidates: List<Candidate>,
): List<ProjectTargetDiff> =
    targets.map { target ->
        val candidate = candidates.firstOrNull { it.name == target.candidate }
        val installed =
            candidate?.installedVersions?.any {
                it.isInstalled && it.version == target.version
            } == true
        ProjectTargetDiff(
            target = target,
            status =
                when {
                    candidate?.defaultVersion == target.version -> ProjectTargetStatus.Current
                    installed -> ProjectTargetStatus.DefaultChange
                    else -> ProjectTargetStatus.Install
                },
        )
    }
