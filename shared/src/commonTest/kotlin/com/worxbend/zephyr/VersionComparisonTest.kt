package com.worxbend.zephyr

import com.worxbend.zephyr.domain.Candidate
import com.worxbend.zephyr.domain.CandidateKind
import com.worxbend.zephyr.domain.CandidateVersion
import com.worxbend.zephyr.domain.ProtectedVersion
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VersionComparisonTest {
    @Test
    fun derivesVendorAndSafetyStatusForSelectedVersions() {
        val candidate =
            Candidate(
                name = "java",
                displayName = "Java",
                kind = CandidateKind.Jdk,
                installedVersions =
                    listOf(
                        CandidateVersion("21.0.5-tem", true, true, true),
                        CandidateVersion("17.0.12-zulu", true, false, false),
                    ),
                defaultVersion = "21.0.5-tem",
                hasLocalOnlyVersions = true,
                localOnlyVersionCount = 1,
                localOnlyVersions = listOf("17.0.12-zulu"),
            )

        val rows =
            candidate.comparisonRows(
                setOf("21.0.5-tem", "17.0.12-zulu"),
                setOf(ProtectedVersion("java", "17.0.12-zulu")),
            )

        assertEquals("Eclipse Temurin", rows.first().vendor)
        assertTrue(rows.first().default)
        assertTrue(rows.last().localOnly)
        assertTrue(rows.last().protected)
    }
}
