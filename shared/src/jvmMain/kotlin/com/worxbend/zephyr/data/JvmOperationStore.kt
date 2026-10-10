package com.worxbend.zephyr.data

import com.worxbend.zephyr.domain.OperationJournalEntry
import com.worxbend.zephyr.storage.AtomicDocumentStore
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class JvmOperationStore(
    destination: Path = defaultOperationStorePath(),
    private val sensitivePaths: () -> List<String> = ::defaultSensitiveExportPaths,
    private val retentionLimit: Int = DEFAULT_OPERATION_RETENTION,
) : OperationStore {
    private val document = AtomicDocumentStore(destination)
    private val mutex = Mutex()
    private var blocked: OperationLedgerLoadException? = null

    init { require(retentionLimit > 0) }

    override suspend fun load(): List<OperationJournalEntry> = withContext(Dispatchers.IO) {
        mutex.withLock {
            checkReadable().sortedByDescending(OperationJournalEntry::startedAtEpochMillis).take(retentionLimit)
        }
    }

    override suspend fun save(entries: List<OperationJournalEntry>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            // Revalidate even if load was not called or the file changed after successful hydration.
            // A failed read poisons this instance: an empty caller fallback cannot erase evidence.
            checkReadable()
            val retained = entries.sortedByDescending(OperationJournalEntry::startedAtEpochMillis).take(retentionLimit)
            document.replace(OperationLedgerCodec.encode(retained, sensitivePaths()))
        }
    }

    private fun checkReadable(): List<OperationJournalEntry> {
        blocked?.let { throw it }
        val content = try {
            document.read()
        } catch (failure: Exception) {
            val exception = UnreadableOperationLedgerException(failure)
            blocked = exception
            throw exception
        }
        return try {
            content?.let(OperationLedgerCodec::decode).orEmpty()
        } catch (failure: OperationLedgerLoadException) {
            blocked = failure
            throw failure
        }
    }
}

actual fun createOperationStore(): OperationStore = JvmOperationStore()

private fun defaultOperationStorePath(): Path {
    val stateHome = System.getenv("XDG_STATE_HOME")?.takeIf(String::isNotBlank)?.let(Path::of)
        ?: Path.of(System.getProperty("user.home"), ".local", "state")
    // Keep the old filename so unreadable V1 evidence is not silently bypassed.
    return stateHome.resolve("zephyr").resolve("task-center-v1.ledger")
}
private const val DEFAULT_OPERATION_RETENTION = 250
