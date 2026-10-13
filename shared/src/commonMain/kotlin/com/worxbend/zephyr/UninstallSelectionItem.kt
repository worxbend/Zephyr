package com.worxbend.zephyr

import com.worxbend.zephyr.domain.Candidate
import com.worxbend.zephyr.domain.CandidateKind
import com.worxbend.zephyr.domain.ProtectedVersion
import com.worxbend.zephyr.domain.UninstallTarget

internal data class UninstallSelectionItem(
    val target: UninstallTarget,
    val displayName: String,
    val kind: CandidateKind,
    val blockedReason: String? = null,
)

internal fun uninstallSelectionItems(
    candidates: List<Candidate>,
    protectedVersions: Set<ProtectedVersion>,
): List<UninstallSelectionItem> =
    candidates
        .flatMap { candidate ->
            candidate.installedVersions
                .filter { it.isInstalled }
                .map { version ->
                    val protected = ProtectedVersion(candidate.name, version.version) in protectedVersions
                    UninstallSelectionItem(
                        target = UninstallTarget(candidate.name, version.version),
                        displayName = candidate.displayName,
                        kind = candidate.kind,
                        blockedReason =
                            when {
                                version.isDefault || candidate.defaultVersion == version.version -> "Default version"
                                protected -> "Protected version"
                                else -> null
                            },
                    )
                }
        }.sortedWith(
            compareBy<UninstallSelectionItem> { it.kind.ordinal }
                .thenBy { it.displayName.lowercase() }
                .thenBy { it.target.version },
        )
