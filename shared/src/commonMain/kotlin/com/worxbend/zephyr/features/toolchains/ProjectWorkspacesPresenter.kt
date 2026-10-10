package com.worxbend.zephyr.features.toolchains

import com.worxbend.zephyr.data.ProjectToolchainService
import com.worxbend.zephyr.data.ProjectWorkspaceDocument
import com.worxbend.zephyr.data.TerminalLauncher
import com.worxbend.zephyr.settings.AppSettings
import com.worxbend.zephyr.settings.ProjectWorkspaceReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProjectWorkspacesState(
    val documents: Map<String, ProjectWorkspaceDocument> = emptyMap(),
    val errors: Map<String, String> = emptyMap(),
    val launchMessages: Map<String, String> = emptyMap(),
    val loading: Boolean = false,
    val pinning: Boolean = false,
)

class ProjectWorkspacesPresenter(
    private val service: ProjectToolchainService,
    private val terminal: TerminalLauncher,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val mutableState = MutableStateFlow(ProjectWorkspacesState())
    val state = mutableState.asStateFlow()
    private var refreshJob: Job? = null
    private var generation = 0

    fun refresh(references: List<ProjectWorkspaceReference>) {
        val request = ++generation
        refreshJob?.cancel()
        mutableState.update { it.copy(loading = true) }
        refreshJob = scope.launch {
            val documents = mutableMapOf<String, ProjectWorkspaceDocument>()
            val errors = mutableMapOf<String, String>()
            try {
                references.forEach { reference ->
                    try { documents[reference.sdkmanRcPath] = service.readWorkspace(reference) }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (failure: Exception) { errors[reference.sdkmanRcPath] = failure.message ?: "The project .sdkmanrc could not be read safely." }
                }
                if (request == generation) mutableState.update { it.copy(documents = documents.toMap(), errors = errors.toMap()) }
            } finally {
                if (request == generation) mutableState.update { it.copy(loading = false) }
            }
        }
    }
    fun pin(onSettingsChange: ((AppSettings) -> AppSettings) -> Unit) {
        if (state.value.pinning || state.value.loading) return
        mutableState.update { it.copy(pinning = true) }
        scope.launch {
            try {
                service.chooseWorkspace()?.let { selected ->
                    onSettingsChange {
                        it.copy(projectWorkspaces = (it.projectWorkspaces.filterNot { existing -> existing.sdkmanRcPath == selected.reference.sdkmanRcPath } + selected.reference).sortedBy { reference -> reference.displayName.lowercase() })
                    }
                    mutableState.update { it.copy(documents = it.documents + (selected.reference.sdkmanRcPath to selected), errors = it.errors - selected.reference.sdkmanRcPath) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                mutableState.update { it.copy(launchMessages = it.launchMessages + (PIN_WORKSPACE_MESSAGE_KEY to (failure.message ?: "Unable to pin the selected .sdkmanrc."))) }
            } finally { mutableState.update { it.copy(pinning = false) } }
        }
    }
    fun launch(sdkmanHome: String, workspace: ProjectWorkspaceDocument) {
        scope.launch {
            try {
                val result = terminal.launchWorkspace(sdkmanHome, workspace.projectDirectory, workspace.targets)
                mutableState.update { it.copy(launchMessages = it.launchMessages + (workspace.reference.sdkmanRcPath to result.message)) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                mutableState.update { it.copy(launchMessages = it.launchMessages + (workspace.reference.sdkmanRcPath to (failure.message ?: "Unable to open scoped terminal."))) }
            }
        }
    }
    fun close() = scope.cancel()
    companion object { const val PIN_WORKSPACE_MESSAGE_KEY = "<pin-workspace>" }
}
