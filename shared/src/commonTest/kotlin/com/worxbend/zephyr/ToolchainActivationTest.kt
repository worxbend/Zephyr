package com.worxbend.zephyr

import com.worxbend.zephyr.domain.Candidate
import com.worxbend.zephyr.domain.CandidateKind
import com.worxbend.zephyr.domain.CandidateVersion
import com.worxbend.zephyr.domain.InstallTarget
import com.worxbend.zephyr.domain.PlannedSdkmanCommand
import com.worxbend.zephyr.domain.SdkmanCommandAction
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ToolchainActivationTest {
    @Test
    fun installsEveryMissingTargetBeforeChangingDefaultsAndNeverRemovesVersions() {
        val current =
            listOf(
                candidate("java", default = "17-tem", installed = listOf("17-tem")),
                candidate("gradle", default = "8.0", installed = listOf("8.0", "9.0")),
            )

        val plan =
            planToolchainActivation(
                targets =
                    listOf(
                        InstallTarget("java", "21-tem"),
                        InstallTarget("gradle", "9.0"),
                    ),
                currentCandidates = current,
            )

        assertEquals(
            listOf(
                PlannedSdkmanCommand(SdkmanCommandAction.Install, "java", "21-tem"),
                PlannedSdkmanCommand(SdkmanCommandAction.SetDefault, "gradle", "9.0"),
                PlannedSdkmanCommand(SdkmanCommandAction.SetDefault, "java", "21-tem"),
            ),
            plan,
        )
        assertTrue(plan.none { it.action == SdkmanCommandAction.Uninstall })
    }

    @Test
    fun activationIsIdempotentWhenAllTargetsAreAlreadyDefaults() {
        val current =
            listOf(
                candidate("java", default = "21-tem", installed = listOf("17-tem", "21-tem")),
                candidate("gradle", default = "9.0", installed = listOf("9.0")),
            )

        assertEquals(
            emptyList(),
            planToolchainActivation(
                listOf(InstallTarget("java", "21-tem"), InstallTarget("gradle", "9.0")),
                current,
            ),
        )
    }

    @Test
    fun typedActivationBoundaryRejectsDefaultBeforeInstall() {
        assertFailsWith<IllegalArgumentException> {
            com.worxbend.zephyr.domain.SdkmanTransaction.ToolchainActivation(
                "Invalid order",
                listOf(
                    PlannedSdkmanCommand(SdkmanCommandAction.SetDefault, "java", "21-tem"),
                    PlannedSdkmanCommand(SdkmanCommandAction.Install, "gradle", "9.0"),
                ),
            )
        }
    }

    private fun candidate(
        name: String,
        default: String,
        installed: List<String>,
    ) = Candidate(
        name = name,
        displayName = name,
        kind = if (name == "java") CandidateKind.Jdk else CandidateKind.Sdk,
        installedVersions =
            installed.map { version ->
                CandidateVersion(version, true, version == default, true)
            },
        defaultVersion = default,
        hasLocalOnlyVersions = false,
        localOnlyVersionCount = 0,
        localOnlyVersions = emptyList(),
    )
}
