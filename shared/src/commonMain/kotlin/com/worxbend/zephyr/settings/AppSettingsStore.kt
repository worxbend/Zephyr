package com.worxbend.zephyr.settings

import com.worxbend.zephyr.logging.ZephyrLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

sealed interface SettingsSaveStatus {
    val revision: Long
    val savedRevision: Long
    data object Loading : SettingsSaveStatus {
        override val revision = 0L
        override val savedRevision = 0L
    }
    data class Dirty(override val revision: Long, override val savedRevision: Long) : SettingsSaveStatus
    data class Saving(override val revision: Long, override val savedRevision: Long) : SettingsSaveStatus
    data class Saved(override val revision: Long) : SettingsSaveStatus {
        override val savedRevision = revision
    }
    data class Failed(
        override val revision: Long,
        override val savedRevision: Long,
        val reason: SettingsFailureReason,
    ) : SettingsSaveStatus
}

enum class SettingsFailureReason { Load, Save, Transform }

sealed interface SettingsCloseResult {
    data class Saved(val revision: Long) : SettingsCloseResult
    data class Failed(val status: SettingsSaveStatus.Failed) : SettingsCloseResult
    /** The writer may still finish its in-flight IO; no durable acknowledgement is implied. */
    data class TimedOut(val status: SettingsSaveStatus) : SettingsCloseResult
}

/** One application-owned writer; visible settings and durable save acknowledgements are separate. */
class AppSettingsStore(
    private val repository: AppSettingsRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private sealed interface Request {
        data class Update(val transform: (AppSettings) -> AppSettings) : Request
        data object Retry : Request
        data class Close(val acknowledgement: CompletableDeferred<SettingsCloseResult>) : Request
    }
    private data class Admission(val open: Boolean = true, val submissions: Int = 0)
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val requests = Channel<Request>(Channel.UNLIMITED)
    private val admission = MutableStateFlow(Admission())
    private val closing = MutableStateFlow<CompletableDeferred<SettingsCloseResult>?>(null)
    private val _state = MutableStateFlow(AppSettings())
    val state: StateFlow<AppSettings> = _state
    private val _saveStatus = MutableStateFlow<SettingsSaveStatus>(SettingsSaveStatus.Loading)
    val saveStatus: StateFlow<SettingsSaveStatus> = _saveStatus
    private var revision = 0L
    private var savedRevision = 0L
    private var loaded = false

    init {
        scope.launch {
            load()
            for (request in requests) {
                when (request) {
                    is Request.Update -> {
                        // A failed load is not permission to overwrite unreadable settings.
                        if (!loaded) continue
                        val next = try {
                            request.transform(_state.value)
                        } catch (exception: Exception) {
                            if (exception is CancellationException) throw exception
                            fail(SettingsFailureReason.Transform, exception)
                            continue
                        }
                        if (next != _state.value) {
                            revision++
                            _state.value = next
                            _saveStatus.value = SettingsSaveStatus.Dirty(revision, savedRevision)
                        }
                        if (revision != savedRevision) save()
                    }
                    Request.Retry -> {
                        if (!loaded) load()
                        else if (revision != savedRevision) save()
                        else _saveStatus.value = SettingsSaveStatus.Saved(savedRevision)
                    }
                    is Request.Close -> {
                        val failure = _saveStatus.value as? SettingsSaveStatus.Failed
                        val result = if (failure != null) SettingsCloseResult.Failed(failure)
                            else SettingsCloseResult.Saved(savedRevision)
                        if (!request.acknowledgement.isCancelled && result is SettingsCloseResult.Saved) {
                            requests.close()
                            request.acknowledgement.complete(result)
                            scope.cancel()
                            break
                        }
                        // Failure/timeout leaves the writer available for explicit retry and a new drain.
                        modifyAdmission { it.copy(open = true) }
                        closing.compareAndSet(request.acknowledgement, null)
                        request.acknowledgement.complete(result)
                    }
                }
            }
        }
    }

    fun update(transform: (AppSettings) -> AppSettings) {
        submit(Request.Update(transform))
    }

    /** Retry the retained dirty revision (or a failed initial read), including unchanged values. */
    fun retry() {
        submit(Request.Retry)
    }

    /** Compatibility non-blocking close: stop admission and drain, rather than cancel pending work. */
    fun close() {
        beginClose()
    }

    /** Only Saved acknowledges every accepted edit. Failed permits retry; timeout is not success. */
    suspend fun flushAndClose(timeoutMillis: Long = 5_000): SettingsCloseResult {
        require(timeoutMillis > 0) { "Settings close timeout must be positive." }
        val acknowledgement = beginClose()
        if (acknowledgement.isCancelled) return SettingsCloseResult.TimedOut(_saveStatus.value)
        val result = try {
            withTimeoutOrNull(timeoutMillis) { acknowledgement.await() }
        } catch (exception: CancellationException) {
            // A concurrent closer may have timed out the shared acknowledgement, not this caller.
            kotlin.coroutines.coroutineContext.ensureActive()
            if (acknowledgement.isCancelled) return SettingsCloseResult.TimedOut(_saveStatus.value)
            throw exception
        }
        if (result != null) return result
        acknowledgement.cancel()
        return SettingsCloseResult.TimedOut(_saveStatus.value)
    }

    private fun submit(request: Request) {
        while (true) {
            val current = admission.value
            if (!current.open) {
                ZephyrLogger.warn("Application settings request was ignored because the store is closing.")
                return
            }
            if (admission.compareAndSet(current, current.copy(submissions = current.submissions + 1))) break
        }
        try {
            requests.trySend(request)
        } finally {
            modifyAdmission { it.copy(submissions = it.submissions - 1) }
        }
    }

    private fun beginClose(): CompletableDeferred<SettingsCloseResult> {
        while (true) {
            closing.value?.let { return it }
            val acknowledgement = CompletableDeferred<SettingsCloseResult>()
            if (!closing.compareAndSet(null, acknowledgement)) continue
            modifyAdmission { it.copy(open = false) }
            scope.launch {
                admission.first { it.submissions == 0 }
                requests.send(Request.Close(acknowledgement))
            }
            return acknowledgement
        }
    }

    private fun modifyAdmission(transform: (Admission) -> Admission) {
        while (true) {
            val current = admission.value
            if (admission.compareAndSet(current, transform(current))) return
        }
    }

    private suspend fun load() {
        try {
            _state.value = repository.load()
            loaded = true
            _saveStatus.value = SettingsSaveStatus.Saved(revision)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            fail(SettingsFailureReason.Load, exception)
        }
    }

    private suspend fun save() {
        _saveStatus.value = SettingsSaveStatus.Saving(revision, savedRevision)
        try {
            repository.save(_state.value)
            savedRevision = revision
            _saveStatus.value = SettingsSaveStatus.Saved(revision)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            fail(SettingsFailureReason.Save, exception)
        }
    }

    private fun fail(reason: SettingsFailureReason, exception: Exception) {
        _saveStatus.value = SettingsSaveStatus.Failed(revision, savedRevision, reason)
        ZephyrLogger.warn("Application settings ${reason.name.lowercase()} failed.", exception)
    }
}
