package com.worxbend.zephyr.application.operation

import com.worxbend.zephyr.data.OperationStore
import com.worxbend.zephyr.data.SdkmanRepository
import com.worxbend.zephyr.domain.OperationJournalEntry
import com.worxbend.zephyr.domain.OperationStatus
import com.worxbend.zephyr.domain.OperationStepStatus
import com.worxbend.zephyr.domain.SdkmanCommandAction
import com.worxbend.zephyr.domain.SdkmanTransaction
import com.worxbend.zephyr.domain.resumeTransaction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull

sealed interface OperationAdmission {
    data class Accepted(val operationId: Long) : OperationAdmission
    data class Rejected(val reason: String) : OperationAdmission
}

sealed interface OperationReview {
    data class Reviewed(val entry: OperationJournalEntry, val transaction: SdkmanTransaction?) : OperationReview
    data object Busy : OperationReview
    data class Rejected(val reason: String) : OperationReview
}

data class OperationCoordinatorState(
    val entries: List<OperationJournalEntry> = emptyList(),
    val liveOperationIds: Set<Long> = emptySet(),
    val initialized: Boolean = false,
    val accepting: Boolean = true,
    val ledgerFailure: String? = null,
)

/**
 * Application lifetime owner of durable reviewed work. Reads never acquire this ownership.
 * A submitted request waits for lifecycle ownership; Accepted means its entire plan was saved.
 * Each external step is admitted durably before dispatch and its typed receipt is saved before
 * the next step. Recovery and all journal writes share the same lifecycle mutex.
 * This is not an exactly-once guarantee across external effects and process crashes.
 */
class OperationCoordinator(
    repository: SdkmanRepository,
    private val store: OperationStore,
    dispatcher: CoroutineDispatcher,
    private val clock: () -> Long,
    private val onCompletion: (OperationJournalEntry) -> Unit = {},
    private val receiptTimeoutMillis: Long = 5_000L,
) {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + dispatcher)
    private val lifecycle = Mutex()
    private val execution = OperationExecution(repository)
    private val recovery = OperationRecovery(repository, clock)
    private val _state = MutableStateFlow(OperationCoordinatorState())
    val state: StateFlow<OperationCoordinatorState> = _state.asStateFlow()
    private var nextId = 1L
    private var readable = false
    private var externalStepInFlight = false

    /** Hydrate once, never replace a live session with a fresh disk snapshot. */
    suspend fun initialize(): OperationCoordinatorState = lifecycle.withLock {
        if (_state.value.initialized) return@withLock _state.value
        try {
            val loaded = store.load()
            readable = true
            nextId = (loaded.maxOfOrNull(OperationJournalEntry::id) ?: 0L) + 1L
            val reconciled = loaded.map { entry ->
                if (entry.status == OperationStatus.Running) recovery.reconcile(entry, includeFailed = false) else entry
            }
            _state.update { it.copy(entries = reconciled, initialized = true) }
            if (loaded != reconciled) persist()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            readable = false
            _state.update { it.copy(initialized = true, ledgerFailure = "Task ledger could not be loaded: ${failure.message ?: "storage unavailable"}. Mutations are blocked; the original ledger is preserved.") }
        }
        _state.value
    }

    /** The returned deferred acknowledges admission, not completion. This scope owns execution. */
    fun submit(transaction: SdkmanTransaction): Deferred<OperationAdmission> {
        val admission = CompletableDeferred<OperationAdmission>()
        if (!_state.value.accepting) {
            admission.complete(OperationAdmission.Rejected("Operation coordinator is shutting down."))
            return admission
        }
        val owner = scope.launch {
            lifecycle.withLock {
                val current = _state.value
                if (!current.accepting || !current.initialized || !readable || current.ledgerFailure != null) {
                    admission.complete(OperationAdmission.Rejected(current.ledgerFailure ?: "Operation coordinator is not ready."))
                    return@withLock
                }
                val id = nextId++
                val entry = OperationJournalEntry(id, transaction, clock())
                _state.update { it.copy(entries = listOf(entry) + it.entries, liveOperationIds = setOf(id)) }
                try {
                    if (!persist()) {
                        _state.update { it.copy(entries = it.entries.filterNot { item -> item.id == id }) }
                        admission.complete(OperationAdmission.Rejected("The task ledger could not be saved, so the operation was not started."))
                        return@withLock
                    }
                    admission.complete(OperationAdmission.Accepted(id))
                    execute(id, transaction)
                } catch (cancelled: CancellationException) {
                    withContext(NonCancellable) {
                        if (entry(id).status == OperationStatus.Running) {
                            interrupt(id, "Task execution was interrupted; any in-flight outcome must be verified.", beforeDispatch = !externalStepInFlight)
                        }
                        persistBounded()
                    }
                    throw cancelled
                } catch (failure: Exception) {
                    withContext(NonCancellable) {
                        if (entry(id).status == OperationStatus.Running) {
                            interrupt(id, "Task execution stopped: ${failure.message ?: "unexpected failure"}.", beforeDispatch = !externalStepInFlight)
                        }
                        persistBounded()
                    }
                } finally {
                    externalStepInFlight = false
                    _state.update { it.copy(liveOperationIds = emptySet()) }
                    val completed = _state.value.entries.firstOrNull { it.id == id && it.status != OperationStatus.Running }
                    if (completed != null) onCompletion(completed)
                }
            }
        }
        owner.invokeOnCompletion {
            if (!admission.isCompleted) admission.complete(OperationAdmission.Rejected("Request was interrupted before durable admission."))
        }
        return admission
    }

    private suspend fun execute(id: Long, transaction: SdkmanTransaction) {
        val failedInstalls = mutableSetOf<Pair<String?, String?>>()
        transaction.commands.forEachIndexed { index, command ->
            if (command.action == SdkmanCommandAction.SetDefault && (command.candidate to command.version) in failedInstalls) {
                updateStep(id, index, OperationStepStatus.Skipped, "Skipped because the required install did not succeed.")
                if (!persist()) { stopForLedgerFailure(id, index); return }
                return@forEachIndexed
            }
            updateStep(id, index, OperationStepStatus.Running, null)
            if (!persist()) { stopForLedgerFailure(id, index); return }
            currentCoroutineContext().ensureActive()
            externalStepInFlight = true
            val outcome = execution.execute(transaction, command)
            externalStepInFlight = false
            val stepStatus = outcome.operationStepStatus()
            updateStep(id, index, stepStatus, outcome.message)
            if (command.action == SdkmanCommandAction.Install && stepStatus != OperationStepStatus.Succeeded) {
                failedInstalls += command.candidate to command.version
            }
            if (!persist()) { stopForLedgerFailure(id, index); return }
        }
        val entry = entry(id)
        val succeeded = entry.steps.count { it.status == OperationStepStatus.Succeeded }
        val summary = if (entry.steps.size == 1) entry.steps.single().outcome.orEmpty()
            else "$succeeded of ${entry.steps.size} ${transaction.summaryLabel()} succeeded."
        val status = when {
            succeeded == entry.steps.size -> OperationStatus.Succeeded
            entry.steps.any { it.status == OperationStepStatus.Indeterminate } -> OperationStatus.Indeterminate
            else -> OperationStatus.Failed
        }
        replace(entry.copy(status = status, outcome = summary, completedAtEpochMillis = clock()))
        persist()
    }

    private suspend fun stopForLedgerFailure(id: Long, index: Int) {
        // Preserve known receipts in memory even if their durability could not be acknowledged.
        interrupt(id, "Task ledger could not record step ${index + 1}; execution stopped before any later mutation.", beforeDispatch = true)
        persistBounded()
    }

    private fun interrupt(id: Long, message: String, beforeDispatch: Boolean = false) {
        val entry = entry(id)
        val completedAt = clock()
        replace(entry.copy(
            status = OperationStatus.Interrupted,
            outcome = message,
            completedAtEpochMillis = completedAt,
            steps = entry.steps.map { step ->
                if (step.status == OperationStepStatus.Running) step.copy(
                    status = if (beforeDispatch) OperationStepStatus.Interrupted else OperationStepStatus.Indeterminate,
                    outcome = message, completedAtEpochMillis = completedAt,
                ) else step
            },
        ))
    }

    private fun updateStep(id: Long, index: Int, status: OperationStepStatus, outcome: String?) {
        val current = entry(id)
        replace(current.copy(steps = current.steps.map { step ->
            if (step.index == index) step.copy(
                status = status, outcome = outcome,
                completedAtEpochMillis = if (status == OperationStepStatus.Running) null else clock(),
            ) else step
        }))
    }

    private fun entry(id: Long) = _state.value.entries.first { it.id == id }

    private fun replace(entry: OperationJournalEntry) {
        _state.update { it.copy(entries = it.entries.map { current -> if (current.id == entry.id) entry else current }) }
    }

    /** A live target is rejected before any suspending evidence read; otherwise recovery is serialized. */
    suspend fun reviewRemaining(id: Long): OperationReview {
        if (id in _state.value.liveOperationIds) return OperationReview.Busy
        return lifecycle.withLock {
            if (id in _state.value.liveOperationIds) return@withLock OperationReview.Busy
            if (!_state.value.accepting || !readable || _state.value.ledgerFailure != null) {
                return@withLock OperationReview.Rejected(_state.value.ledgerFailure ?: "Operation coordinator is not ready.")
            }
            val current = _state.value.entries.firstOrNull { it.id == id }
                ?: return@withLock OperationReview.Rejected("Task not found.")
            val reconciled = recovery.reconcile(current, includeFailed = true)
            replace(reconciled)
            if (!persist()) return@withLock OperationReview.Rejected("Task reconciliation could not be saved.")
            OperationReview.Reviewed(reconciled, reconciled.resumeTransaction())
        }
    }

    /** Retry retained revisions only. An unreadable ledger is never overwritten through this API. */
    suspend fun retryPersistence(): Boolean = lifecycle.withLock { readable && persist() }

    private suspend fun persist(): Boolean = try {
        store.save(_state.value.entries)
        _state.update { it.copy(ledgerFailure = null) }
        true
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Exception) {
        _state.update { it.copy(ledgerFailure = "Task ledger could not be saved: ${failure.message ?: "storage unavailable"}.") }
        false
    }

    private suspend fun persistBounded(): Boolean = try {
        withTimeout(receiptTimeoutMillis) { persist() }
    } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
        _state.update { it.copy(ledgerFailure = "Task ledger receipt persistence timed out.") }
        false
    }

    /** Best-effort cancellation. Desktop lifecycle must use shutdownAndJoin for acknowledgment. */
    fun close() {
        _state.update { it.copy(accepting = false) }
        job.cancel()
    }

    /** Returns false on timeout or unacknowledged receipt persistence; callers must surface that failure. */
    suspend fun shutdownAndJoin(timeoutMillis: Long = 10_000L): Boolean {
        close()
        val joined = withTimeoutOrNull(timeoutMillis) { job.cancelAndJoin(); true } ?: false
        return joined && _state.value.ledgerFailure == null
    }
}

private fun SdkmanTransaction.summaryLabel(): String = when (this) {
    is SdkmanTransaction.BatchInstall -> "selected installs"
    is SdkmanTransaction.BatchUninstall -> "selected uninstalls"
    is SdkmanTransaction.SnapshotRestore -> "snapshot restore steps"
    is SdkmanTransaction.ToolchainActivation -> "profile activation steps"
    is SdkmanTransaction.UpdateActivation -> "stable update steps"
    is SdkmanTransaction.CleanLocalOnly -> "cleanup steps"
    else -> "task steps"
}
