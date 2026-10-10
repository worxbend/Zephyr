package com.worxbend.zephyr.data

import com.worxbend.zephyr.domain.InstallTarget
import com.worxbend.zephyr.domain.OperationJournalEntry
import com.worxbend.zephyr.domain.PlannedSdkmanCommand
import com.worxbend.zephyr.domain.SdkmanCommandAction
import com.worxbend.zephyr.domain.SdkmanTransaction
import com.worxbend.zephyr.domain.UninstallTarget
import com.worxbend.zephyr.domain.UpdateActivationTarget
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class OperationLedgerCodecTest {
    private val entry = OperationJournalEntry(7, SdkmanTransaction.BatchInstall(
        listOf(InstallTarget("java", "21-tem"), InstallTarget("gradle", "9.0")),
    ), 100)

    @Test
    fun redactsBoundaryPathsBeforePersistingProfileAndEntryAndStepOutcomes() {
        sensitivePathBoundaryFixtures.forEach { path ->
            val transaction = SdkmanTransaction.ToolchainActivation("Profile $path; activation", entry.transaction.commands)
            val original = entry.copy(transaction = transaction, outcome = "Failure $path; retry.", steps = entry.steps.map {
                it.copy(outcome = "Failure $path; retry.")
            })
            val persisted = parseOperationLedger(renderOperationLedger(listOf(original))).single()
            val freeText = listOf(persisted.transaction.title, persisted.outcome.orEmpty()) + persisted.steps.map { it.outcome.orEmpty() }
            freeText.forEach { text ->
                kotlin.test.assertFalse(text.contains("customer-private"), "path=$path: $text")
                kotlin.test.assertFalse(text.contains("Reilly"), "path=$path: $text")
                kotlin.test.assertTrue(text.contains("<redacted-path>"), "path=$path: $text")
            }
            assertEquals(original.transaction.commands, persisted.transaction.commands)
            assertEquals(original.steps.map { it.command }, persisted.steps.map { it.command })
        }
    }

    @Test
    fun rejectsLostPendingTailEvenWhenRemainingRecordsAreValid() {
        val body = renderOperationLedger(listOf(entry)).lines().filterNot { it.startsWith("S\t7\t1\t") || it.startsWith("Z\t") || it.isEmpty() }
        assertFailsWith<CorruptOperationLedgerException> { parseOperationLedger(seal(body)) }
    }

    @Test
    fun rejectsDuplicateGappedNegativeAndReorderedIndices() {
        listOf("0", "2", "-1").forEach { index ->
            val body = body().map { if (it.startsWith("S\t7\t1\t")) it.replace("S\t7\t1\t", "S\t7\t$index\t") else it }
            assertFailsWith<CorruptOperationLedgerException>("index=$index") { parseOperationLedger(seal(body)) }
        }
        assertFailsWith<CorruptOperationLedgerException> { parseOperationLedger(seal(body().let { listOf(it[0], it[1], it[3], it[2]) })) }
    }

    @Test
    fun rejectsMalformedRecordsRatherThanSalvagingAnyEntries() {
        val original = body()
        val damages = listOf(
            original + "garbage",
            original + original[1],
            original.map { if (it.startsWith("S\t7\t1")) "S\t7\t1" else it },
            original.map { if (it.startsWith("S\t7\t1")) it.replace("\tPending\t", "\tUnknown\t") else it },
            original.map { if (it.startsWith("S\t7\t1")) it.replace("S\t7", "S\t8") else it },
            original.map { if (it.startsWith("S\t7\t1")) it.replace("\tZ3JhZGxl\t", "\t%%%\t") else it },
            original.map { if (it.startsWith("S\t7\t1")) it.replace("\tZ3JhZGxl\t", "\t_w\t") else it },
            original.map { if (it.startsWith("E\t")) it.replace("\t100\t", "\tnot-a-number\t") else it },
            original + "",
        )
        damages.forEachIndexed { index, lines ->
            assertFailsWith<CorruptOperationLedgerException>("damage=$index") { parseOperationLedger(seal(lines)) }
        }
    }

    @Test
    fun rejectsTruncationAtEveryByteBoundary() {
        val text = renderOperationLedger(listOf(entry))
        (0 until text.length).forEach { length ->
            assertFailsWith<CorruptOperationLedgerException>("length=$length") { parseOperationLedger(text.take(length)) }
        }
    }

    @Test
    fun rejectsChangedActionsAndIdentifiersEvenWithValidDocumentChecksum() {
        val mutations = listOf(
            body().map { if (it.startsWith("S\t")) it.replace("\tInstall\t", "\tUninstall\t") else it },
            body().map { if (it.startsWith("E\t")) it.replace("\tbatch-install\t", "\tbatch-uninstall\t") else it },
            body().map { if (it.startsWith("S\t")) it.replace("\tZ3JhZGxl\t", "\t${field("../gradle")}\t") else it },
        )
        mutations.forEach { assertFailsWith<CorruptOperationLedgerException> { parseOperationLedger(seal(it)) } }
    }

    @Test
    fun checksExpectedPlanBeforeEncoding() {
        assertFailsWith<IllegalArgumentException> { renderOperationLedger(listOf(entry.copy(steps = entry.steps.take(1)))) }
        assertFailsWith<IllegalArgumentException> { renderOperationLedger(listOf(entry, entry)) }
        assertFailsWith<IllegalArgumentException> { renderOperationLedger(listOf(entry.copy(id = -1))) }
    }

    @Test
    fun acceptsLegacySingleAndRejectsUnverifiableLegacyBatchWithoutShrinking() {
        val single = "ZEPHYR_TASK_CENTER\t1\nE\t7\t100\t\tRunning\tinstall\t\nS\t7\t0\tInstall\t${field("java")}\t${field("21-tem")}\tPending\t\t\n"
        assertEquals(SdkmanTransaction.Install("java", "21-tem"), parseOperationLedger(single).single().transaction)
        val batch = single.replace("\tinstall\t", "\tbatch-install\t")
        assertFailsWith<CorruptOperationLedgerException> { parseOperationLedger(batch) }
        assertFailsWith<CorruptOperationLedgerException> { parseOperationLedger(single.replace("\tInstall\t", "\tUninstall\t")) }
        assertFailsWith<CorruptOperationLedgerException> { parseOperationLedger(single.replace("\tPending\t", "\tBROKEN\t")) }
    }

    @Test
    fun unknownSchemaIsExplicitlyUnsupported() {
        val error = assertFailsWith<UnsupportedOperationLedgerException> { parseOperationLedger("ZEPHYR_TASK_CENTER\t99\n") }
        assertEquals("99", error.schemaVersion)
    }

    @Test
    fun roundTripsAllTransactionKindsAndEmptyLedger() {
        val commands = listOf(PlannedSdkmanCommand(SdkmanCommandAction.Install, "java", "21-tem"), PlannedSdkmanCommand(SdkmanCommandAction.SetDefault, "java", "21-tem"))
        val transactions = listOf(
            SdkmanTransaction.Install("java", "21-tem"), entry.transaction,
            SdkmanTransaction.SnapshotRestore(commands), SdkmanTransaction.ToolchainActivation("Backend", commands),
            SdkmanTransaction.UpdateActivation(listOf(UpdateActivationTarget("java", "21-tem", true))),
            SdkmanTransaction.Uninstall("java", "21-tem"), SdkmanTransaction.BatchUninstall(listOf(UninstallTarget("java", "21-tem"))),
            SdkmanTransaction.SetDefault("java", "21-tem"), SdkmanTransaction.CleanLocalOnly("java", listOf("21-tem")),
            SdkmanTransaction.RefreshMetadata, SdkmanTransaction.SelfUpdate,
        )
        val entries = transactions.mapIndexed { index, transaction -> OperationJournalEntry(index + 1L, transaction, 100) }
        assertEquals(entries, parseOperationLedger(renderOperationLedger(entries)))
        assertEquals(emptyList(), parseOperationLedger(renderOperationLedger(emptyList())))
    }

    private fun body() = renderOperationLedger(listOf(entry)).lines().filterNot { it.startsWith("Z\t") || it.isEmpty() }
    private fun seal(lines: List<String>): String {
        val body = lines.joinToString("\n", postfix = "\n")
        val hash = Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(body.toByteArray(UTF_8)))
        return body + "Z\t${lines.count { it.startsWith("E\t") }}\t$hash\n"
    }
    private fun field(text: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(text.toByteArray(UTF_8))
}
