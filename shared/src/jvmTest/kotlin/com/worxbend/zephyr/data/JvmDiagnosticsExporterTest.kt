package com.worxbend.zephyr.data

import com.worxbend.zephyr.domain.ConnectivityState
import com.worxbend.zephyr.domain.ConnectivityStatus
import com.worxbend.zephyr.domain.ConnectivityDiagnostic
import com.worxbend.zephyr.domain.ConnectivityOutcome
import com.worxbend.zephyr.domain.ConnectivityRouteKind
import com.worxbend.zephyr.domain.DiagnosticsSnapshot
import com.worxbend.zephyr.domain.IntegrityCheck
import com.worxbend.zephyr.domain.IntegrityCheckId
import com.worxbend.zephyr.domain.IntegrityStatus
import com.worxbend.zephyr.domain.OperationJournalEntry
import com.worxbend.zephyr.domain.OperationStatus
import com.worxbend.zephyr.domain.SdkmanStatus
import com.worxbend.zephyr.domain.SdkmanTransaction
import java.nio.file.Files
import kotlin.io.path.readText
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmDiagnosticsExporterTest {
    @Test
    fun redactsBoundaryPathsInSupportIntegrityAndOperationFields() = runBlocking {
        val directory = operationTestDirectory("zephyr-boundary-support-")
        try {
            val snapshot = DiagnosticsSnapshot(
                generatedAtEpochMillis = 100,
                sdkmanStatus = SdkmanStatus(true, "/synthetic/sdkman", "SDKMAN 5.20"),
                connectivityStatus = ConnectivityStatus.from(
                    ConnectivityDiagnostic(ConnectivityRouteKind.Direct, 100, 42, ConnectivityOutcome.Online),
                ),
                integrityChecks = sensitivePathBoundaryFixtures.map { path ->
                    IntegrityCheck(IntegrityCheckId.RequiredScripts, "Scripts $path; check", IntegrityStatus.Failed, "Failure $path; retry.")
                },
                installedCandidates = 1,
                installedVersions = 1,
                localOnlyVersions = 0,
                protectedVersions = 0,
                journal = sensitivePathBoundaryFixtures.mapIndexed { index, path ->
                    OperationJournalEntry(
                        index + 1L,
                        SdkmanTransaction.ToolchainActivation(
                            "Profile $path; activation",
                            listOf(com.worxbend.zephyr.domain.PlannedSdkmanCommand(com.worxbend.zephyr.domain.SdkmanCommandAction.Install, "java", "21-tem")),
                        ),
                        100,
                        outcome = "Failure $path; retry. See https://example.test/docs?next=/public/project",
                    )
                },
            )
            val result = JvmDiagnosticsExporter({ directory }, { 100 }, { emptyList() }).export(snapshot)
            val report = java.nio.file.Path.of(result.path).readText()
            assertFalse(report.contains("customer-private"), report)
            assertFalse(report.contains("Reilly"), report)
            assertTrue(report.contains("<redacted-path>"), report)
            assertTrue(report.contains("Session operations"), report)
            assertTrue(report.contains("Activate"), report)
            assertTrue(report.contains("https://example.test/docs?next=/public/project"), report)
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun exportsStructuredDiagnosticsWithSensitivePathsRedacted() = runBlocking {
        val directory = operationTestDirectory("zephyr-support-test-")
        try {
            val exporter = JvmDiagnosticsExporter(
                outputDirectory = { directory },
                clock = { 1_721_234_567_000L },
                sensitivePaths = { listOf("/home/alice") },
            )
            val snapshot = DiagnosticsSnapshot(
                generatedAtEpochMillis = 1_721_234_560_000L,
                sdkmanStatus = SdkmanStatus(
                    isInstalled = true,
                    home = "/opt/custom-sdkman",
                    cliVersion = "SDKMAN 5.20",
                ),
                connectivityStatus = ConnectivityStatus.from(
                    ConnectivityDiagnostic(
                        route = ConnectivityRouteKind.Proxy,
                        checkedAtEpochMillis = 1_721_234_560_500L,
                        latencyMillis = 42,
                        outcome = ConnectivityOutcome.Online,
                    ),
                ),
                integrityChecks = listOf(
                    IntegrityCheck(
                        IntegrityCheckId.RequiredScripts,
                        "Required scripts at /srv/secret-title/build",
                        IntegrityStatus.Passed,
                        "Scripts under /opt/custom-sdkman are present; C:\\Customers\\secret-detail\\scripts.",
                    ),
                ),
                installedCandidates = 2,
                installedVersions = 4,
                localOnlyVersions = 1,
                protectedVersions = 1,
                journal = listOf(
                    OperationJournalEntry(
                        id = 1,
                        transaction = SdkmanTransaction.Install("java", "21.0.5-tem"),
                        startedAtEpochMillis = 1_721_234_560_000L,
                        completedAtEpochMillis = 1_721_234_561_000L,
                        status = OperationStatus.Failed,
                        outcome = "Archive failed in /home/alice/.sdkman/tmp.",
                    ),
                ),
            )

            val result = exporter.export(snapshot)
            val report = java.nio.file.Path.of(result.path).readText()

            assertTrue(report.contains("Zephyr Support Bundle"))
            assertTrue(report.contains("Integrity"))
            assertTrue(report.contains("Session operations"))
            assertTrue(report.contains("Connectivity route: Proxy"))
            assertTrue(report.contains("Connectivity latency: 42 ms"))
            assertTrue(report.contains("<redacted-path>"))
            assertFalse(report.contains("/home/alice"))
            assertFalse(report.contains("/opt/custom-sdkman"))
            assertFalse(report.contains("secret-title"))
            assertFalse(report.contains("secret-detail"))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }
}
