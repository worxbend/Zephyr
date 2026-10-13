package com.worxbend.zephyr.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class OperationJournalTest {
    private val entries =
        listOf(
            OperationJournalEntry(
                id = 2,
                transaction = SdkmanTransaction.Uninstall("java", "17.0.1-tem"),
                startedAtEpochMillis = 200,
                completedAtEpochMillis = 210,
                status = OperationStatus.Failed,
                outcome = "Network unavailable",
            ),
            OperationJournalEntry(
                id = 1,
                transaction = SdkmanTransaction.Install("gradle", "9.0.0"),
                startedAtEpochMillis = 100,
                completedAtEpochMillis = 110,
                status = OperationStatus.Succeeded,
                outcome = "Installed",
            ),
        )

    @Test
    fun searchesActionsCandidatesVersionsStatusesAndOutcomes() {
        assertEquals(listOf(2L), entries.searchOperationJournal("uninstall").map { it.id })
        assertEquals(listOf(1L), entries.searchOperationJournal("gradle").map { it.id })
        assertEquals(listOf(2L), entries.searchOperationJournal("17.0.1").map { it.id })
        assertEquals(listOf(1L), entries.searchOperationJournal("succeeded").map { it.id })
        assertEquals(listOf(2L), entries.searchOperationJournal("network").map { it.id })
        assertEquals(entries, entries.searchOperationJournal(""))
    }

    @Test
    fun resumesFailedUpdateInstallTogetherWithItsSkippedDefault() {
        val transaction =
            SdkmanTransaction.UpdateActivation(
                listOf(UpdateActivationTarget("gradle", "8.14", true)),
            )
        val entry =
            OperationJournalEntry(
                id = 3,
                transaction = transaction,
                startedAtEpochMillis = 300,
                status = OperationStatus.Failed,
                steps =
                    listOf(
                        OperationStep(0, transaction.commands[0], OperationStepStatus.Failed),
                        OperationStep(1, transaction.commands[1], OperationStepStatus.Skipped),
                    ),
            )

        assertEquals(
            transaction,
            assertIs<SdkmanTransaction.UpdateActivation>(entry.resumeTransaction()),
        )
    }
}
