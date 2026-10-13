package com.worxbend.zephyr

import com.worxbend.zephyr.domain.Candidate
import com.worxbend.zephyr.domain.CandidateKind
import com.worxbend.zephyr.domain.CandidateVersion
import com.worxbend.zephyr.domain.ProtectedVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class BatchUninstallTest {
    @Test
    fun defaultAndProtectedVersionsAreNotEligible() {
        val candidate =
            Candidate(
                name = "gradle",
                displayName = "Gradle",
                kind = CandidateKind.Sdk,
                installedVersions =
                    listOf(
                        CandidateVersion("8.10", true, true, true),
                        CandidateVersion("8.11", true, false, true),
                        CandidateVersion("8.12", true, false, true),
                    ),
                defaultVersion = "8.10",
                hasLocalOnlyVersions = false,
                localOnlyVersionCount = 0,
                localOnlyVersions = emptyList(),
            )

        val items =
            uninstallSelectionItems(
                listOf(candidate),
                setOf(ProtectedVersion("gradle", "8.11")),
            )

        assertEquals("Default version", items.single { it.target.version == "8.10" }.blockedReason)
        assertEquals("Protected version", items.single { it.target.version == "8.11" }.blockedReason)
        assertNull(items.single { it.target.version == "8.12" }.blockedReason)
    }
}
