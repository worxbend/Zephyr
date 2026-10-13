package com.worxbend.zephyr.data

import com.worxbend.zephyr.domain.Candidate
import com.worxbend.zephyr.domain.CandidateKind
import com.worxbend.zephyr.domain.CandidateVersion
import com.worxbend.zephyr.domain.PlannedSdkmanCommand
import com.worxbend.zephyr.domain.RemoteAvailability
import com.worxbend.zephyr.domain.SdkmanCommandAction
import kotlin.test.Test
import kotlin.test.assertEquals

class EnvironmentSnapshotServiceTest {
    @Test
    fun captureAndRenderingAreDeterministic() {
        val candidates =
            listOf(
                candidate("java", "21-tem", "21-tem", "17-tem"),
                candidate("gradle", "8.14", "8.14"),
            )
        val first = captureEnvironmentSnapshot(candidates, 42)
        val second = captureEnvironmentSnapshot(candidates.reversed(), 42)

        assertEquals(renderEnvironmentSnapshot(first), renderEnvironmentSnapshot(second))
        assertEquals(first, parseEnvironmentSnapshot(renderEnvironmentSnapshot(first)))
    }

    @Test
    fun diffIgnoresInputOrderAndReportsVersionAndDefaultChanges() {
        val previous =
            captureEnvironmentSnapshot(
                listOf(candidate("java", "17-tem", "21-tem", "17-tem")),
                1,
            )
        val current =
            captureEnvironmentSnapshot(
                listOf(candidate("java", "21-tem", "22-tem", "21-tem")),
                2,
            )

        assertEquals(
            listOf(
                SnapshotCandidateDiff(
                    candidate = "java",
                    previousDefault = "17-tem",
                    currentDefault = "21-tem",
                    addedVersions = listOf("22-tem"),
                    removedVersions = listOf("17-tem"),
                ),
            ),
            diffEnvironmentSnapshots(previous, current),
        )
    }

    @Test
    fun restorePlanInstallsMissingVersionsBeforeChangingDefaultsAndCanResume() {
        val snapshot =
            captureEnvironmentSnapshot(
                listOf(
                    candidate("java", "21-tem", "17-tem", "21-tem"),
                    candidate("gradle", "8.14", "8.14"),
                ),
                1,
            )
        val current = listOf(candidate("java", "17-tem", "17-tem"))

        assertEquals(
            listOf(
                PlannedSdkmanCommand(SdkmanCommandAction.Install, "gradle", "8.14"),
                PlannedSdkmanCommand(SdkmanCommandAction.Install, "java", "21-tem"),
                PlannedSdkmanCommand(SdkmanCommandAction.SetDefault, "gradle", "8.14"),
                PlannedSdkmanCommand(SdkmanCommandAction.SetDefault, "java", "21-tem"),
            ),
            planSnapshotRestore(snapshot, current),
        )

        val afterPartialRun =
            listOf(
                candidate("java", "17-tem", "17-tem", "21-tem"),
                candidate("gradle", null, "8.14"),
            )
        assertEquals(
            listOf(
                PlannedSdkmanCommand(SdkmanCommandAction.SetDefault, "gradle", "8.14"),
                PlannedSdkmanCommand(SdkmanCommandAction.SetDefault, "java", "21-tem"),
            ),
            planSnapshotRestore(snapshot, afterPartialRun),
        )
    }

    private fun candidate(
        name: String,
        default: String?,
        vararg versions: String,
    ) = Candidate(
        name = name,
        displayName = name,
        kind = if (name == "java") CandidateKind.Jdk else CandidateKind.Sdk,
        installedVersions =
            versions.map {
                CandidateVersion(
                    it,
                    isInstalled = true,
                    isDefault = it == default,
                    remoteAvailability = RemoteAvailability.Available,
                )
            },
        defaultVersion = default,
        hasLocalOnlyVersions = false,
        localOnlyVersionCount = 0,
        localOnlyVersions = emptyList(),
    )
}
