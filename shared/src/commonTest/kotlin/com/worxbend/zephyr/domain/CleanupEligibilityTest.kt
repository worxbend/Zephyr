package com.worxbend.zephyr.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CleanupEligibilityTest {
    private val candidate = Candidate(
        name = "gradle", displayName = "Gradle", kind = CandidateKind.Sdk,
        installedVersions = listOf("7.6", "8.0", "8.1").map {
            CandidateVersion(it, true, it == "8.1", RemoteAvailability.LocalOnly)
        },
        defaultVersion = "8.1", hasLocalOnlyVersions = true, localOnlyVersionCount = 3,
        localOnlyVersions = listOf("7.6", "8.0", "8.1"), remoteEvidence = RemoteEvidenceState.LiveComplete,
    )

    @Test
    fun defaultsAndProtectionAreExcludedWithoutRejectingOtherVersions() {
        val result = cleanupEligibility(candidate, setOf(ProtectedVersion("gradle", "8.0")), true)
        assertEquals(listOf("7.6"), result.eligibleVersions)
        assertEquals(CleanupBlockReason.Protected, result.blockedReasons["8.0"])
        assertEquals(CleanupBlockReason.Default, result.blockedReasons["8.1"])
    }

    @Test
    fun liveDetailMetadataAloneCannotAdmitCleanup() {
        val result = cleanupEligibility(candidate, emptySet(), false)
        assertTrue(result.eligibleVersions.isEmpty())
        assertTrue(result.blockedReasons.values.all { it == CleanupBlockReason.Unverified })
    }

    @Test
    fun staleOrPartialEvidenceNeverAdmitsCleanup() {
        for (evidence in listOf(RemoteEvidenceState.Unknown, RemoteEvidenceState.LivePartial)) {
            assertTrue(cleanupEligibility(candidate.copy(remoteEvidence = evidence), emptySet(), true).eligibleVersions.isEmpty())
        }
    }

    @Test
    fun absentAndRemoteAvailableVersionsAreNotCleanableEvenWithInconsistentLocalOnlyList() {
        val result = cleanupEligibility(
            candidate.copy(installedVersions = listOf(CandidateVersion("7.6", true, false, RemoteAvailability.Available))),
            emptySet(), true,
        )
        assertTrue(result.eligibleVersions.isEmpty())
    }
}
