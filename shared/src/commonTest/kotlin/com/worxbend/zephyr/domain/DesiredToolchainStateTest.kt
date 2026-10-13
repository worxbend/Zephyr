package com.worxbend.zephyr.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesiredToolchainStateTest {
    @Test
    fun driftIsDeterministicAndRemediationNeverRemovesExtraVersions() {
        val desired =
            DesiredToolchainState(
                sourceKind = DesiredStateSourceKind.Snapshot,
                sourceLabel = "Release baseline",
                candidates =
                    listOf(
                        DesiredCandidateState("java", "21-tem", listOf("17-tem", "21-tem")),
                        DesiredCandidateState("gradle", "9.0", listOf("9.0")),
                    ),
            )
        val current =
            listOf(
                candidate(
                    "java",
                    default = "17-tem",
                    versions =
                        listOf(
                            CandidateVersion("17-tem", true, true, RemoteAvailability.Available),
                            CandidateVersion("11-tem", true, false, RemoteAvailability.Available),
                        ),
                ),
                candidate(
                    "kotlin",
                    default = "2.2",
                    versions = listOf(CandidateVersion("2.2", true, true, RemoteAvailability.Available)),
                ),
            )

        val drift = calculateDesiredStateDrift(desired, current)

        assertEquals(
            listOf(InstallTarget("gradle", "9.0"), InstallTarget("java", "21-tem")),
            drift.missingVersions,
        )
        assertEquals(
            listOf(InstallTarget("gradle", "9.0"), InstallTarget("java", "21-tem")),
            drift.defaultChanges,
        )
        assertEquals(
            listOf(InstallTarget("java", "11-tem"), InstallTarget("kotlin", "2.2")),
            drift.extraInstalledVersions,
        )
        assertTrue(drift.remediationCommands.none { it.action == SdkmanCommandAction.Uninstall })
        assertEquals(
            listOf(
                SdkmanCommandAction.Install,
                SdkmanCommandAction.Install,
                SdkmanCommandAction.SetDefault,
                SdkmanCommandAction.SetDefault,
            ),
            drift.remediationCommands.map(PlannedSdkmanCommand::action),
        )
    }

    @Test
    fun reportsDesiredVersionsThatAreVerifiedLocalOnly() {
        val desired =
            DesiredToolchainState(
                sourceKind = DesiredStateSourceKind.Profile,
                sourceLabel = "Legacy",
                candidates = listOf(DesiredCandidateState("java", "17-tem", listOf("17-tem"))),
            )
        val current =
            listOf(
                candidate(
                    "java",
                    default = "17-tem",
                    versions =
                        listOf(
                            CandidateVersion("17-tem", true, true, RemoteAvailability.LocalOnly),
                        ),
                ),
            )

        val drift = calculateDesiredStateDrift(desired, current)

        assertTrue(drift.isAligned)
        assertEquals(listOf(InstallTarget("java", "17-tem")), drift.localOnlyDesiredVersions)
    }

    private fun candidate(
        name: String,
        default: String,
        versions: List<CandidateVersion>,
    ) = Candidate(
        name = name,
        displayName = name,
        kind = if (name == "java") CandidateKind.Jdk else CandidateKind.Sdk,
        installedVersions = versions,
        defaultVersion = default,
        hasLocalOnlyVersions = versions.any(CandidateVersion::isConfirmedLocalOnly),
        localOnlyVersionCount = versions.count(CandidateVersion::isConfirmedLocalOnly),
        localOnlyVersions = versions.filter(CandidateVersion::isConfirmedLocalOnly).map(CandidateVersion::version),
    )
}
