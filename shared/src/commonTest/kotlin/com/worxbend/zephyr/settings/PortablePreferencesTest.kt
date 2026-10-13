package com.worxbend.zephyr.settings

import com.worxbend.zephyr.domain.DesiredCandidateState
import com.worxbend.zephyr.domain.DesiredStateSourceKind
import com.worxbend.zephyr.domain.DesiredToolchainState
import com.worxbend.zephyr.domain.InstallTarget
import kotlin.test.Test
import kotlin.test.assertEquals

class PortablePreferencesTest {
    @Test
    fun formatRoundTripsDeterministicallyAndApplyPreservesMachineState() {
        val source =
            AppSettings(
                themePreference = ThemePreference.Dark,
                favoriteCandidates = setOf("gradle", "kotlin"),
                toolchainProfiles =
                    listOf(
                        ToolchainProfile("Backend\tJVM", listOf(InstallTarget("java", "21-tem"))),
                    ),
                projectWorkspaces =
                    listOf(
                        ProjectWorkspaceReference("/private/work/backend/.sdkmanrc", "Backend"),
                    ),
                desiredToolchainState =
                    DesiredToolchainState(
                        sourceKind = DesiredStateSourceKind.Profile,
                        sourceLabel = "Source profile",
                        candidates = listOf(DesiredCandidateState("java", "21-tem", listOf("21-tem"))),
                    ),
                savedJdkFilters = listOf(SavedJdkFilter("Local", "17\nlts", "Installed", "tem", "Version")),
                navigationWidthDp = 300,
            )
        val portable = source.portablePreferences()
        val rendered = renderPortablePreferences(portable)
        val parsed = parsePortablePreferences(rendered)

        assertEquals(portable, parsed)
        assertEquals(rendered, renderPortablePreferences(parsed))

        val machineState =
            AppSettings(
                localOnlyObservations = listOf(LocalOnlyObservation("java", "old-local", 123)),
                projectWorkspaces =
                    listOf(
                        ProjectWorkspaceReference("/machine-only/.sdkmanrc", "Local"),
                    ),
                desiredToolchainState =
                    DesiredToolchainState(
                        sourceKind = DesiredStateSourceKind.Snapshot,
                        sourceLabel = "Machine baseline",
                        candidates = listOf(DesiredCandidateState("gradle", "9.0", listOf("9.0"))),
                    ),
            ).applyPortablePreferences(parsed)
        assertEquals(source.themePreference, machineState.themePreference)
        assertEquals(listOf(LocalOnlyObservation("java", "old-local", 123)), machineState.localOnlyObservations)
        assertEquals(
            listOf(ProjectWorkspaceReference("/machine-only/.sdkmanrc", "Local")),
            machineState.projectWorkspaces,
        )
        assertEquals(DesiredStateSourceKind.Snapshot, machineState.desiredToolchainState?.sourceKind)
        assertEquals("Machine baseline", machineState.desiredToolchainState?.sourceLabel)
        assertEquals(false, rendered.contains("/private/work/backend"))
    }
}
