package com.worxbend.zephyr.data

import com.worxbend.zephyr.domain.OperationJournalEntry
import com.worxbend.zephyr.domain.OperationStatus
import com.worxbend.zephyr.domain.SdkmanTransaction
import java.nio.file.Files
import kotlin.io.path.readText
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmOperationJournalExporterTest {
    @Test
    fun redactsBoundaryPathsInLiveCsvFields() = runBlocking {
        val directory = operationTestDirectory("zephyr-boundary-csv-")
        try {
            val entries = sensitivePathBoundaryFixtures.mapIndexed { index, path ->
                OperationJournalEntry(
                    index + 1L,
                    SdkmanTransaction.ToolchainActivation(
                        "Profile $path; activation",
                        listOf(com.worxbend.zephyr.domain.PlannedSdkmanCommand(com.worxbend.zephyr.domain.SdkmanCommandAction.Install, "java", "21-tem")),
                    ),
                    100,
                    outcome = "Failure $path; retry. See https://example.test/docs?next=/public/project",
                )
            }
            val result = JvmOperationJournalExporter({ directory }, { 100 }, { emptyList() }).export(entries)
            val csv = java.nio.file.Path.of(result.path).readText()
            assertEquals(entries.size, result.exportedEntries)
            assertFalse(csv.contains("customer-private"), csv)
            assertFalse(csv.contains("Reilly"), csv)
            assertTrue(csv.contains("<redacted-path>"), csv)
            assertTrue(csv.contains("Install java 21-tem"), csv)
            assertTrue(csv.contains("https://example.test/docs?next=/public/project"), csv)
            assertTrue(csv.contains("\"\"<redacted-path>\"\""), csv)
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun redactsExternalPathsInLiveTitlesAndOutcomes() = runBlocking {
        val directory = operationTestDirectory("zephyr-external-export-")
        try {
            val entry = OperationJournalEntry(
                9,
                SdkmanTransaction.ToolchainActivation(
                    "Customer /srv/secret-client/profile",
                    listOf(com.worxbend.zephyr.domain.PlannedSdkmanCommand(com.worxbend.zephyr.domain.SdkmanCommandAction.Install, "java", "21-tem")),
                ),
                100,
                outcome = "Failure at C:\\Customers\\secret-client\\build and /opt/secret-outcome/file",
            )
            val result = JvmOperationJournalExporter({ directory }, { 100 }, { emptyList() }).export(listOf(entry))
            val csv = java.nio.file.Path.of(result.path).readText()
            assertFalse(csv.contains("secret-client"))
            assertFalse(csv.contains("secret-outcome"))
            assertTrue(csv.contains("<redacted-path>"))
            val withStepOutcome = entry.copy(steps = entry.steps.map {
                it.copy(outcome = "Failure at /mnt/secret-step/project")
            })
            val reloaded = parseOperationLedger(renderOperationLedger(listOf(withStepOutcome))).single()
            assertFalse(reloaded.steps.single().outcome.orEmpty().contains("secret-step"))
            assertFalse(reloaded.transaction.title.contains("secret-client"))
            assertFalse(reloaded.outcome.orEmpty().contains("secret-outcome"))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun exportsSearchableSessionHistoryAsEscapedCsv() = runBlocking {
        val directory = operationTestDirectory("zephyr-journal-test-")
        try {
            val exporter = JvmOperationJournalExporter(
                outputDirectory = { directory },
                clock = { 1_721_234_567_000L },
                sensitivePaths = { listOf("/synthetic/home") },
            )
            val entries = listOf(
                OperationJournalEntry(
                    id = 1,
                    transaction = SdkmanTransaction.Install("gradle", "9.0.0"),
                    startedAtEpochMillis = 1_721_234_560_000L,
                    completedAtEpochMillis = 1_721_234_561_000L,
                    status = OperationStatus.Succeeded,
                    outcome = "Installed, with metadata at /synthetic/home/.sdkman",
                ),
            )

            val result = exporter.export(entries)
            val destination = java.nio.file.Path.of(result.path)
            val csv = destination.readText()

            assertEquals(1, result.exportedEntries)
            assertTrue(Files.isRegularFile(destination))
            assertTrue(csv.startsWith("started,completed,status,operation,commands,outcome"))
            assertTrue(csv.contains("\"Succeeded\""))
            assertTrue(csv.contains("\"Install gradle 9.0.0\""))
            assertTrue(csv.contains("\"Installed, with metadata at <redacted-path>\""))
            assertFalse(csv.contains("/synthetic/home"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
