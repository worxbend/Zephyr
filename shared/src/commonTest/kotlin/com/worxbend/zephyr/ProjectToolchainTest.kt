package com.worxbend.zephyr

import com.worxbend.zephyr.domain.Candidate
import com.worxbend.zephyr.domain.CandidateKind
import com.worxbend.zephyr.domain.CandidateVersion
import com.worxbend.zephyr.domain.InstallTarget
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectToolchainTest {
    @Test
    fun distinguishesCurrentInstalledAndMissingTargets() {
        val candidate =
            Candidate(
                name = "java",
                displayName = "Java",
                kind = CandidateKind.Jdk,
                installedVersions =
                    listOf(
                        CandidateVersion("21-tem", true, true, true),
                        CandidateVersion("17-tem", true, false, true),
                    ),
                defaultVersion = "21-tem",
                hasLocalOnlyVersions = false,
                localOnlyVersionCount = 0,
                localOnlyVersions = emptyList(),
            )

        val diff =
            compareProjectToolchain(
                listOf(
                    InstallTarget("java", "21-tem"),
                    InstallTarget("java", "17-tem"),
                    InstallTarget("gradle", "8.14"),
                ),
                listOf(candidate),
            )

        assertEquals(
            listOf(ProjectTargetStatus.Current, ProjectTargetStatus.DefaultChange, ProjectTargetStatus.Install),
            diff.map { it.status },
        )
        assertEquals(ProjectWorkspaceStatus.Missing, projectWorkspaceStatus(diff))
        assertEquals(
            ProjectWorkspaceStatus.DefaultsDiffer,
            projectWorkspaceStatus(diff.filterNot { it.status == ProjectTargetStatus.Install }),
        )
        assertEquals(
            ProjectWorkspaceStatus.Ready,
            projectWorkspaceStatus(diff.filter { it.status == ProjectTargetStatus.Current }),
        )
    }
}
