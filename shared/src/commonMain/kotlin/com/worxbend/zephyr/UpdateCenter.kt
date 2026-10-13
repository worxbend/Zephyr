package com.worxbend.zephyr

import com.worxbend.zephyr.domain.Candidate
import com.worxbend.zephyr.domain.CandidateCatalogItem
import com.worxbend.zephyr.domain.CandidateKind
import com.worxbend.zephyr.settings.UpdateNotificationPolicy

internal enum class StableTargetState(
    val label: String,
) {
    Missing("Install required"),
    InstalledInactive("Installed · activation required"),
    Active("Active"),
}

internal data class CandidateUpdate(
    val candidate: String,
    val displayName: String,
    val kind: CandidateKind,
    val currentVersion: String?,
    val targetVersion: String,
    val state: StableTargetState,
)

internal data class UpdateNotification(
    val title: String,
    val message: String,
    val signature: String,
)

internal fun updateNotification(
    policy: UpdateNotificationPolicy,
    candidates: List<Candidate>,
    catalog: List<CandidateCatalogItem>,
): UpdateNotification? {
    if (policy == UpdateNotificationPolicy.Off || catalog.isEmpty()) return null
    val updates = availableCandidateUpdates(candidates, catalog)
    val signature =
        updates
            .joinToString("|") { "${it.candidate}:${it.targetVersion}:${it.state.name}" }
            .ifEmpty { "current" }
    if (updates.isEmpty()) {
        return if (policy == UpdateNotificationPolicy.AllChecks) {
            UpdateNotification(
                title = "Zephyr update check",
                message = "Your loaded SDKMAN toolchain is current.",
                signature = signature,
            )
        } else {
            null
        }
    }
    val missingCount = updates.count { it.state == StableTargetState.Missing }
    val inactiveCount = updates.count { it.state == StableTargetState.InstalledInactive }
    return UpdateNotification(
        title =
            when {
                missingCount == 0 && inactiveCount == 1 -> "1 stable update ready to activate"
                missingCount == 0 -> "$inactiveCount stable updates ready to activate"
                inactiveCount == 0 && missingCount == 1 -> "1 toolchain update available"
                inactiveCount == 0 -> "$missingCount toolchain updates available"
                else -> "${updates.size} stable updates need action"
            },
        message =
            updates
                .take(3)
                .joinToString(", ") {
                    val action =
                        when (it.state) {
                            StableTargetState.Missing -> "install and activate"
                            StableTargetState.InstalledInactive -> "activate installed"
                            StableTargetState.Active -> "active"
                        }
                    "${it.displayName} ${it.targetVersion} ($action)"
                }.let { summary ->
                    if (updates.size > 3) "$summary, and ${updates.size - 3} more" else summary
                },
        signature = signature,
    )
}

internal fun availableCandidateUpdates(
    candidates: List<Candidate>,
    catalog: List<CandidateCatalogItem>,
): List<CandidateUpdate> = stableCandidateTargets(candidates, catalog).filter { it.state != StableTargetState.Active }

internal fun stableCandidateTargets(
    candidates: List<Candidate>,
    catalog: List<CandidateCatalogItem>,
): List<CandidateUpdate> {
    val catalogByName = catalog.associateBy(CandidateCatalogItem::name)
    return candidates
        .mapNotNull { candidate ->
            val target =
                catalogByName[candidate.name]?.stableVersion?.takeIf(String::isNotBlank)
                    ?: return@mapNotNull null
            val installed =
                candidate.installedVersions
                    .asSequence()
                    .filter { it.isInstalled }
                    .map { it.version }
                    .toSet()
            val state =
                when {
                    candidate.defaultVersion == target -> StableTargetState.Active
                    target in installed -> StableTargetState.InstalledInactive
                    else -> StableTargetState.Missing
                }
            CandidateUpdate(
                candidate = candidate.name,
                displayName = candidate.displayName,
                kind = candidate.kind,
                currentVersion = candidate.defaultVersion ?: installed.firstOrNull(),
                targetVersion = target,
                state = state,
            )
        }.sortedWith(
            compareBy<CandidateUpdate> { it.kind.ordinal }
                .thenBy { it.displayName.lowercase() },
        )
}
