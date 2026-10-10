package com.worxbend.zephyr.features.toolchains

import com.worxbend.zephyr.data.ProjectToolchainService
import com.worxbend.zephyr.data.SdkmanRcDocument
import com.worxbend.zephyr.domain.InstallTarget
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProjectToolchainState(
    val document: SdkmanRcDocument? = null,
    val busy: Boolean = false,
    val message: String? = null,
    val error: String? = null,
)

class ProjectToolchainPresenter(private val service: ProjectToolchainService, parentScope: CoroutineScope) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val mutableState = MutableStateFlow(ProjectToolchainState())
    val state = mutableState.asStateFlow()

    fun chooseDocument() = work {
        service.chooseAndRead()?.let { document -> mutableState.update { it.copy(document = document) } }
    }
    fun export(targets: List<InstallTarget>) = work {
        service.chooseAndWrite(targets)?.let { result ->
            mutableState.update { it.copy(message = "Exported ${result.exportedTargets} defaults to ${result.fileName}.") }
        }
    }
    private fun work(action: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true, error = null, message = null) }
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) { mutableState.update { it.copy(error = failure.message ?: "Project toolchain operation failed.") } }
            finally { mutableState.update { it.copy(busy = false) } }
        }
    }
    fun close() = scope.cancel()
}
