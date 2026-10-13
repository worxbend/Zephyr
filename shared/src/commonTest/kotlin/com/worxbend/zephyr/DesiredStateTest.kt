package com.worxbend.zephyr

import com.worxbend.zephyr.data.EnvironmentSnapshot
import com.worxbend.zephyr.data.SnapshotCandidate
import com.worxbend.zephyr.domain.DesiredStateSourceKind
import com.worxbend.zephyr.domain.InstallTarget
import com.worxbend.zephyr.settings.ToolchainProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class DesiredStateTest {
    @Test
    fun profileBaselineStoresOnlyValidatedToolIdentifiers() {
        val desired =
            desiredStateFromProfile(
                ToolchainProfile(
                    "Backend\nteam",
                    listOf(InstallTarget("java", "21-tem"), InstallTarget("gradle", "9.0")),
                ),
            )

        assertEquals(DesiredStateSourceKind.Profile, desired.sourceKind)
        assertEquals("Backend team", desired.sourceLabel)
        assertEquals(listOf("gradle", "java"), desired.candidates.map { it.candidate })
        assertFalse(desired.sourceLabel.contains('/'))
    }

    @Test
    fun snapshotBaselineUsesCapturedIdentifiersAndTimestampNotSourcePath() {
        val desired =
            desiredStateFromSnapshot(
                EnvironmentSnapshot(
                    capturedAtEpochMillis = 1234,
                    candidates =
                        listOf(
                            SnapshotCandidate("java", "21-tem", listOf("17-tem", "21-tem")),
                        ),
                ),
            )

        assertEquals(DesiredStateSourceKind.Snapshot, desired.sourceKind)
        assertEquals("Snapshot 1234", desired.sourceLabel)
        assertEquals(listOf("17-tem", "21-tem"), desired.candidates.single().installedVersions)
    }
}
