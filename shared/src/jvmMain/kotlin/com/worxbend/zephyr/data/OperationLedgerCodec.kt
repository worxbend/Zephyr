package com.worxbend.zephyr.data

import com.worxbend.zephyr.domain.InstallTarget
import com.worxbend.zephyr.domain.OperationJournalEntry
import com.worxbend.zephyr.domain.OperationStatus
import com.worxbend.zephyr.domain.OperationStep
import com.worxbend.zephyr.domain.OperationStepStatus
import com.worxbend.zephyr.domain.PlannedSdkmanCommand
import com.worxbend.zephyr.domain.SdkmanCommandAction
import com.worxbend.zephyr.domain.SdkmanTransaction
import com.worxbend.zephyr.domain.UninstallTarget
import com.worxbend.zephyr.domain.UpdateActivationTarget
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64

/** Load failures are never equivalent to missing history. Originals remain untouched. */
internal sealed class OperationLedgerLoadException(message: String, cause: Throwable? = null) :
    IllegalArgumentException(message, cause)
internal class CorruptOperationLedgerException(cause: Throwable? = null) :
    OperationLedgerLoadException("Task history is corrupt or its complete plan cannot be verified. Original preserved.", cause)
internal class UnsupportedOperationLedgerException(val schemaVersion: String) :
    OperationLedgerLoadException("Task history uses an unsupported schema. Original preserved.")
internal class UnreadableOperationLedgerException(cause: Throwable? = null) :
    OperationLedgerLoadException("Task history could not be read safely. Original preserved.", cause)

/** Pure, versioned codec. V2 binds the complete ordered plan and whole document. */
internal object OperationLedgerCodec {
    fun encode(entries: List<OperationJournalEntry>, sensitivePaths: List<String>): String {
        require(entries.map { it.id }.distinct().size == entries.size) { "Duplicate operation identifiers." }
        val redactor = SensitiveTextRedactor(sensitivePaths)
        val body = buildString {
            appendLine("$MAGIC\t2")
            entries.forEach { entry ->
                validateEntry(entry)
                appendLine(listOf(
                    "E", entry.id.toString(), entry.startedAtEpochMillis.toString(),
                    entry.completedAtEpochMillis?.toString().orEmpty(), entry.status.name,
                    entry.transaction.persistenceKind(redactor),
                    encodeField(redactor.redact(entry.outcome.orEmpty()).take(1_000)),
                    entry.steps.size.toString(), planDigest(entry.transaction.commands),
                ).joinToString("\t"))
                entry.steps.sortedBy(OperationStep::index).forEach { step ->
                    appendLine(listOf(
                        "S", entry.id.toString(), step.index.toString(), step.command.action.name,
                        encodeField(step.command.candidate.orEmpty()), encodeField(step.command.version.orEmpty()),
                        step.status.name, step.completedAtEpochMillis?.toString().orEmpty(),
                        encodeField(redactor.redact(step.outcome.orEmpty()).take(1_000)),
                    ).joinToString("\t"))
                }
            }
        }
        return body + "Z\t${entries.size}\t${digest(body)}\n"
    }

    fun decode(content: String): List<OperationJournalEntry> {
        try {
            require(content.endsWith('\n')) { "Truncated ledger." }
            val lines = content.dropLast(1).split('\n')
            val header = lines.first().split('\t')
            require(header.size == 2 && header[0] == MAGIC) { "Invalid ledger header." }
            val version = header[1]
            if (version != "1" && version != "2") throw UnsupportedOperationLedgerException(version)
            val records = if (version == "2") {
                val footer = lines.last().split('\t')
                require(footer.size == 3 && footer[0] == "Z") { "Missing completion record." }
                val body = lines.dropLast(1).joinToString("\n", postfix = "\n")
                require(footer[2] == digest(body)) { "Document integrity mismatch." }
                lines.drop(1).dropLast(1)
            } else lines.drop(1)
            val entries = mutableListOf<OperationJournalEntry>()
            val ids = mutableSetOf<Long>()
            var position = 0
            while (position < records.size) {
                val fields = records[position++].split('\t')
                require(fields.size == if (version == "2") 9 else 7)
                require(fields[0] == "E") { "Unknown or orphan record." }
                val id = fields[1].toLong()
                require(ids.add(id)) { "Duplicate operation identifier." }
                val steps = mutableListOf<OperationStep>()
                while (position < records.size && records[position].startsWith("S\t")) {
                    val step = records[position++].split('\t')
                    require(step.size == 9 && step[1].toLong() == id) { "Malformed or orphan step." }
                    steps += OperationStep(
                        index = step[2].toInt(),
                        command = PlannedSdkmanCommand(
                            SdkmanCommandAction.valueOf(step[3]),
                            decodeField(step[4]).ifEmpty { null }, decodeField(step[5]).ifEmpty { null },
                        ),
                        status = OperationStepStatus.valueOf(step[6]),
                        completedAtEpochMillis = step[7].takeIf(String::isNotEmpty)?.toLong(),
                        outcome = decodeField(step[8]).ifEmpty { null },
                    )
                }
                require(steps.isNotEmpty() && steps.map { it.index } == steps.indices.toList()) {
                    "Step indices must be unique, ordered and contiguous from zero."
                }
                val commands = steps.map(OperationStep::command)
                if (version == "2") {
                    require(fields[7].toInt() == steps.size) { "Incomplete expected plan." }
                    require(fields[8] == planDigest(commands)) { "Plan integrity mismatch." }
                } else {
                    // V1 stored no expected count or plan identity. Variable-length plans cannot
                    // distinguish a valid prefix from a lost pending tail; do not auto-salvage them.
                    require(fields[5] in LEGACY_SINGLE_KINDS) { "Legacy batch plan is unverifiable." }
                }
                val transaction = transactionFromStored(fields[5], commands)
                require(transaction.commands == commands) { "Transaction/action mismatch." }
                val entry = OperationJournalEntry(
                    id, transaction, fields[2].toLong(),
                    fields[3].takeIf(String::isNotEmpty)?.toLong(), OperationStatus.valueOf(fields[4]),
                    decodeField(fields[6]).ifEmpty { null }, steps,
                )
                validateEntry(entry)
                entries += entry
            }
            if (version == "2") require(lines.last().split('\t')[1].toInt() == entries.size)
            return entries
        } catch (failure: OperationLedgerLoadException) {
            throw failure
        } catch (failure: Exception) {
            throw CorruptOperationLedgerException(failure)
        }
    }
}

internal fun renderOperationLedger(entries: List<OperationJournalEntry>, sensitivePaths: List<String> = emptyList()): String =
    OperationLedgerCodec.encode(entries, sensitivePaths)
internal fun parseOperationLedger(content: String): List<OperationJournalEntry> = OperationLedgerCodec.decode(content)

private fun validateEntry(entry: OperationJournalEntry) {
    require(entry.id > 0 && entry.startedAtEpochMillis >= 0)
    require(entry.completedAtEpochMillis == null || entry.completedAtEpochMillis >= entry.startedAtEpochMillis)
    require(entry.steps.map { it.index } == entry.steps.indices.toList())
    require(entry.steps.map { it.command } == entry.transaction.commands) { "Steps differ from expected transaction plan." }
    entry.steps.forEach {
        require(it.completedAtEpochMillis == null || it.completedAtEpochMillis >= entry.startedAtEpochMillis)
    }
}

private fun SdkmanTransaction.persistenceKind(redactor: SensitiveTextRedactor): String = when (this) {
    is SdkmanTransaction.Install -> "install"
    is SdkmanTransaction.BatchInstall -> "batch-install"
    is SdkmanTransaction.SnapshotRestore -> "snapshot-restore"
    is SdkmanTransaction.ToolchainActivation -> "profile-activation:${encodeField(redactor.redact(profileName.trim()).take(120))}"
    is SdkmanTransaction.UpdateActivation -> "update-activation"
    is SdkmanTransaction.Uninstall -> "uninstall"
    is SdkmanTransaction.BatchUninstall -> "batch-uninstall"
    is SdkmanTransaction.SetDefault -> "set-default"
    is SdkmanTransaction.CleanLocalOnly -> "clean-local-only"
    SdkmanTransaction.RefreshMetadata -> "refresh-metadata"
    SdkmanTransaction.SelfUpdate -> "self-update"
}

private fun transactionFromStored(kind: String, commands: List<PlannedSdkmanCommand>): SdkmanTransaction = when (kind) {
    "install" -> commands.single().let { SdkmanTransaction.Install(requireNotNull(it.candidate), requireNotNull(it.version)) }
    "batch-install" -> SdkmanTransaction.BatchInstall(commands.map { InstallTarget(requireNotNull(it.candidate), requireNotNull(it.version)) })
    "snapshot-restore" -> SdkmanTransaction.SnapshotRestore(commands)
    "update-activation" -> {
        val installs = commands.filter { it.action == SdkmanCommandAction.Install }.map { it.candidate to it.version }.toSet()
        SdkmanTransaction.UpdateActivation(commands.filter { it.action == SdkmanCommandAction.SetDefault }.map {
            UpdateActivationTarget(requireNotNull(it.candidate), requireNotNull(it.version), (it.candidate to it.version) in installs)
        })
    }
    "uninstall" -> commands.single().let { SdkmanTransaction.Uninstall(requireNotNull(it.candidate), requireNotNull(it.version)) }
    "batch-uninstall" -> SdkmanTransaction.BatchUninstall(commands.map { UninstallTarget(requireNotNull(it.candidate), requireNotNull(it.version)) })
    "set-default" -> commands.single().let { SdkmanTransaction.SetDefault(requireNotNull(it.candidate), requireNotNull(it.version)) }
    "clean-local-only" -> SdkmanTransaction.CleanLocalOnly(
        commands.map { requireNotNull(it.candidate) }.distinct().single(), commands.map { requireNotNull(it.version) },
    )
    "refresh-metadata" -> SdkmanTransaction.RefreshMetadata
    "self-update" -> SdkmanTransaction.SelfUpdate
    else -> {
        require(kind.startsWith("profile-activation:")) { "Unknown transaction kind." }
        SdkmanTransaction.ToolchainActivation(decodeField(kind.removePrefix("profile-activation:")), commands)
    }
}

private fun planDigest(commands: List<PlannedSdkmanCommand>): String = digest(commands.joinToString("\n") {
    listOf(it.action.name, encodeField(it.candidate.orEmpty()), encodeField(it.version.orEmpty())).joinToString("\t")
})
private fun digest(value: String): String = Base64.getUrlEncoder().withoutPadding()
    .encodeToString(MessageDigest.getInstance("SHA-256").digest(value.toByteArray(UTF_8)))
private fun encodeField(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray(UTF_8))
private fun decodeField(value: String): String {
    val bytes = Base64.getUrlDecoder().decode(value)
    require(Base64.getUrlEncoder().withoutPadding().encodeToString(bytes) == value) { "Noncanonical field encoding." }
    return UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()
}
private const val MAGIC = "ZEPHYR_TASK_CENTER"
private val LEGACY_SINGLE_KINDS = setOf("install", "uninstall", "set-default", "refresh-metadata", "self-update")
