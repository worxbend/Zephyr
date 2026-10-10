package com.worxbend.zephyr.viewmodel

import com.worxbend.zephyr.application.operation.OperationAdmission
import com.worxbend.zephyr.application.operation.OperationCoordinator
import com.worxbend.zephyr.application.operation.OperationReview

import com.worxbend.zephyr.data.DiagnosticsExporter
import com.worxbend.zephyr.data.OperationJournalExporter
import com.worxbend.zephyr.data.OperationStore
import com.worxbend.zephyr.data.NoOpOperationStore
import com.worxbend.zephyr.data.ActivityStore
import com.worxbend.zephyr.data.NoOpActivityStore
import com.worxbend.zephyr.data.SdkmanRepository
import com.worxbend.zephyr.data.currentEpochMillis
import com.worxbend.zephyr.domain.Candidate
import com.worxbend.zephyr.domain.ActivityAction
import com.worxbend.zephyr.domain.ActivityEvent
import com.worxbend.zephyr.domain.ActivitySeverity
import com.worxbend.zephyr.domain.CandidateCatalogItem
import com.worxbend.zephyr.domain.CandidateMetadataStatus
import com.worxbend.zephyr.domain.BatchInstallProgress
import com.worxbend.zephyr.domain.BatchItemStatus
import com.worxbend.zephyr.domain.BatchUninstallProgress
import com.worxbend.zephyr.domain.ConnectivityState
import com.worxbend.zephyr.domain.ConnectivityStatus
import com.worxbend.zephyr.domain.ConnectivityDiagnostic
import com.worxbend.zephyr.domain.ConnectivityOutcome
import com.worxbend.zephyr.domain.ConnectivityRouteKind
import com.worxbend.zephyr.domain.DiskImpactEstimate
import com.worxbend.zephyr.domain.DiskImpactKind
import com.worxbend.zephyr.domain.DiagnosticsSnapshot
import com.worxbend.zephyr.domain.EstimateConfidence
import com.worxbend.zephyr.domain.IntegrityCheck
import com.worxbend.zephyr.domain.LocalOnlyAudit
import com.worxbend.zephyr.domain.LocalOnlyCandidateScanStatus
import com.worxbend.zephyr.domain.LocalOnlyScanProgress
import com.worxbend.zephyr.domain.OperationJournalEntry
import com.worxbend.zephyr.domain.OperationStatus
import com.worxbend.zephyr.domain.OperationStepStatus
import com.worxbend.zephyr.domain.PlannedSdkmanCommand
import com.worxbend.zephyr.domain.ProtectedVersion
import com.worxbend.zephyr.domain.ReadRetryStatus
import com.worxbend.zephyr.domain.RetryableReadOperation
import com.worxbend.zephyr.domain.SdkmanSelfUpdateStatus
import com.worxbend.zephyr.domain.SdkmanStatus
import com.worxbend.zephyr.domain.SdkmanTransaction
import com.worxbend.zephyr.domain.SnapshotRestoreProgress
import com.worxbend.zephyr.domain.StorageInventory
import com.worxbend.zephyr.domain.requiresNetwork
import com.worxbend.zephyr.domain.withInstalledCandidates
import com.worxbend.zephyr.logging.ZephyrLogger
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.withTimeoutOrNull
import com.worxbend.zephyr.domain.cleanupEligibility
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

sealed interface ZephyrRoute {
    data object Overview : ZephyrRoute
    data object InstalledJdk : ZephyrRoute
    data object InstalledSdks : ZephyrRoute
    data object BrowseJdks : ZephyrRoute
    data object BrowseSdks : ZephyrRoute
    data object LocalOnly : ZephyrRoute
    data object Storage : ZephyrRoute
    data object UpdateCenter : ZephyrRoute
    data object BatchUninstall : ZephyrRoute
    data object Profiles : ZephyrRoute
    data object ProjectWorkspaces : ZephyrRoute
    data object ProjectImport : ZephyrRoute
    data object ProjectExport : ZephyrRoute
    data object EnvironmentSnapshot : ZephyrRoute
    data object Comparison : ZephyrRoute
    data object Diagnostics : ZephyrRoute
    data object History : ZephyrRoute
    data object Settings : ZephyrRoute
    data object About : ZephyrRoute
    data class JdkDetail(val candidate: String = "java") : ZephyrRoute
    data class SdkDetail(val candidate: String) : ZephyrRoute
}

sealed interface ZephyrUiState {
    data object Loading : ZephyrUiState
    data class SdkmanMissing(val message: String) : ZephyrUiState
    data class Ready(
        val sdkmanStatus: SdkmanStatus,
        val route: ZephyrRoute,
        val previousRoute: ZephyrRoute?,
        val candidates: List<Candidate>,
        val catalog: List<CandidateCatalogItem>,
        val catalogCachedAtEpochMillis: Long? = null,
        val catalogIsCached: Boolean = false,
        val selectedCandidate: Candidate?,
        val isRefreshing: Boolean,
        val isCatalogLoading: Boolean,
        val localOnlyScanInProgress: Boolean,
        val localOnlyScanProgress: LocalOnlyScanProgress? = null,
        val storageInventory: StorageInventory? = null,
        val storageScanInProgress: Boolean = false,
        val detailLoadingCandidate: String? = null,
        val errorMessage: String?,
        val lastOutcome: String?,
        val pendingTransaction: SdkmanTransaction? = null,
        val pendingTransactionDiskImpact: DiskImpactEstimate? = null,
        val transactionPreviewLoading: Boolean = false,
        val operationJournal: List<OperationJournalEntry> = emptyList(),
        val activityEvents: List<ActivityEvent> = emptyList(),
        val activityCenterOpen: Boolean = false,
        val journalExportInProgress: Boolean = false,
        val diagnosticsExportInProgress: Boolean = false,
        val batchInstallProgress: List<BatchInstallProgress> = emptyList(),
        val batchUninstallProgress: List<BatchUninstallProgress> = emptyList(),
        val snapshotRestoreProgress: List<SnapshotRestoreProgress> = emptyList(),
        val updateActivationProgress: List<SnapshotRestoreProgress> = emptyList(),
        val protectedVersions: Set<ProtectedVersion> = emptySet(),
        val connectivityStatus: ConnectivityStatus = ConnectivityStatus(ConnectivityState.Unknown),
        val integrityChecks: List<IntegrityCheck> = emptyList(),
        val readRetryStatus: ReadRetryStatus? = null,
        val liveOperationIds: Set<Long> = emptySet(),
        val operationLedgerError: String? = null,
        // Kept in the same CAS state as findings so invalidation fences every publication.
        val localOnlyAuditGeneration: Long = 0L,
    ) : ZephyrUiState
}

class ZephyrViewModel(
    private val repository: SdkmanRepository,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val journalExporter: OperationJournalExporter = UnconfiguredJournalExporter,
    private val diagnosticsExporter: DiagnosticsExporter = UnconfiguredDiagnosticsExporter,
    private val readRetryDelaysMillis: List<Long> = listOf(500L, 1_500L),
    private val operationStore: OperationStore = NoOpOperationStore,
    private val activityStore: ActivityStore = NoOpActivityStore,
    private val clock: () -> Long = ::currentEpochMillis,
    localOnlyScanConcurrency: Int = LocalOnlyAudit.DEFAULT_CONCURRENCY_LIMIT,
) {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val _state = MutableStateFlow<ZephyrUiState>(ZephyrUiState.Loading)
    val state: StateFlow<ZephyrUiState> = _state
    private val operationMutex = Mutex()
    private val connectivityMutex = Mutex()
    private val activityStoreMutex = Mutex()
    private val localOnlyAudit = LocalOnlyAudit(localOnlyScanConcurrency)
    private val localOnlyBaseline = MutableStateFlow<LocalOnlyAuditBaseline?>(null)
    private var nextActivityId = 1L
    private val detailGeneration = MutableStateFlow(0L)
    private var detailReadJob: Job? = null
    private var scheduledMetadataRefresh = false
    private val operations = OperationCoordinator(repository, operationStore, dispatcher, clock, ::operationCompleted)

    init {
        scope.launch {
            operations.state.collect { operationState ->
                _state.updateReady {
                    it.copy(
                        operationJournal = operationState.entries,
                        liveOperationIds = operationState.liveOperationIds,
                        operationLedgerError = operationState.ledgerFailure,
                        errorMessage = operationState.ledgerFailure ?: it.errorMessage,
                    )
                }
                operationState.entries.firstOrNull()?.let { entry ->
                    publishBatchProgress(entry.transaction, entry.transaction.commands, entry.steps.map { it.status }, entry.steps.map { it.outcome })
                }
            }
        }
        refreshAll()
    }

    fun close() {
        operations.close()
        scope.cancel()
    }

    suspend fun shutdownAndJoin(timeoutMillis: Long = 10_000L): Boolean {
        val persisted = operations.shutdownAndJoin(timeoutMillis)
        scope.cancel()
        val joined = withTimeoutOrNull(timeoutMillis) { scope.coroutineContext[Job]?.join(); true } ?: false
        return persisted && joined
    }

    suspend fun retryOperationPersistence(): Boolean = operations.retryPersistence()

    fun refreshAll() {
        launchOperation {
            _state.value = ZephyrUiState.Loading
            runCatchingCancellable {
                val detected = repository.detect()
                if (!detected.isInstalled) {
                    _state.value = ZephyrUiState.SdkmanMissing(detected.reason ?: "SDKMAN could not be found.")
                } else {
                    val version = repository.cliVersion()
                    val status = detected.copy(cliVersion = version)
                    val candidates = repository.installedCandidates()
                    val cachedCatalog = repository.cachedCatalog()
                    val protectedVersions = loadProtectedVersions()
                    val integrityChecks = loadIntegrityChecks()
                    val operationJournal = loadAndReconcileOperations()
                    val activityEvents = loadActivityEvents()
                    _state.value = ZephyrUiState.Ready(
                        sdkmanStatus = status,
                        route = ZephyrRoute.Overview,
                        previousRoute = null,
                        candidates = candidates,
                        catalog = cachedCatalog?.items.orEmpty().withInstalledCandidates(candidates),
                        catalogCachedAtEpochMillis = cachedCatalog?.cachedAtEpochMillis,
                        catalogIsCached = cachedCatalog != null,
                        selectedCandidate = null,
                        isRefreshing = false,
                        isCatalogLoading = false,
                        localOnlyScanInProgress = false,
                        errorMessage = operations.state.value.ledgerFailure,
                        lastOutcome = "Loaded ${candidates.size} installed SDKMAN package(s).",
                        operationJournal = operationJournal,
                        operationLedgerError = operations.state.value.ledgerFailure,
                        liveOperationIds = operations.state.value.liveOperationIds,
                        activityEvents = activityEvents,
                        protectedVersions = protectedVersions,
                        integrityChecks = integrityChecks,
                    )
                    refreshConnectivity()
                }
            }.onFailure { failure ->
                ZephyrLogger.error("Initial SDKMAN load failed.", failure)
                runCatchingCancellable {
                    val detected = repository.detect()
                    if (detected.isInstalled) {
                        val candidates = repository.installedCandidates()
                        val cachedCatalog = repository.cachedCatalog()
                        val operationJournal = loadAndReconcileOperations()
                        val activityEvents = loadActivityEvents()
                        _state.value = ZephyrUiState.Ready(
                            sdkmanStatus = detected.copy(cliVersion = repository.cliVersion()),
                            route = ZephyrRoute.BrowseSdks,
                            previousRoute = null,
                            candidates = candidates,
                            catalog = cachedCatalog?.items.orEmpty().withInstalledCandidates(candidates),
                            catalogCachedAtEpochMillis = cachedCatalog?.cachedAtEpochMillis,
                            catalogIsCached = cachedCatalog != null,
                            selectedCandidate = null,
                            isRefreshing = false,
                            isCatalogLoading = false,
                            localOnlyScanInProgress = false,
                            errorMessage = "SDKMAN catalog failed: ${failure.message}",
                            lastOutcome = null,
                            operationJournal = operationJournal,
                        operationLedgerError = operations.state.value.ledgerFailure,
                        liveOperationIds = operations.state.value.liveOperationIds,
                            activityEvents = activityEvents,
                            protectedVersions = loadProtectedVersions(),
                            integrityChecks = loadIntegrityChecks(),
                        )
                        refreshConnectivity()
                    } else {
                        _state.value = ZephyrUiState.SdkmanMissing(detected.reason ?: failure.message ?: "SDKMAN could not be found.")
                    }
                }.onFailure { fallbackFailure ->
                    ZephyrLogger.error("Failed to build fallback UI after SDKMAN load failure.", fallbackFailure)
                    _state.value = ZephyrUiState.SdkmanMissing(failure.message ?: "SDKMAN could not be loaded.")
                }
            }
        }
    }

    fun navigate(route: ZephyrRoute) {
        if (_state.value !is ZephyrUiState.Ready) return
        detailGeneration.update { it + 1 }
        detailReadJob?.cancel()
        _state.updateReady { ready ->
            ready.copy(
                route = route,
                previousRoute = if (route is ZephyrRoute.JdkDetail || route is ZephyrRoute.SdkDetail) ready.route else null,
                selectedCandidate = null,
                detailLoadingCandidate = null,
                errorMessage = null,
            )
        }
        when (route) {
            is ZephyrRoute.JdkDetail -> loadDetail(route.candidate)
            is ZephyrRoute.SdkDetail -> loadDetail(route.candidate)
            ZephyrRoute.BrowseJdks -> {
                ensureCatalog()
                loadDetail("java")
            }
            ZephyrRoute.BrowseSdks -> ensureCatalog()
            ZephyrRoute.UpdateCenter -> ensureCatalog()
            ZephyrRoute.Storage -> refreshStorage()
            else -> Unit
        }
    }

    fun goBack() {
        if (_state.value !is ZephyrUiState.Ready) return
        detailGeneration.update { it + 1 }
        detailReadJob?.cancel()
        _state.updateReady { ready ->
            ready.copy(
                route = ready.previousRoute ?: ZephyrRoute.Overview,
                previousRoute = null,
                selectedCandidate = null,
                detailLoadingCandidate = null,
                errorMessage = null,
            )
        }
    }

    fun clearMessages() {
        val currentId = (_state.value as? ZephyrUiState.Ready)
            ?.activityEvents
            ?.firstOrNull { !it.acknowledged }
            ?.id
        _state.updateReady {
            it.copy(
                errorMessage = null,
                lastOutcome = null,
                activityEvents = it.activityEvents.map { event ->
                    if (event.id == currentId) event.copy(acknowledged = true) else event
                },
            )
        }
        persistActivityEventsAsync()
    }

    fun toggleActivityCenter() {
        _state.updateReady { it.copy(activityCenterOpen = !it.activityCenterOpen) }
    }

    fun dismissActivity(eventId: Long) {
        _state.updateReady {
            it.copy(
                activityEvents = it.activityEvents.map { event ->
                    if (event.id == eventId) event.copy(acknowledged = true) else event
                },
            )
        }
        persistActivityEventsAsync()
    }

    fun handleActivityAction(action: ActivityAction) {
        when (action) {
            ActivityAction.OpenTaskCenter -> navigate(ZephyrRoute.History)
        }
        _state.updateReady { it.copy(activityCenterOpen = false) }
    }

    fun requestTransaction(transaction: SdkmanTransaction) {
        val ready = _state.value as? ZephyrUiState.Ready ?: return
        if (ready.operationLedgerError != null) {
            _state.updateReady { it.copy(errorMessage = ready.operationLedgerError) }
            return
        }
        if (ready.hasActiveOperation() || ready.pendingTransaction != null) return
        if (transaction is SdkmanTransaction.CleanLocalOnly) {
            val eligible = ready.trustedCleanupVersions(transaction.candidate)
            if (transaction.versions.isEmpty() || transaction.versions.any { it !in eligible }) {
                _state.updateReady {
                    it.copy(
                        errorMessage = "Only findings completed with trusted evidence in the latest local-only audit can be reviewed for cleanup.",
                    )
                }
                return
            }
        }
        _state.updateReady {
            it.copy(
                transactionPreviewLoading = true,
                pendingTransactionDiskImpact = null,
            )
        }
        scope.launch {
            if (transaction.requiresNetwork && !checkOnline()) {
                _state.updateReady {
                    it.copy(
                        transactionPreviewLoading = false,
                        errorMessage = "This operation requires the SDKMAN service, but Zephyr is offline.",
                    )
                }
                return@launch
            }
            val estimate = runCatchingCancellable {
                repository.estimateDiskImpact(transaction)
            }.getOrElse { failure ->
                ZephyrLogger.warn("Unable to estimate transaction disk impact.", failure)
                DiskImpactEstimate(
                    kind = DiskImpactKind.Unknown,
                    confidence = EstimateConfidence.Unknown,
                    explanation = "Disk impact could not be measured: ${failure.message ?: "unknown error"}.",
                )
            }
            _state.updateReady {
                it.copy(
                    pendingTransaction = transaction,
                    pendingTransactionDiskImpact = estimate,
                    transactionPreviewLoading = false,
                )
            }
        }
    }

    fun refreshConnectivity() {
        if (_state.value !is ZephyrUiState.Ready) return
        _state.updateReady {
            it.copy(connectivityStatus = it.connectivityStatus.copy(state = ConnectivityState.Checking))
        }
        scope.launch {
            checkOnline()
        }
    }

    fun refreshIntegrity() {
        launchOperation {
            if (!beginRefresh()) return@launchOperation
            runCatchingCancellable {
                retryRead(RetryableReadOperation.IntegrityChecks) {
                    repository.integrityChecks()
                }
            }.onSuccess { checks ->
                _state.updateReady {
                    it.copy(
                        integrityChecks = checks,
                        isRefreshing = false,
                        lastOutcome = "SDKMAN integrity checks completed.",
                        errorMessage = null,
                    )
                }
            }.onFailure { failure ->
                ZephyrLogger.warn("SDKMAN integrity checks failed.", failure)
                fail("SDKMAN integrity checks failed: ${failure.message}")
            }
        }
    }

    fun retryTransaction(transaction: SdkmanTransaction) {
        val retry = if (transaction is SdkmanTransaction.CleanLocalOnly) {
            val verified = (_state.value as? ZephyrUiState.Ready)
                ?.trustedCleanupVersions(transaction.candidate)
                .orEmpty()
            val remaining = transaction.versions.filter { it in verified }
            if (remaining.isEmpty()) {
                _state.updateReady {
                    it.copy(lastOutcome = "Scan local-only versions again before retrying cleanup.")
                }
                return
            }
            transaction.copy(versions = remaining)
        } else {
            transaction
        }
        requestTransaction(retry)
    }

    fun requestResumeOperation(entryId: Long) {
        scope.launch {
            when (val review = operations.reviewRemaining(entryId)) {
                OperationReview.Busy -> _state.updateReady { it.copy(errorMessage = "This task is still owned by a live operation.") }
                is OperationReview.Rejected -> _state.updateReady { it.copy(errorMessage = review.reason) }
                is OperationReview.Reviewed -> {
                    if (review.transaction != null) requestTransaction(review.transaction)
                    else _state.updateReady {
                        it.copy(lastOutcome = if (review.entry.steps.all { step -> step.status == OperationStepStatus.Succeeded }) {
                            "Every task step is already satisfied."
                        } else {
                            "No task steps can be resumed safely. Review steps marked Could not verify."
                        })
                    }
                }
            }
        }
    }

    fun dismissTransaction() {
        _state.updateReady {
            it.copy(
                pendingTransaction = null,
                pendingTransactionDiskImpact = null,
                transactionPreviewLoading = false,
            )
        }
    }

    fun confirmTransaction(): Deferred<OperationAdmission>? {
        val transaction = (_state.value as? ZephyrUiState.Ready)?.pendingTransaction ?: return null
        _state.updateReady {
            it.copy(pendingTransaction = null, pendingTransactionDiskImpact = null)
        }
        return submitTransaction(transaction)
    }

    private fun submitTransaction(transaction: SdkmanTransaction): Deferred<OperationAdmission>? {
        if (_state.value !is ZephyrUiState.Ready) return null
        localOnlyBaseline.value = null
        _state.updateReady {
            it.copy(
                localOnlyAuditGeneration = it.localOnlyAuditGeneration + 1,
                localOnlyScanProgress = null,
                localOnlyScanInProgress = false,
                transactionPreviewLoading = true,
                isRefreshing = true,
                errorMessage = null,
            )
        }
        val admission = operations.submit(transaction)
        scope.launch {
            when (val result = admission.await()) {
                is OperationAdmission.Accepted -> _state.updateReady { it.copy(transactionPreviewLoading = false) }
                is OperationAdmission.Rejected -> _state.updateReady {
                    it.copy(transactionPreviewLoading = false, isRefreshing = false, errorMessage = result.reason)
                }
            }
        }
        return admission
    }

    fun exportJournal() {
        val entries = (_state.value as? ZephyrUiState.Ready)?.operationJournal.orEmpty()
        if (entries.isEmpty()) {
            _state.updateReady { it.copy(lastOutcome = "The operation journal is empty.") }
            return
        }
        scope.launch {
            _state.updateReady { it.copy(journalExportInProgress = true, errorMessage = null) }
            runCatchingCancellable {
                journalExporter.export(entries)
            }.onSuccess { result ->
                _state.updateReady {
                    it.copy(
                        journalExportInProgress = false,
                        lastOutcome = "Exported ${result.exportedEntries} journal entries to ${result.path}.",
                    )
                }
            }.onFailure { failure ->
                ZephyrLogger.warn("Operation journal export failed.", failure)
                _state.updateReady {
                    it.copy(
                        journalExportInProgress = false,
                        errorMessage = "Operation journal export failed: ${failure.message}",
                    )
                }
            }
        }
    }

    fun exportDiagnostics() {
        val ready = _state.value as? ZephyrUiState.Ready ?: return
        val snapshot = DiagnosticsSnapshot(
            generatedAtEpochMillis = clock(),
            sdkmanStatus = ready.sdkmanStatus,
            connectivityStatus = ready.connectivityStatus,
            integrityChecks = ready.integrityChecks,
            installedCandidates = ready.candidates.size,
            installedVersions = ready.candidates.sumOf { candidate ->
                candidate.installedVersions.count { it.isInstalled }
            },
            localOnlyVersions = ready.candidates.sumOf { it.localOnlyVersionCount },
            protectedVersions = ready.protectedVersions.size,
            journal = ready.operationJournal,
        )
        scope.launch {
            _state.updateReady { it.copy(diagnosticsExportInProgress = true, errorMessage = null) }
            runCatchingCancellable {
                diagnosticsExporter.export(snapshot)
            }.onSuccess { result ->
                _state.updateReady {
                    it.copy(
                        diagnosticsExportInProgress = false,
                        lastOutcome = "Exported a redacted support bundle to ${result.path}.",
                    )
                }
            }.onFailure { failure ->
                ZephyrLogger.warn("Support bundle export failed.", failure)
                _state.updateReady {
                    it.copy(
                        diagnosticsExportInProgress = false,
                        errorMessage = "Support bundle export failed: ${failure.message}",
                    )
                }
            }
        }
    }

    fun setVersionProtected(candidate: String, version: String, protected: Boolean) {
        launchOperation {
            if (!beginRefresh()) return@launchOperation
            runCatchingCancellable {
                val outcome = repository.setVersionProtected(candidate, version, protected)
                outcome to repository.protectedVersions()
            }.onSuccess { (outcome, protectedVersions) ->
                _state.updateReady {
                    it.copy(
                        protectedVersions = protectedVersions,
                        storageInventory = it.storageInventory?.copy(
                            versions = it.storageInventory.versions.map { entry ->
                                if (entry.candidate == candidate && entry.version == version) {
                                    entry.copy(isProtected = ProtectedVersion(candidate, version) in protectedVersions)
                                } else {
                                    entry
                                }
                            },
                        ),
                        isRefreshing = false,
                        lastOutcome = outcome.message,
                        errorMessage = if (outcome.success) null else outcome.message,
                    )
                }
            }.onFailure { failure ->
                ZephyrLogger.warn("Protected-version update failed.", failure)
                fail("Protected-version update failed: ${failure.message}")
            }
        }
    }

    fun refreshInstalled() {
        launchOperation {
            if (!beginRefresh()) return@launchOperation
            runCatchingCancellable {
                val candidates = retryRead(RetryableReadOperation.InstalledCandidates) {
                    repository.installedCandidates()
                }
                _state.updateReady {
                    it.copy(
                        candidates = candidates,
                        catalog = it.catalog.withInstalledCandidates(candidates),
                        storageInventory = null,
                        localOnlyScanProgress = null,
                        localOnlyAuditGeneration = it.localOnlyAuditGeneration + 1,
                        isRefreshing = false,
                        errorMessage = null,
                    )
                }
                localOnlyBaseline.value = null
                refreshSelectedDetailIfNeeded()
            }.onFailure {
                ZephyrLogger.warn("Refresh failed.", it)
                fail("Refresh failed: ${it.message}")
            }
        }
    }

    fun refreshStorage() {
        launchOperation {
            val ready = _state.value as? ZephyrUiState.Ready ?: return@launchOperation
            if (
                ready.storageScanInProgress ||
                ready.isRefreshing ||
                ready.isCatalogLoading ||
                ready.localOnlyScanInProgress ||
                ready.transactionPreviewLoading
            ) {
                return@launchOperation
            }
            _state.updateReady {
                it.copy(
                    storageScanInProgress = true,
                    errorMessage = null,
                )
            }
            runCatchingCancellable {
                repository.storageInventory(ready.candidates)
            }.onSuccess { inventory ->
                _state.updateReady {
                    it.copy(
                        storageInventory = inventory,
                        storageScanInProgress = false,
                        lastOutcome = "Measured ${inventory.versions.size} installed version payload(s).",
                    )
                }
            }.onFailure { failure ->
                ZephyrLogger.warn("Storage inventory failed.", failure)
                fail("Storage inventory failed: ${failure.message}")
            }
        }
    }

    fun refreshMetadata() = submitTransaction(SdkmanTransaction.RefreshMetadata)

    fun refreshMetadataIfIdle() {
        val ready = _state.value as? ZephyrUiState.Ready ?: return
        if (ready.hasActiveOperation() || ready.pendingTransaction != null) return
        scope.launch {
            if (!checkOnline()) return@launch
            val current = _state.value as? ZephyrUiState.Ready ?: return@launch
            if (current.hasActiveOperation() || current.pendingTransaction != null) return@launch
            scheduledMetadataRefresh = true
            submitTransaction(SdkmanTransaction.RefreshMetadata)
        }
    }

    fun checkSdkmanUpdates() = submitTransaction(SdkmanTransaction.SelfUpdate)

    fun scanLocalOnly() {
        scanLocalOnly(retryFailuresOnly = false)
    }

    fun retryFailedLocalOnlyReads() {
        scanLocalOnly(retryFailuresOnly = true)
    }

    private fun scanLocalOnly(retryFailuresOnly: Boolean) {
        val current = _state.value as? ZephyrUiState.Ready ?: return
        if (current.pendingTransaction != null || current.transactionPreviewLoading) {
            _state.updateReady {
                it.copy(errorMessage = "Finish or dismiss the pending transaction review before starting an audit.")
            }
            return
        }
        if (current.hasActiveOperation() || operations.state.value.liveOperationIds.isNotEmpty()) return
        launchOperation {
            var admittedReady: ZephyrUiState.Ready? = null
            _state.updateReady { ready ->
                admittedReady = null
                if (retryFailuresOnly && ready.localOnlyScanProgress?.failures.isNullOrEmpty()) {
                    ready
                } else if (ready.pendingTransaction != null || ready.transactionPreviewLoading) {
                    ready.copy(
                        errorMessage = "Finish or dismiss the pending transaction review before starting an audit.",
                    )
                } else if (ready.hasActiveOperation() || operations.state.value.liveOperationIds.isNotEmpty()) {
                    ready
                } else {
                    admittedReady = ready
                    ready.copy(localOnlyScanInProgress = true, errorMessage = null)
                }
            }
            val ready = admittedReady ?: return@launchOperation
            val generation = ready.localOnlyAuditGeneration
            val previousProgress = ready.localOnlyScanProgress
            val failedNames = previousProgress?.failures.orEmpty().mapTo(linkedSetOf()) { it.candidate }
            if (retryFailuresOnly && failedNames.isEmpty()) return@launchOperation
            if (!checkOnline(auditGeneration = generation)) {
                fail("Local-only scanning requires the SDKMAN service, but Zephyr is offline.", auditGeneration = generation)
                return@launchOperation
            }
            if (!isCurrentLocalOnlyAudit(generation)) return@launchOperation
            runCatchingCancellable {
                val retryBaseline = localOnlyBaseline.value?.takeIf { it.generation == generation }
                val snapshot = if (retryFailuresOnly) {
                    requireNotNull(retryBaseline?.installedSnapshot) {
                        "The installed snapshot for failed reads is no longer available."
                    }
                } else {
                    repository.installedCandidates().toList()
                }
                if (!isCurrentLocalOnlyAudit(generation)) return@runCatchingCancellable
                val baseline = if (retryFailuresOnly) {
                    requireNotNull(retryBaseline?.displayBaseline)
                } else {
                    snapshot.map { local ->
                        ready.candidates.firstOrNull { it.name == local.name } ?: local
                    }
                }
                localOnlyBaseline.value = LocalOnlyAuditBaseline(generation, snapshot, baseline)
                val targets = if (retryFailuresOnly) failedNames else snapshot.mapTo(linkedSetOf(), Candidate::name)
                localOnlyAudit.scan(
                    installedSnapshot = snapshot,
                    initialProgress = previousProgress.takeIf { retryFailuresOnly },
                    targetCandidates = targets,
                    readCandidate = repository::mergedCandidate,
                ) { progress ->
                    val findings = progress.candidates
                        .mapNotNull { item -> item.finding?.let { item.candidate to it } }
                        .toMap()
                    val audited = baseline.map { candidate -> findings[candidate.name] ?: candidate }
                    _state.updateReady {
                        if (it.localOnlyAuditGeneration != generation) it else it.copy(
                            candidates = audited,
                            storageInventory = null,
                            localOnlyScanInProgress = progress.running,
                            localOnlyScanProgress = progress,
                            selectedCandidate = it.selectedCandidate?.let { selected ->
                                audited.firstOrNull { candidate -> candidate.name == selected.name } ?: selected
                            },
                            lastOutcome = if (!progress.running) {
                                "Audited ${progress.completed} of ${progress.total} installed candidates; " +
                                    "${progress.failures.size} read(s) failed."
                            } else {
                                it.lastOutcome
                            },
                        )
                    }
                }
            }.onFailure {
                if (isCurrentLocalOnlyAudit(generation)) {
                    ZephyrLogger.warn("Local-only scan failed.", it)
                    fail("Local-only scan failed: ${it.message}", auditGeneration = generation)
                }
            }
        }
    }

    fun install(candidate: String, version: String) = submitTransaction(SdkmanTransaction.Install(candidate, version))

    private fun operationCompleted(entry: OperationJournalEntry) {
        localOnlyBaseline.value = null
        val message = entry.outcome.orEmpty()
        _state.updateReady {
            it.copy(
                operationJournal = operations.state.value.entries,
                liveOperationIds = operations.state.value.liveOperationIds,
                storageInventory = null,
                localOnlyScanProgress = null,
                localOnlyScanInProgress = false,
                localOnlyAuditGeneration = it.localOnlyAuditGeneration + 1,
                transactionPreviewLoading = false,
                isRefreshing = false,
                lastOutcome = message,
                errorMessage = operations.state.value.ledgerFailure ?: if (entry.status == OperationStatus.Succeeded) null else {
                    if (entry.transaction == SdkmanTransaction.SelfUpdate) "SDKMAN self-update failed: $message" else message
                },
            )
        }
        recordActivity(message, when (entry.status) {
            OperationStatus.Succeeded -> ActivitySeverity.Success
            OperationStatus.Failed -> ActivitySeverity.Error
            else -> ActivitySeverity.Warning
        }, entry.completedAtEpochMillis ?: clock(), ActivityAction.OpenTaskCenter)
        if (entry.status == OperationStatus.Interrupted) return
        refreshAfterOperation(entry)
    }

    /** Read failures are presentation warnings, never execution receipts. */
    private fun refreshAfterOperation(entry: OperationJournalEntry) {
        val scheduled = scheduledMetadataRefresh && entry.transaction == SdkmanTransaction.RefreshMetadata
        if (entry.transaction == SdkmanTransaction.RefreshMetadata) scheduledMetadataRefresh = false
        launchQueuedOperation {
            _state.updateReady { it.copy(isRefreshing = true) }
            runCatchingCancellable {
                when (entry.transaction) {
                    SdkmanTransaction.RefreshMetadata -> {
                        val catalog = repository.catalog(refreshMetadata = false)
                        _state.updateReady {
                            it.copy(
                                catalog = catalog,
                                catalogCachedAtEpochMillis = null,
                                catalogIsCached = false,
                                sdkmanStatus = it.sdkmanStatus.copy(metadataStatus = if (entry.status == OperationStatus.Succeeded) {
                                    CandidateMetadataStatus.Refreshed
                                } else CandidateMetadataStatus.Failed(entry.outcome.orEmpty())),
                                lastOutcome = if (scheduled) "Scheduled metadata refresh completed. Loaded ${catalog.size} packages."
                                    else "${entry.outcome} Loaded ${catalog.size} packages.",
                            )
                        }
                    }
                    SdkmanTransaction.SelfUpdate -> {
                        val version = repository.cliVersion()
                        _state.updateReady {
                            it.copy(sdkmanStatus = it.sdkmanStatus.copy(
                                cliVersion = version,
                                selfUpdateStatus = when {
                                    entry.status != OperationStatus.Succeeded -> SdkmanSelfUpdateStatus.Failed(entry.outcome.orEmpty())
                                    entry.outcome == "SDKMAN was updated." -> SdkmanSelfUpdateStatus.Updated
                                    else -> SdkmanSelfUpdateStatus.UpToDate
                                },
                            ))
                        }
                    }
                    else -> {
                        val candidates = repository.installedCandidates()
                        _state.updateReady {
                            it.copy(candidates = candidates, catalog = it.catalog.withInstalledCandidates(candidates))
                        }
                        refreshSelectedDetailIfNeeded()
                    }
                }
            }.onFailure { failure ->
                val warning = if (entry.transaction == SdkmanTransaction.RefreshMetadata) {
                    "Candidate metadata refresh failed: ${failure.message}"
                } else "Operation completed; refresh failed: ${failure.message}"
                _state.updateReady { it.copy(errorMessage = operations.state.value.ledgerFailure ?: warning) }
            }
            _state.updateReady { it.copy(isRefreshing = false, isCatalogLoading = false) }
        }
    }

    private fun publishBatchProgress(
        transaction: SdkmanTransaction,
        commands: List<PlannedSdkmanCommand>,
        statuses: List<OperationStepStatus>,
        outcomes: List<String?>,
    ) {
        val batchStatuses = statuses.map(OperationStepStatus::toBatchItemStatus)
        _state.updateReady { ready ->
            when (transaction) {
                is SdkmanTransaction.BatchInstall -> ready.copy(
                    batchInstallProgress = transaction.targets.mapIndexed { index, target ->
                        BatchInstallProgress(target, batchStatuses[index], outcomes[index])
                    },
                )
                is SdkmanTransaction.BatchUninstall -> ready.copy(
                    batchUninstallProgress = transaction.targets.mapIndexed { index, target ->
                        BatchUninstallProgress(target, batchStatuses[index], outcomes[index])
                    },
                )
                is SdkmanTransaction.SnapshotRestore -> ready.copy(
                    snapshotRestoreProgress = commands.mapIndexed { index, command ->
                        SnapshotRestoreProgress(command, batchStatuses[index], outcomes[index])
                    },
                )
                is SdkmanTransaction.ToolchainActivation -> ready.copy(
                    snapshotRestoreProgress = commands.mapIndexed { index, command ->
                        SnapshotRestoreProgress(command, batchStatuses[index], outcomes[index])
                    },
                )
                is SdkmanTransaction.UpdateActivation -> ready.copy(
                    updateActivationProgress = commands.mapIndexed { index, command ->
                        SnapshotRestoreProgress(command, batchStatuses[index], outcomes[index])
                    },
                )
                else -> ready
            }
        }
    }

    fun uninstall(candidate: String, version: String) = submitTransaction(SdkmanTransaction.Uninstall(candidate, version))

    fun setDefault(candidate: String, version: String) = submitTransaction(SdkmanTransaction.SetDefault(candidate, version))

    fun cleanLocalOnly(candidate: String, versions: List<String>) {
        requestTransaction(SdkmanTransaction.CleanLocalOnly(candidate, versions))
    }

    private fun ensureCatalog() {
        val ready = _state.value as? ZephyrUiState.Ready ?: return
        if (ready.catalog.isNotEmpty() && !ready.catalogIsCached) return
        launchQueuedOperation {
            val current = _state.value as? ZephyrUiState.Ready ?: return@launchQueuedOperation
            if (current.catalog.isNotEmpty() && !current.catalogIsCached) return@launchQueuedOperation
            _state.updateReady { it.copy(isCatalogLoading = true) }
            if (!checkOnline()) {
                if (current.catalogIsCached && current.catalog.isNotEmpty()) {
                    _state.updateReady {
                        it.copy(
                            isCatalogLoading = false,
                            errorMessage = null,
                            lastOutcome = "Showing cached candidate metadata while offline.",
                        )
                    }
                } else {
                    fail("Catalog loading requires the SDKMAN service, but Zephyr is offline.")
                }
                return@launchQueuedOperation
            }
            runCatchingCancellable {
                val catalog = retryRead(RetryableReadOperation.CandidateCatalog) {
                    repository.catalog(refreshMetadata = true)
                }
                _state.updateReady {
                    it.copy(
                        catalog = catalog,
                        catalogCachedAtEpochMillis = null,
                        catalogIsCached = false,
                        isCatalogLoading = false,
                        lastOutcome = "Loaded ${catalog.size} SDKMAN packages.",
                    )
                }
            }.onFailure {
                ZephyrLogger.warn("Catalog load failed.", it)
                fail("Catalog load failed: ${it.message}")
            }
        }
    }

    private fun loadDetail(candidate: String, mutationRefresh: Boolean = false) {
        val generation = detailGeneration.updateAndGet { it + 1 }
        detailReadJob?.cancel()
        detailReadJob = launchQueuedOperation {
            var shouldLoad = false
            _state.update { state ->
                if (state is ZephyrUiState.Ready && state.displaysCandidate(candidate) && generation == detailGeneration.value) {
                    shouldLoad = true
                    state.copy(detailLoadingCandidate = candidate, errorMessage = null)
                } else {
                    state
                }
            }
            if (!shouldLoad) return@launchQueuedOperation
            if (!checkOnline()) {
                if (generation == detailGeneration.value) {
                    fail("Version loading requires the SDKMAN service, but Zephyr is offline.")
                }
                return@launchQueuedOperation
            }
            runCatchingCancellable {
                val merged = retryRead(RetryableReadOperation.CandidateDetail) {
                    repository.mergedCandidate(candidate)
                }
                _state.updateReady {
                    if (it.displaysCandidate(candidate) && generation == detailGeneration.value) {
                        it.copy(
                            selectedCandidate = merged?.takeIf { detail -> detail.name == candidate },
                            detailLoadingCandidate = null,
                            candidates = it.candidates.replaceCandidate(merged),
                            storageInventory = null,
                        )
                    } else {
                        it
                    }
                }
            }.onFailure {
                if (generation == detailGeneration.value && (_state.value as? ZephyrUiState.Ready)?.displaysCandidate(candidate) == true) {
                    ZephyrLogger.warn("Version load failed for $candidate.", it)
                    fail(if (mutationRefresh) "Operation completed; detail refresh failed: ${it.message}" else "Version load failed: ${it.message}")
                }
            }
        }
    }

    private fun refreshSelectedDetailIfNeeded() {
        val ready = _state.value as? ZephyrUiState.Ready ?: return
        val selected = ready.selectedCandidate ?: return
        loadDetail(selected.name, mutationRefresh = true)
    }

    private fun beginRefresh(): Boolean {
        if (_state.value !is ZephyrUiState.Ready) return false
        _state.updateReady { it.copy(isRefreshing = true, errorMessage = null, lastOutcome = null) }
        return true
    }

    private fun launchOperation(block: suspend () -> Unit) {
        scope.launch {
            if (!operationMutex.tryLock()) return@launch
            try {
                block()
            } finally {
                operationMutex.unlock()
            }
        }
    }

    private fun launchQueuedOperation(block: suspend () -> Unit): Job = scope.launch {
        operationMutex.withLock { block() }
    }

    private fun isCurrentLocalOnlyAudit(generation: Long): Boolean =
        (_state.value as? ZephyrUiState.Ready)?.localOnlyAuditGeneration == generation

    private fun fail(message: String, auditGeneration: Long? = null) {
        var published = false
        _state.updateReady {
            published = auditGeneration == null || it.localOnlyAuditGeneration == auditGeneration
            if (!published) it else it.copy(
                isRefreshing = false,
                isCatalogLoading = false,
                localOnlyScanInProgress = false,
                storageScanInProgress = false,
                detailLoadingCandidate = null,
                errorMessage = message,
            )
        }
        if (!published) return
        ZephyrLogger.warn(message)
        recordActivity(message, ActivitySeverity.Error)
    }

    private fun recordActivity(
        message: String,
        severity: ActivitySeverity,
        timestampEpochMillis: Long = clock(),
        action: ActivityAction? = null,
    ) {
        val normalized = message.trim().take(MAX_ACTIVITY_MESSAGE_LENGTH)
        if (normalized.isEmpty()) return
        val event = ActivityEvent(
            id = nextActivityId++,
            timestampEpochMillis = timestampEpochMillis,
            severity = severity,
            message = normalized,
            action = action,
        )
        _state.updateReady {
            it.copy(activityEvents = (listOf(event) + it.activityEvents).take(MAX_ACTIVITY_EVENTS))
        }
        persistActivityEventsAsync()
    }

    private suspend fun loadActivityEvents(): List<ActivityEvent> {
        val loaded = runCatchingCancellable { activityStore.load() }
            .getOrElse { failure ->
                ZephyrLogger.warn("Unable to load the activity ledger.", failure)
                emptyList()
            }
            .sortedByDescending(ActivityEvent::timestampEpochMillis)
            .take(MAX_ACTIVITY_EVENTS)
        nextActivityId = (loaded.maxOfOrNull(ActivityEvent::id) ?: 0L) + 1L
        return loaded
    }

    private fun persistActivityEventsAsync() {
        scope.launch {
            activityStoreMutex.withLock {
                val events = (_state.value as? ZephyrUiState.Ready)?.activityEvents.orEmpty()
                runCatchingCancellable { activityStore.save(events) }
                    .onFailure { ZephyrLogger.warn("Unable to persist the activity ledger.", it) }
            }
        }
    }

    private suspend fun loadAndReconcileOperations(): List<OperationJournalEntry> = operations.initialize().entries

    private suspend fun loadProtectedVersions(): Set<ProtectedVersion> =
        runCatchingCancellable {
            repository.protectedVersions()
        }.getOrElse { failure ->
            ZephyrLogger.warn("Unable to load protected SDKMAN versions.", failure)
            emptySet()
        }

    private suspend fun loadIntegrityChecks(): List<IntegrityCheck> =
        runCatchingCancellable {
            repository.integrityChecks()
        }.getOrElse { failure ->
            ZephyrLogger.warn("Unable to run SDKMAN integrity checks.", failure)
            emptyList()
        }

    private suspend fun <T> retryRead(
        operation: RetryableReadOperation,
        block: suspend () -> T,
    ): T {
        val maximumAttempts = readRetryDelaysMillis.size + 1
        var lastFailure: Exception? = null
        repeat(maximumAttempts) { index ->
            try {
                val result = block()
                _state.updateReady { it.copy(readRetryStatus = null) }
                return result
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                lastFailure = exception
                val nextAttempt = index + 2
                if (nextAttempt <= maximumAttempts) {
                    _state.updateReady {
                        it.copy(
                            readRetryStatus = ReadRetryStatus(operation, nextAttempt, maximumAttempts),
                        )
                    }
                    delay(readRetryDelaysMillis[index])
                }
            }
        }
        _state.updateReady { it.copy(readRetryStatus = null) }
        throw requireNotNull(lastFailure)
    }

    private suspend fun checkOnline(auditGeneration: Long? = null): Boolean = connectivityMutex.withLock {
        val status = runCatchingCancellable {
            repository.checkConnectivity()
        }.getOrElse { failure ->
            ZephyrLogger.warn("SDKMAN connectivity check failed.", failure)
            ConnectivityStatus.from(
                ConnectivityDiagnostic(
                    route = (_state.value as? ZephyrUiState.Ready)
                        ?.connectivityStatus
                        ?.diagnostic
                        ?.route
                        ?: ConnectivityRouteKind.Direct,
                    checkedAtEpochMillis = clock(),
                    latencyMillis = 0,
                    outcome = ConnectivityOutcome.Indeterminate,
                ),
            )
        }
        _state.updateReady {
            if (auditGeneration == null || it.localOnlyAuditGeneration == auditGeneration) it.copy(connectivityStatus = status) else it
        }
        status.diagnostic?.outcome == ConnectivityOutcome.Online
    }

}

private data class LocalOnlyAuditBaseline(
    val generation: Long,
    val installedSnapshot: List<Candidate>,
    val displayBaseline: List<Candidate>,
)

private fun MutableStateFlow<ZephyrUiState>.updateReady(transform: (ZephyrUiState.Ready) -> ZephyrUiState.Ready) {
    update { state ->
        if (state is ZephyrUiState.Ready) transform(state) else state
    }
}

private suspend inline fun <T> runCatchingCancellable(crossinline block: suspend () -> T): Result<T> =
    try {
        Result.success(block())
    } catch (exception: CancellationException) {
        throw exception
    } catch (exception: Exception) {
        Result.failure(exception)
    }

private fun List<Candidate>.replaceCandidate(candidate: Candidate?): List<Candidate> {
    if (candidate == null) return this
    val index = indexOfFirst { it.name == candidate.name }
    return if (index < 0) this else toMutableList().also { it[index] = candidate }
}

private fun OperationStepStatus.toBatchItemStatus(): BatchItemStatus =
    when (this) {
        OperationStepStatus.Pending -> BatchItemStatus.Pending
        OperationStepStatus.Running -> BatchItemStatus.Running
        OperationStepStatus.Succeeded -> BatchItemStatus.Succeeded
        OperationStepStatus.Skipped -> BatchItemStatus.Skipped
        OperationStepStatus.Failed,
        OperationStepStatus.Interrupted,
        OperationStepStatus.Indeterminate,
        -> BatchItemStatus.Failed
    }

private fun ZephyrUiState.Ready.displaysCandidate(candidate: String): Boolean =
    when (val currentRoute = route) {
        is ZephyrRoute.JdkDetail -> currentRoute.candidate == candidate
        is ZephyrRoute.SdkDetail -> currentRoute.candidate == candidate
        ZephyrRoute.BrowseJdks -> candidate == "java"
        else -> false
    }

private fun ZephyrUiState.Ready.hasActiveOperation(): Boolean =
    liveOperationIds.isNotEmpty() || isRefreshing ||
        isCatalogLoading ||
        localOnlyScanInProgress ||
        storageScanInProgress ||
        detailLoadingCandidate != null ||
        journalExportInProgress ||
        diagnosticsExportInProgress ||
        transactionPreviewLoading

private fun ZephyrUiState.Ready.trustedCleanupVersions(candidate: String): Set<String> {
    val completed = localOnlyScanProgress?.candidates?.firstOrNull {
        it.candidate == candidate && it.status == LocalOnlyCandidateScanStatus.Completed
    }?.finding ?: return emptySet()
    return cleanupEligibility(completed, protectedVersions, evidenceTrusted = true).eligibleVersions.toSet()
}

private object UnconfiguredJournalExporter : OperationJournalExporter {
    override suspend fun export(entries: List<OperationJournalEntry>): com.worxbend.zephyr.domain.JournalExportResult =
        error("Operation journal exporter is not configured.")
}

private object UnconfiguredDiagnosticsExporter : DiagnosticsExporter {
    override suspend fun export(snapshot: DiagnosticsSnapshot): com.worxbend.zephyr.domain.SupportBundleExportResult =
        error("Diagnostics exporter is not configured.")
}

private const val MAX_ACTIVITY_EVENTS = 100
private const val MAX_ACTIVITY_MESSAGE_LENGTH = 1_000
