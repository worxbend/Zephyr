package com.worxbend.zephyr.data

import com.worxbend.zephyr.domain.JournalExportResult
import com.worxbend.zephyr.domain.OperationJournalEntry
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class JvmOperationJournalExporter(
    private val outputDirectory: () -> Path = ::defaultExportDirectory,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sensitivePaths: () -> List<String> = ::defaultSensitivePaths,
) : OperationJournalExporter {
    override suspend fun export(entries: List<OperationJournalEntry>): JournalExportResult =
        withContext(Dispatchers.IO) {
            require(entries.isNotEmpty()) { "The operation journal is empty." }
            val directory = outputDirectory().toAbsolutePath().normalize()
            Files.createDirectories(directory)
            val timestamp = FILE_TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(clock()).atZone(ZoneId.systemDefault()))
            val destination = uniqueDestination(directory, "zephyr-operation-journal-$timestamp", ".csv")
            Files.writeString(
                destination,
                entries.toCsv(sensitivePaths()),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE,
            )
            JournalExportResult(destination.toString(), entries.size)
        }
}

actual fun createOperationJournalExporter(): OperationJournalExporter = JvmOperationJournalExporter()

actual fun currentEpochMillis(): Long = System.currentTimeMillis()

actual fun formatLocalTimestamp(epochMillis: Long): String =
    DISPLAY_TIMESTAMP_FORMAT.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

private fun defaultExportDirectory(): Path {
    val home = Path.of(System.getProperty("user.home")).toAbsolutePath().normalize()
    val downloads = home.resolve("Downloads")
    return if (Files.isDirectory(downloads)) downloads else home
}

private fun defaultSensitivePaths(): List<String> =
    defaultSensitiveExportPaths()

private fun uniqueDestination(directory: Path, stem: String, extension: String): Path {
    var destination = directory.resolve("$stem$extension")
    var suffix = 2
    while (Files.exists(destination)) {
        destination = directory.resolve("$stem-$suffix$extension")
        suffix += 1
    }
    return destination
}

private fun List<OperationJournalEntry>.toCsv(sensitivePaths: List<String>): String = buildString {
    val redactor = SensitiveTextRedactor(sensitivePaths)
    appendLine("started,completed,status,operation,commands,outcome")
    this@toCsv.forEach { entry ->
        val commands = entry.transaction.commands.joinToString("; ") { command ->
            listOfNotNull(command.action.label, command.candidate, command.version).joinToString(" ")
        }
        appendLine(
            listOf(
                formatLocalTimestamp(entry.startedAtEpochMillis),
                entry.completedAtEpochMillis?.let(::formatLocalTimestamp).orEmpty(),
                entry.status.label,
                entry.transaction.title.removeSuffix("?"),
                commands,
                entry.outcome.orEmpty(),
            ).joinToString(",") { redactor.redact(it).csvField() },
        )
    }
}

private fun String.csvField(): String = "\"${replace("\"", "\"\"")}\""

private val FILE_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
private val DISPLAY_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
