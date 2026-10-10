package com.worxbend.zephyr.runtime

import com.worxbend.zephyr.data.DesktopNotificationService
import com.worxbend.zephyr.data.currentEpochMillis
import com.worxbend.zephyr.domain.Candidate
import com.worxbend.zephyr.domain.CandidateCatalogItem
import com.worxbend.zephyr.domain.ProtectedVersion
import com.worxbend.zephyr.logging.ZephyrLogger
import com.worxbend.zephyr.operationNotification
import com.worxbend.zephyr.settings.AppSettingsStore
import com.worxbend.zephyr.settings.UpdateNotificationPolicy
import com.worxbend.zephyr.settings.reconcileLocalOnlyObservations
import com.worxbend.zephyr.updateNotification
import com.worxbend.zephyr.viewmodel.ZephyrUiState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Application-lifetime policy, independent of recomposition and route lifetime. */
class AppPolicyCoordinator(
    states: StateFlow<ZephyrUiState>,
    private val settings: AppSettingsStore,
    private val notifications: DesktopNotificationService,
    refreshMetadataIfIdle: () -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val clock: () -> Long = ::currentEpochMillis,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    init {
        scope.launch {
            settings.state.map { it.metadataRefreshSchedule }.distinctUntilChanged().collectLatest { schedule ->
                val interval = schedule.intervalMillis ?: return@collectLatest
                // This is the user's opt-in schedule, not lifecycle/status polling.
                while (true) {
                    delay(interval)
                    refreshMetadataIfIdle()
                }
            }
        }
        scope.launch {
            val tracker = OperationCompletionTracker()
            states.map { (it as? ZephyrUiState.Ready)?.operationJournal }.filterNotNull()
                .distinctUntilChanged().collect { journal ->
                    for (entry in tracker.observe(journal)) {
                        operationNotification(settings.state.value.operationNotificationPolicy, entry)?.let {
                            show(it.title, it.message)
                        }
                    }
                }
        }
        scope.launch {
            combine(
                states.map { (it as? ZephyrUiState.Ready)?.localOnlyScanProgress?.trustedFindings.orEmpty() }
                    .distinctUntilChanged(),
                settings.state.map { it.cleanupGracePeriod }.distinctUntilChanged(),
            ) { findings, _ -> findings }.collect { findings ->
                val verified = findings.mapTo(linkedSetOf(), Candidate::name)
                val targets = findings.flatMap { candidate ->
                    candidate.localOnlyVersions.map { ProtectedVersion(candidate.name, it) }
                }.toSet()
                settings.update { it.reconcileLocalOnlyObservations(targets, clock(), verified) }
            }
        }
        scope.launch {
            var lastKey: String? = null
            combine(
                states.map { state ->
                    (state as? ZephyrUiState.Ready)?.let { UpdateEvidence(it.candidates, it.catalog, it.isCatalogLoading) }
                }.distinctUntilChanged(),
                settings.state.map { it.updateNotificationPolicy }.distinctUntilChanged(),
            ) { evidence, policy -> evidence to policy }.collect { (evidence, policy) ->
                if (policy == UpdateNotificationPolicy.Off) {
                    lastKey = null
                } else if (evidence != null) {
                    if (evidence.loading) {
                        if (policy == UpdateNotificationPolicy.AllChecks) lastKey = null
                    } else if (evidence.catalog.isNotEmpty()) {
                        val notification = updateNotification(policy, evidence.candidates, evidence.catalog)
                        val key = "${policy.name}:${notification?.signature ?: "current"}"
                        if (key != lastKey) {
                            lastKey = key
                            notification?.let { show(it.title, it.message) }
                        }
                    }
                }
            }
        }
    }

    private fun show(title: String, message: String) {
        try {
            notifications.show(title, message)
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            ZephyrLogger.warn("Unable to deliver desktop notification.", exception)
        }
    }

    fun close() { scope.cancel() }

    suspend fun closeAndJoin(timeoutMillis: Long = 5_000L): Boolean {
        close()
        return kotlinx.coroutines.withTimeoutOrNull(timeoutMillis) {
            scope.coroutineContext[kotlinx.coroutines.Job]?.join()
            true
        } ?: false
    }
}

private data class UpdateEvidence(
    val candidates: List<Candidate>,
    val catalog: List<CandidateCatalogItem>,
    val loading: Boolean,
)
