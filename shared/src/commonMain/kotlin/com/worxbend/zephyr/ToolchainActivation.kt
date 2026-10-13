package com.worxbend.zephyr

import com.worxbend.zephyr.domain.Candidate
import com.worxbend.zephyr.domain.InstallTarget
import com.worxbend.zephyr.domain.PlannedSdkmanCommand
import com.worxbend.zephyr.domain.SdkmanCommandAction

internal fun planToolchainActivation(
    targets: List<InstallTarget>,
    currentCandidates: List<Candidate>,
): List<PlannedSdkmanCommand> {
    val currentByName = currentCandidates.associateBy(Candidate::name)
    val desired =
        targets
            .distinctBy(InstallTarget::candidate)
            .sortedBy(InstallTarget::candidate)
    val installs =
        desired.mapNotNull { target ->
            val installed =
                currentByName[target.candidate]
                    ?.installedVersions
                    .orEmpty()
                    .any { it.isInstalled && it.version == target.version }
            if (installed) {
                null
            } else {
                PlannedSdkmanCommand(SdkmanCommandAction.Install, target.candidate, target.version)
            }
        }
    val defaults =
        desired.mapNotNull { target ->
            if (currentByName[target.candidate]?.defaultVersion == target.version) {
                null
            } else {
                PlannedSdkmanCommand(SdkmanCommandAction.SetDefault, target.candidate, target.version)
            }
        }
    return installs + defaults
}
