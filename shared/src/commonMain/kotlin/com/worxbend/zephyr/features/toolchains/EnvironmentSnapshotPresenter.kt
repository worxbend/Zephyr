package com.worxbend.zephyr.features.toolchains

import com.worxbend.zephyr.data.EnvironmentSnapshot
import com.worxbend.zephyr.data.EnvironmentSnapshotService
import com.worxbend.zephyr.data.captureEnvironmentSnapshot
import com.worxbend.zephyr.data.currentEpochMillis
import com.worxbend.zephyr.domain.Candidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class EnvironmentSnapshotState(
    val current: EnvironmentSnapshot = EnvironmentSnapshot(capturedAtEpochMillis = 0, candidates = emptyList()),
    val baseline: EnvironmentSnapshot? = null,
    val restoreSnapshot: EnvironmentSnapshot? = null,
    val choosing: Boolean = false,
    val exporting: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class EnvironmentSnapshotPresenter(
    private val service: EnvironmentSnapshotService,
    parentScope: CoroutineScope,
    private val clock: () -> Long = ::currentEpochMillis,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val mutableState = MutableStateFlow(EnvironmentSnapshotState())
    val state = mutableState.asStateFlow()

    fun updateCandidates(candidates: List<Candidate>) {
        mutableState.update { it.copy(current = captureEnvironmentSnapshot(candidates, clock())) }
    }
    fun captureBaseline() { mutableState.update { it.copy(baseline = it.current, message = "Baseline captured for this session.", error = null) } }
    fun message(value: String) { mutableState.update { it.copy(message = value) } }
    fun importRestore() = work(exporting = false) {
        service.chooseAndRead()?.let { snapshot ->
            mutableState.update { it.copy(restoreSnapshot = snapshot, message = "Loaded snapshot with ${snapshot.candidates.size} candidate(s).") }
        }
    }
    fun export() {
        val snapshot = state.value.current
        work(exporting = true) {
            service.chooseAndWrite(snapshot)?.let { result ->
                mutableState.update { it.copy(message = "Exported ${result.versionCount} version(s) to ${result.fileName}.") }
            }
        }
    }
    private fun work(exporting: Boolean, action: suspend () -> Unit) {
        if (state.value.choosing || state.value.exporting) return
        mutableState.update { it.copy(choosing = !exporting, exporting = exporting, error = null, message = null) }
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.update { it.copy(error = failure.message ?: "Environment snapshot operation failed.") } }
            finally { mutableState.update { it.copy(choosing = false, exporting = false) } }
        }
    }
    fun close() = scope.cancel()
}
