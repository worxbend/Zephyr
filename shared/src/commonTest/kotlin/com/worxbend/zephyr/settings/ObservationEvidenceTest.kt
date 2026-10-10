package com.worxbend.zephyr.settings

import com.worxbend.zephyr.domain.ProtectedVersion
import kotlin.test.Test
import kotlin.test.assertEquals

class ObservationEvidenceTest {
    @Test
    fun emptyUnauditedInventoryPreservesFirstSeenHistory() {
        val settings = AppSettings(
            cleanupGracePeriod = CleanupGracePeriod.SevenDays,
            localOnlyObservations = listOf(LocalOnlyObservation("gradle", "7.6", 1_000L)),
        )

        assertEquals(settings, settings.reconcileLocalOnlyObservations(emptySet(), 2_000L))
    }

    @Test
    fun verifiedResolutionClearsOnlyTheAuditedCandidate() {
        val settings = AppSettings(
            cleanupGracePeriod = CleanupGracePeriod.SevenDays,
            localOnlyObservations = listOf(
                LocalOnlyObservation("gradle", "7.6", 1_000L),
                LocalOnlyObservation("java", "17-tem", 1_000L),
            ),
        )
        val next = settings.reconcileLocalOnlyObservations(emptySet(), 2_000L, setOf("java"))
        assertEquals(listOf(LocalOnlyObservation("gradle", "7.6", 1_000L)), next.localOnlyObservations)
    }

    @Test
    fun observationOfAnotherCandidateDoesNotEraseUnknownCandidateHistory() {
        val settings = AppSettings(
            cleanupGracePeriod = CleanupGracePeriod.SevenDays,
            localOnlyObservations = listOf(LocalOnlyObservation("gradle", "7.6", 1_000L)),
        )

        val next = settings.reconcileLocalOnlyObservations(setOf(ProtectedVersion("java", "17-tem")), 2_000L)
        assertEquals(
            listOf(LocalOnlyObservation("gradle", "7.6", 1_000L), LocalOnlyObservation("java", "17-tem", 2_000L)),
            next.localOnlyObservations,
        )
    }
}
