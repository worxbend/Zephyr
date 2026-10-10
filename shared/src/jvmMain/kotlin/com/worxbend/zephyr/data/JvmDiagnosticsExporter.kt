package com.worxbend.zephyr.data

import com.worxbend.zephyr.domain.DiagnosticsSnapshot
import com.worxbend.zephyr.domain.SupportBundleExportResult
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class JvmDiagnosticsExporter(
    private val outputDirectory: () -> Path = ::defaultDiagnosticsDirectory,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sensitivePaths: () -> List<String> = ::defaultDiagnosticsSensitivePaths,
) : DiagnosticsExporter {
    override suspend fun export(snapshot: DiagnosticsSnapshot): SupportBundleExportResult =
        withContext(Dispatchers.IO) {
            val directory = outputDirectory().toAbsolutePath().normalize()
            Files.createDirectories(directory)
            val timestamp = FILE_TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()))
            val destination = uniqueDiagnosticsDestination(directory, "zephyr-support-$timestamp", ".txt")
            Files.writeString(
                destination,
                SensitiveTextRedactor(sensitivePaths() + listOfNotNull(snapshot.sdkmanStatus.home))
                    .redact(snapshot.toSupportText()),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            SupportBundleExportResult(destination.toString())
        }
}

actual fun createDiagnosticsExporter(): DiagnosticsExporter = JvmDiagnosticsExporter()

private fun DiagnosticsSnapshot.toSupportText(): String = buildString {
    appendLine("Zephyr Support Bundle")
    appendLine("=====================")
    appendLine("Generated: ${formatLocalTimestamp(generatedAtEpochMillis)}")
    appendLine("Zephyr: 1.2.3")
    appendLine("OS: ${System.getProperty("os.name")} ${System.getProperty("os.version")}")
    appendLine("Java: ${System.getProperty("java.version")}")
    appendLine()
    appendLine("SDKMAN")
    appendLine("------")
    appendLine("Installed: ${sdkmanStatus.isInstalled}")
    appendLine("Version: ${sdkmanStatus.cliVersion ?: "unknown"}")
    appendLine("Home: <redacted-path>")
    appendLine("Connectivity: ${connectivityStatus.state.label}")
    connectivityStatus.diagnostic?.let { diagnostic ->
        appendLine("Connectivity route: ${diagnostic.route.label}")
        appendLine("Connectivity outcome: ${diagnostic.outcome.label}")
        appendLine("Connectivity latency: ${diagnostic.latencyMillis} ms")
        appendLine("Connectivity checked: ${formatLocalTimestamp(diagnostic.checkedAtEpochMillis)}")
    }
    appendLine("Installed candidates: $installedCandidates")
    appendLine("Installed versions: $installedVersions")
    appendLine("Local-only versions: $localOnlyVersions")
    appendLine("Protected versions: $protectedVersions")
    appendLine()
    appendLine("Integrity")
    appendLine("---------")
    integrityChecks.forEach { check ->
        appendLine("${check.status.label} | ${check.title} | ${check.detail}")
    }
    appendLine()
    appendLine("Session operations")
    appendLine("------------------")
    if (journal.isEmpty()) {
        appendLine("No confirmed operations in this session.")
    } else {
        journal.forEach { entry ->
            appendLine(
                "${formatLocalTimestamp(entry.startedAtEpochMillis)} | ${entry.status.label} | " +
                    "${entry.transaction.title.removeSuffix("?")} | ${entry.outcome ?: "no outcome"}",
            )
        }
    }
}

private fun defaultDiagnosticsDirectory(): Path {
    val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
    val downloads = home.resolve("Downloads")
    return if (Files.isDirectory(downloads)) downloads else home
}

private fun defaultDiagnosticsSensitivePaths(): List<String> =
    defaultSensitiveExportPaths()

private fun uniqueDiagnosticsDestination(directory: Path, stem: String, extension: String): Path {
    var destination = directory.resolve("$stem$extension")
    var suffix = 2
    while (Files.exists(destination)) {
        destination = directory.resolve("$stem-$suffix$extension")
        suffix += 1
    }
    return destination
}

private val FILE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
