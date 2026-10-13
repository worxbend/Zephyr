package com.worxbend.zephyr.data

import com.worxbend.zephyr.domain.ActivityAction
import com.worxbend.zephyr.domain.ActivityEvent
import com.worxbend.zephyr.domain.ActivitySeverity
import com.worxbend.zephyr.storage.AtomicDocumentStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.Base64

internal class JvmActivityStore(
    destination: Path = defaultActivityStorePath(),
    private val sensitivePaths: () -> List<String> = ::defaultSensitiveExportPaths,
    private val retentionLimit: Int = DEFAULT_ACTIVITY_RETENTION,
) : ActivityStore {
    private val document = AtomicDocumentStore(destination)

    init {
        require(retentionLimit > 0)
    }

    override suspend fun load(): List<ActivityEvent> =
        withContext(Dispatchers.IO) {
            document
                .read()
                ?.let(::parseActivityLedger)
                .orEmpty()
                .sortedByDescending(ActivityEvent::timestampEpochMillis)
                .take(retentionLimit)
        }

    override suspend fun save(events: List<ActivityEvent>) =
        withContext(Dispatchers.IO) {
            val retained =
                events
                    .sortedByDescending(ActivityEvent::timestampEpochMillis)
                    .take(retentionLimit)
            document.replace(renderActivityLedger(retained, sensitivePaths()))
        }
}

actual fun createActivityStore(): ActivityStore = JvmActivityStore()

internal fun renderActivityLedger(
    events: List<ActivityEvent>,
    sensitivePaths: List<String> = emptyList(),
): String =
    buildString {
        appendLine("$ACTIVITY_LEDGER_MAGIC\t$ACTIVITY_LEDGER_VERSION")
        val redactor = SensitiveTextRedactor(sensitivePaths)
        events.forEach { event ->
            appendLine(
                listOf(
                    event.id.toString(),
                    event.timestampEpochMillis.toString(),
                    event.severity.name,
                    event.acknowledged.toString(),
                    event.action?.name.orEmpty(),
                    encodeActivityField(redactor.redact(event.message).take(MAX_PERSISTED_ACTIVITY_MESSAGE_LENGTH)),
                ).joinToString("\t"),
            )
        }
    }

internal fun parseActivityLedger(content: String): List<ActivityEvent> {
    val lines = content.lineSequence().filter(String::isNotBlank).toList()
    require(lines.firstOrNull() == "$ACTIVITY_LEDGER_MAGIC\t$ACTIVITY_LEDGER_VERSION") {
        "Unsupported activity-center ledger."
    }
    return lines.drop(1).mapNotNull { line ->
        val fields = line.split('\t')
        if (fields.size != 6) return@mapNotNull null
        runCatching {
            ActivityEvent(
                id = fields[0].toLong().also { require(it >= 0) },
                timestampEpochMillis = fields[1].toLong().also { require(it >= 0) },
                severity = ActivitySeverity.valueOf(fields[2]),
                acknowledged = fields[3].toBooleanStrict(),
                action = fields[4].takeIf(String::isNotEmpty)?.let(ActivityAction::valueOf),
                message = decodeActivityField(fields[5]).trim().also { require(it.isNotEmpty()) },
            )
        }.getOrNull()
    }
}

private fun encodeActivityField(value: String): String = ACTIVITY_FIELD_ENCODER.encodeToString(value.toByteArray(StandardCharsets.UTF_8))

private fun decodeActivityField(value: String): String = String(ACTIVITY_FIELD_DECODER.decode(value), StandardCharsets.UTF_8)

private fun defaultActivityStorePath(): Path {
    val stateHome =
        System
            .getenv("XDG_STATE_HOME")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".local", "state")
    return stateHome.resolve("zephyr").resolve("activity-center-v1.ledger")
}

private val ACTIVITY_FIELD_ENCODER = Base64.getUrlEncoder().withoutPadding()
private val ACTIVITY_FIELD_DECODER = Base64.getUrlDecoder()
private const val ACTIVITY_LEDGER_MAGIC = "ZEPHYR_ACTIVITY_CENTER"
private const val ACTIVITY_LEDGER_VERSION = 1
private const val DEFAULT_ACTIVITY_RETENTION = 100
private const val MAX_PERSISTED_ACTIVITY_MESSAGE_LENGTH = 1_000
