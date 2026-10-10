package com.worxbend.zephyr.viewmodel

import com.worxbend.zephyr.application.operation.OperationAdmission
import com.worxbend.zephyr.application.operation.OperationCoordinator
import com.worxbend.zephyr.application.operation.OperationReview
import com.worxbend.zephyr.data.DiagnosticsExporter
import com.worxbend.zephyr.data.CandidateMetadataCache
import com.worxbend.zephyr.data.OperationJournalExporter
import com.worxbend.zephyr.data.OperationStore
import com.worxbend.zephyr.data.CommandSatisfaction
import com.worxbend.zephyr.data.ActivityStore
import com.worxbend.zephyr.data.SdkmanRepository
import com.worxbend.zephyr.domain.Candidate
import com.worxbend.zephyr.domain.CandidateCatalogItem
import com.worxbend.zephyr.domain.CandidateKind
import com.worxbend.zephyr.domain.CandidateVersion
import com.worxbend.zephyr.domain.BatchItemStatus
import com.worxbend.zephyr.domain.CommandOutcome
import com.worxbend.zephyr.domain.CommandOutcomeStatus
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
import com.worxbend.zephyr.domain.IntegrityCheckId
import com.worxbend.zephyr.domain.IntegrityStatus
import com.worxbend.zephyr.domain.InstallTarget
import com.worxbend.zephyr.domain.JournalExportResult
import com.worxbend.zephyr.domain.OperationJournalEntry
import com.worxbend.zephyr.domain.OperationStatus
import com.worxbend.zephyr.domain.OperationStep
import com.worxbend.zephyr.domain.OperationStepStatus
import com.worxbend.zephyr.domain.ProtectedVersion
import com.worxbend.zephyr.domain.PlannedSdkmanCommand
import com.worxbend.zephyr.domain.SdkmanCommandAction
import com.worxbend.zephyr.domain.SdkmanSelfUpdateStatus
import com.worxbend.zephyr.domain.SdkmanStatus
import com.worxbend.zephyr.domain.SdkmanTransaction
import com.worxbend.zephyr.domain.SupportBundleExportResult
import com.worxbend.zephyr.domain.UninstallTarget
import com.worxbend.zephyr.domain.UpdateActivationTarget
import com.worxbend.zephyr.domain.ActivityAction
import com.worxbend.zephyr.domain.ActivityEvent
import com.worxbend.zephyr.domain.ActivitySeverity
import com.worxbend.zephyr.domain.StorageInventory
import com.worxbend.zephyr.domain.StorageMeasurement
import com.worxbend.zephyr.domain.VersionStorage
import com.worxbend.zephyr.domain.RemoteAvailability
import com.worxbend.zephyr.domain.RemoteEvidenceState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class ZephyrViewModelTest {
    @Test
    fun activityInboxLoadsAndPersistsAcknowledgementAcrossRestarts() {
        val stored = ActivityEvent(
            id = 19,
            timestampEpochMillis = 500,
            severity = ActivitySeverity.Warning,
            message = "Interrupted task",
            action = ActivityAction.OpenTaskCenter,
        )
        val activityStore = InMemoryActivityStore(listOf(stored))
        val first = ZephyrViewModel(
            repository = FakeSdkmanRepository(),
            dispatcher = testScope(),
            activityStore = activityStore,
        )

        assertEquals(listOf(stored), assertIs<ZephyrUiState.Ready>(first.state.value).activityEvents)
        first.dismissActivity(stored.id)
        assertTrue(activityStore.events.single().acknowledged)
        first.close()

        val second = ZephyrViewModel(
            repository = FakeSdkmanRepository(),
            dispatcher = testScope(),
            activityStore = activityStore,
        )
        assertTrue(assertIs<ZephyrUiState.Ready>(second.state.value).activityEvents.single().acknowledged)
        second.close()
    }

    @Test
    fun openingStorageCenterLoadsSafeInventoryForCurrentCandidates() {
        val candidate = Candidate(
            name = "gradle",
            displayName = "Gradle",
            kind = CandidateKind.Sdk,
            installedVersions = listOf(CandidateVersion("9.0", true, true, true)),
            defaultVersion = "9.0",
            hasLocalOnlyVersions = false,
            localOnlyVersionCount = 0,
            localOnlyVersions = emptyList(),
        )
        val inventory = StorageInventory(
            versions = listOf(
                VersionStorage(
                    candidate = "gradle",
                    candidateDisplayName = "Gradle",
                    version = "9.0",
                    measurement = StorageMeasurement.Exact(42),
                    isDefault = true,
                    isProtected = false,
                    remoteAvailability = RemoteAvailability.Available,
                ),
            ),
            scannedAtEpochMillis = 10,
        )
        val repository = FakeSdkmanRepository(
            installedCandidate = candidate,
            storageInventory = inventory,
        )
        val viewModel = ZephyrViewModel(repository, testScope())

        viewModel.navigate(ZephyrRoute.Storage)

        val state = assertIs<ZephyrUiState.Ready>(viewModel.state.value)
        assertEquals(ZephyrRoute.Storage, state.route)
        assertEquals(inventory, state.storageInventory)
        assertEquals(1, repository.storageInventoryCalls)
        assertFalse(state.storageScanInProgress)
        viewModel.close()
    }

    @Test
    fun initialLoadDoesNotRefreshRemoteMetadata() {
        val repository = FakeSdkmanRepository()
        val viewModel = ZephyrViewModel(repository, testScope())

        assertEquals(0, repository.catalogCalls)
        assertEquals(0, repository.metadataRefreshCalls)
        assertEquals(
            IntegrityStatus.Passed,
            assertIs<ZephyrUiState.Ready>(viewModel.state.value).integrityChecks.single().status,
        )
        viewModel.close()
    }

    @Test
    fun openingBrowseLoadsTheCatalogWithMetadataRefresh() {
        val repository = FakeSdkmanRepository()
        val viewModel = ZephyrViewModel(repository, testScope())

        viewModel.navigate(ZephyrRoute.BrowseSdks)

        assertEquals(listOf(true), repository.catalogRefreshRequests)
        viewModel.close()
    }

    @Test
    fun cachedCatalogRemainsBrowsableOfflineWithItsTimestamp() {
        val cachedItem = CandidateCatalogItem(
            name = "gradle",
            displayName = "Gradle",
            stableVersion = "8.14",
            description = null,
            websiteUrl = "https://gradle.org",
            kind = CandidateKind.Sdk,
            isInstalled = false,
        )
        val repository = FakeSdkmanRepository(
            cachedCatalog = CandidateMetadataCache(123_456L, listOf(cachedItem)),
            connectivity = testConnectivity(ConnectivityOutcome.Service),
        )
        val viewModel = ZephyrViewModel(repository, testScope())

        val loaded = assertIs<ZephyrUiState.Ready>(viewModel.state.value)
        assertEquals(listOf(cachedItem), loaded.catalog)
        assertTrue(loaded.catalogIsCached)
        assertEquals(123_456L, loaded.catalogCachedAtEpochMillis)

        viewModel.navigate(ZephyrRoute.BrowseSdks)

        val offline = assertIs<ZephyrUiState.Ready>(viewModel.state.value)
        assertEquals(listOf(cachedItem), offline.catalog)
        assertFalse(offline.isCatalogLoading)
        assertEquals(0, repository.catalogCalls)
        viewModel.close()
    }

    @Test
    fun transientCatalogReadIsRetriedUntilItSucceeds() {
        val repository = FakeSdkmanRepository(catalogFailuresBeforeSuccess = 2)
        val viewModel = ZephyrViewModel(
            repository = repository,
            dispatcher = testScope(),
            readRetryDelaysMillis = listOf(0, 0),
        )

        viewModel.navigate(ZephyrRoute.BrowseSdks)

        assertEquals(3, repository.catalogCalls)
        assertEquals(null, assertIs<ZephyrUiState.Ready>(viewModel.state.value).readRetryStatus)
        viewModel.close()
    }

    @Test
    fun mutatingInstallIsNeverAutomaticallyRetried() {
        val repository = FakeSdkmanRepository(installFailure = IllegalStateException("connection reset"))
        val viewModel = ZephyrViewModel(
            repository = repository,
            dispatcher = testScope(),
            readRetryDelaysMillis = listOf(0, 0),
        )

        viewModel.requestTransaction(SdkmanTransaction.Install("java", "21-tem"))
        viewModel.confirmTransaction()

        assertEquals(listOf("install:java:21-tem"), repository.mutationCalls)
        assertEquals(
            OperationStatus.Indeterminate,
            assertIs<ZephyrUiState.Ready>(viewModel.state.value).operationJournal.single().status,
        )
        viewModel.close()
    }

    @Test
    fun metadataFailureLeavesTheUiInteractiveAndReportsTheError() {
        val repository = FakeSdkmanRepository(catalogFailure = IllegalStateException("network unavailable"))
        val viewModel = ZephyrViewModel(repository, testScope())

        viewModel.refreshMetadata()

        val state = assertIs<ZephyrUiState.Ready>(viewModel.state.value)
        assertFalse(state.isCatalogLoading)
        assertEquals("Candidate metadata refresh failed: network unavailable", state.errorMessage)
    }

    @Test
    fun scheduledMetadataRefreshRunsOnlyWhenOnlineAndIdle() {
        val repository = FakeSdkmanRepository()
        val viewModel = ZephyrViewModel(repository, testScope())

        viewModel.refreshMetadataIfIdle()

        assertEquals(1, repository.metadataRefreshCalls)
        assertEquals(
            "Scheduled metadata refresh completed. Loaded 0 packages.",
            assertIs<ZephyrUiState.Ready>(viewModel.state.value).lastOutcome,
        )
        viewModel.close()
    }

    @Test
    fun scheduledMetadataRefreshSkipsOfflineState() {
        val repository = FakeSdkmanRepository(
            connectivity = testConnectivity(ConnectivityOutcome.Service),
        )
        val viewModel = ZephyrViewModel(repository, testScope())

        viewModel.refreshMetadataIfIdle()

        assertEquals(0, repository.metadataRefreshCalls)
        assertEquals(
            ConnectivityState.Offline,
            assertIs<ZephyrUiState.Ready>(viewModel.state.value).connectivityStatus.state,
        )
        viewModel.close()
    }

    @Test
    fun scheduledMetadataRefreshDoesNotInterruptPendingConfirmation() {
        val repository = FakeSdkmanRepository()
        val viewModel = ZephyrViewModel(repository, testScope())
        viewModel.requestTransaction(SdkmanTransaction.Install("java", "21.0.5-tem"))

        viewModel.refreshMetadataIfIdle()

        assertEquals(0, repository.metadataRefreshCalls)
        assertIs<SdkmanTransaction.Install>(
            assertIs<ZephyrUiState.Ready>(viewModel.state.value).pendingTransaction,
        )
        viewModel.close()
    }

    @Test
    fun selfUpdateExceptionLeavesTheUiInteractiveAndReportsTheError() {
        val repository = FakeSdkmanRepository(selfUpdateFailure = IllegalStateException("connection reset"))
        val viewModel = ZephyrViewModel(repository, testScope())

        viewModel.checkSdkmanUpdates()

        val state = assertIs<ZephyrUiState.Ready>(viewModel.state.value)
        assertFalse(state.isRefreshing)
        assertEquals("SDKMAN self-update failed: connection reset", state.errorMessage)
    }

    @Test
    fun refreshPreservesTheCurrentRouteInsteadOfWritingAStaleSnapshot() {
        val repository = FakeSdkmanRepository()
        val viewModel = ZephyrViewModel(repository, testScope())

        viewModel.navigate(ZephyrRoute.BrowseSdks)
        viewModel.refreshInstalled()

        val state = assertIs<ZephyrUiState.Ready>(viewModel.state.value)
        assertIs<ZephyrRoute.BrowseSdks>(state.route)
        assertTrue(state.candidates.isEmpty())
    }

    @Test
    fun openingAnUninstalledPackageDoesNotAddItToInstalledCandidates() {
        val repository = FakeSdkmanRepository(remoteDetail = remoteCandidate("gradle"))
        val viewModel = ZephyrViewModel(repository, testScope())

        viewModel.navigate(ZephyrRoute.SdkDetail("gradle"))

        val state = assertIs<ZephyrUiState.Ready>(viewModel.state.value)
        assertEquals("gradle", state.selectedCandidate?.name)
        assertTrue(state.candidates.isEmpty())
    }

    @Test
    fun ignoresRepeatedOperationsWhileAnotherOperationIsRunning() = runBlocking {
        val refreshStarted = CompletableDeferred<Unit>()
        val refreshGate = CompletableDeferred<Unit>()
        val repository = FakeSdkmanRepository(refreshStarted = refreshStarted, refreshGate = refreshGate)
        val viewModel = ZephyrViewModel(repository, Dispatchers.Default)
        withTimeout(1_000) { viewModel.state.filterIsInstance<ZephyrUiState.Ready>().first() }

        viewModel.refreshInstalled()
        withTimeout(1_000) { refreshStarted.await() }
        viewModel.refreshInstalled()
        refreshGate.complete(Unit)
        withTimeout(1_000) {
            viewModel.state.filterIsInstance<ZephyrUiState.Ready>().first { !it.isRefreshing }
        }

        assertEquals(2, repository.installedCandidatesCalls)
        viewModel.close()
    }

    @Test
    fun loadsARequestedDetailAfterAnActiveScanFinishes() = runBlocking {
        val refreshStarted = CompletableDeferred<Unit>()
        val refreshGate = CompletableDeferred<Unit>()
        val java = remoteCandidate("java", CandidateKind.Jdk)
        val repository = FakeSdkmanRepository(
            remoteDetail = java,
            installedCandidate = java,
            refreshStarted = refreshStarted,
            refreshGate = refreshGate,
        )
        val viewModel = ZephyrViewModel(repository, Dispatchers.Default)
        withTimeout(1_000) { viewModel.state.filterIsInstance<ZephyrUiState.Ready>().first() }

        viewModel.scanLocalOnly()
        withTimeout(1_000) { refreshStarted.await() }
        viewModel.navigate(ZephyrRoute.JdkDetail())
        refreshGate.complete(Unit)

        val state = withTimeout(1_000) {
            viewModel.state.filterIsInstance<ZephyrUiState.Ready>().first {
                it.route is ZephyrRoute.JdkDetail && it.selectedCandidate?.name == "java"
            }
        }

        assertEquals("java", state.selectedCandidate?.name)
        viewModel.close()
    }

    @Test
    fun activeLocalOnlyAuditRejectsMutationReviewAndPerformsNoMutation() = runBlocking {
        val scanStarted = CompletableDeferred<Unit>()
        val scanGate = CompletableDeferred<Unit>()
        val java = remoteCandidate("java", CandidateKind.Jdk)
        val repository = FakeSdkmanRepository(
            installedCandidate = java,
            remoteDetail = java,
            refreshStarted = scanStarted,
            refreshGate = scanGate,
        )
        val viewModel = ZephyrViewModel(repository, Dispatchers.Default)
        withTimeout(1_000) { viewModel.state.filterIsInstance<ZephyrUiState.Ready>().first() }

        viewModel.scanLocalOnly()
        withTimeout(1_000) { scanStarted.await() }
        viewModel.requestTransaction(SdkmanTransaction.SetDefault("java", "21-tem"))

        val scanning = assertIs<ZephyrUiState.Ready>(viewModel.state.value)
        assertEquals(null, scanning.pendingTransaction)
        assertTrue(repository.mutationCalls.isEmpty())

        scanGate.complete(Unit)
        withTimeout(1_000) {
            viewModel.state.filterIsInstance<ZephyrUiState.Ready>().first { !it.localOnlyScanInProgress }
        }
        assertTrue(repository.mutationCalls.isEmpty())
        viewModel.close()
    }

    @Test
    fun liveMutationPreventsLocalOnlyAuditAndFailedReadRetryFromStarting() = runTest {
        val installGate = CompletableDeferred<Unit>()
        val java = remoteCandidate("java", CandidateKind.Jdk)
        val repository = FakeSdkmanRepository(installedCandidate = java, installGate = installGate)
        val vm = ZephyrViewModel(repository, StandardTestDispatcher(testScheduler))
        runCurrent()
        try {
            vm.scanLocalOnly()
            runCurrent()
            assertEquals(listOf("java"), assertIs<ZephyrUiState.Ready>(vm.state.value).localOnlyScanProgress?.failures?.map { it.candidate })
            vm.install("java", "21-tem")
            runCurrent()
            assertTrue(assertIs<ZephyrUiState.Ready>(vm.state.value).liveOperationIds.isNotEmpty())
            val installedReads = repository.installedCandidatesCalls
            val connectivityReads = repository.connectivityCalls

            vm.scanLocalOnly()
            vm.retryFailedLocalOnlyReads()
            runCurrent()

            assertEquals(installedReads, repository.installedCandidatesCalls, "An audit must not read a live mutation's inventory")
            assertEquals(connectivityReads, repository.connectivityCalls)
            assertEquals(null, assertIs<ZephyrUiState.Ready>(vm.state.value).localOnlyScanProgress)
            assertFalse(assertIs<ZephyrUiState.Ready>(vm.state.value).localOnlyScanInProgress)
        } finally {
            installGate.complete(Unit)
            runCurrent()
            vm.close()
        }
    }

    @Test
    fun mutationInvalidatesSuspendedAuditBeforeSnapshotOrFindingCanPublish() = runTest {
        listOf(true, false).forEach { suspendSnapshot ->
            val readStarted = CompletableDeferred<Unit>()
            val readGate = CompletableDeferred<Unit>()
            val installed = remoteCandidate("gradle").copy(
                installedVersions = listOf(CandidateVersion("8.10", true, false, false)),
            )
            val obsoleteFinding = installed.copy(
                hasLocalOnlyVersions = true,
                localOnlyVersionCount = 1,
                localOnlyVersions = listOf("8.10"),
                remoteEvidence = RemoteEvidenceState.LiveComplete,
            )
            val fake = FakeSdkmanRepository()
            var inventoryReads = 0
            var mutated = false
            val repository = object : SdkmanRepository by fake {
                override suspend fun installedCandidates(): List<Candidate> {
                    inventoryReads += 1
                    val snapshot = listOf(installed.copy(description = if (mutated) "post mutation" else "before mutation"))
                    if (suspendSnapshot && inventoryReads == 2) {
                        readStarted.complete(Unit)
                        readGate.await()
                    }
                    return snapshot
                }

                override suspend fun mergedCandidate(candidate: String): Candidate {
                    if (!suspendSnapshot) {
                        readStarted.complete(Unit)
                        readGate.await()
                    }
                    return obsoleteFinding
                }

                override suspend fun install(candidate: String, version: String): CommandOutcome {
                    mutated = true
                    return fake.install(candidate, version)
                }
            }
            val vm = ZephyrViewModel(repository, StandardTestDispatcher(testScheduler))
            runCurrent()
            val observations = mutableListOf<Candidate>()
            val observer = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
                vm.state.collect { state ->
                    observations += (state as? ZephyrUiState.Ready)?.localOnlyScanProgress?.trustedFindings.orEmpty()
                }
            }
            try {
                vm.scanLocalOnly()
                runCurrent()
                readStarted.await()
                vm.install("gradle", "8.14")
                runCurrent()
                assertEquals(OperationStatus.Succeeded, assertIs<ZephyrUiState.Ready>(vm.state.value).operationJournal.single().status)
                assertEquals(null, assertIs<ZephyrUiState.Ready>(vm.state.value).localOnlyScanProgress)

                readGate.complete(Unit)
                runCurrent()

                assertTrue(observations.isEmpty(), "An obsolete audit must not become observation history (snapshot gate: $suspendSnapshot)")
                val ready = assertIs<ZephyrUiState.Ready>(vm.state.value)
                assertEquals(null, ready.localOnlyScanProgress)
                assertFalse(ready.localOnlyScanInProgress)
                assertEquals("post mutation", ready.candidates.single().description)
                vm.cleanLocalOnly("gradle", listOf("8.10"))
                runCurrent()
                assertEquals(null, assertIs<ZephyrUiState.Ready>(vm.state.value).pendingTransaction)
                assertEquals(listOf("install:gradle:8.14"), fake.mutationCalls)
                val readsAfterMutation = inventoryReads
                vm.retryFailedLocalOnlyReads()
                runCurrent()
                assertEquals(readsAfterMutation, inventoryReads)
                vm.scanLocalOnly()
                runCurrent()
                assertTrue(observations.isNotEmpty(), "A fresh post-mutation audit must still publish trusted findings")
                vm.cleanLocalOnly("gradle", listOf("8.10"))
                runCurrent()
                assertIs<SdkmanTransaction.CleanLocalOnly>(assertIs<ZephyrUiState.Ready>(vm.state.value).pendingTransaction)
            } finally {
                observer.cancel()
                readGate.complete(Unit)
                vm.close()
            }
        }
    }

    @Test
    fun invalidatedAuditFailureCannotClearLiveMutationStateOrPublishActivity() = runTest {
        listOf(true, false).forEach { suspendConnectivity ->
            val readStarted = CompletableDeferred<Unit>()
            val readGate = CompletableDeferred<Unit>()
            val installGate = CompletableDeferred<Unit>()
            val fake = FakeSdkmanRepository(installGate = installGate)
            var connectivityReads = 0
            var inventoryReads = 0
            val repository = object : SdkmanRepository by fake {
                override suspend fun checkConnectivity(): ConnectivityStatus {
                    connectivityReads += 1
                    if (suspendConnectivity && connectivityReads == 2) {
                        readStarted.complete(Unit)
                        readGate.await()
                        return testConnectivity(ConnectivityOutcome.Service)
                    }
                    return testConnectivity()
                }

                override suspend fun installedCandidates(): List<Candidate> {
                    inventoryReads += 1
                    if (!suspendConnectivity && inventoryReads == 2) {
                        readStarted.complete(Unit)
                        readGate.await()
                        error("obsolete inventory failure")
                    }
                    return emptyList()
                }
            }
            val vm = ZephyrViewModel(repository, StandardTestDispatcher(testScheduler))
            runCurrent()
            try {
                vm.scanLocalOnly()
                runCurrent()
                readStarted.await()
                vm.install("gradle", "8.14")
                runCurrent()
                readGate.complete(Unit)
                runCurrent()

                val live = assertIs<ZephyrUiState.Ready>(vm.state.value)
                assertTrue(live.liveOperationIds.isNotEmpty())
                assertTrue(live.isRefreshing, "A stale audit failure must not clear mutation busy state")
                assertEquals(ConnectivityState.Online, live.connectivityStatus.state)
                assertEquals(null, live.localOnlyScanProgress)
                assertFalse(live.localOnlyScanInProgress)
                assertEquals(null, live.errorMessage)
                assertTrue(live.activityEvents.isEmpty())
                installGate.complete(Unit)
                runCurrent()
                val completed = assertIs<ZephyrUiState.Ready>(vm.state.value)
                assertEquals(OperationStatus.Succeeded, completed.operationJournal.single().status)
                assertEquals(listOf("Installed"), completed.activityEvents.map { it.message })
            } finally {
                readGate.complete(Unit)
                installGate.complete(Unit)
                vm.close()
            }
        }
    }

    @Test
    fun pendingTransactionReviewPreventsLocalOnlyAuditFromStarting() {
        val java = remoteCandidate("java", CandidateKind.Jdk)
        val repository = FakeSdkmanRepository(installedCandidate = java, remoteDetail = java)
        val viewModel = ZephyrViewModel(repository, testScope())
        val transaction = SdkmanTransaction.SetDefault("java", "21-tem")

        viewModel.requestTransaction(transaction)
        viewModel.scanLocalOnly()

        val reviewing = assertIs<ZephyrUiState.Ready>(viewModel.state.value)
        assertEquals(transaction, reviewing.pendingTransaction)
        assertEquals(1, repository.installedCandidatesCalls)
        assertTrue(repository.mutationCalls.isEmpty())

        viewModel.confirmTransaction()
        assertEquals(listOf("default:java:21-tem"), repository.mutationCalls)
        viewModel.close()
    }

    @Test
    fun loadsBrowseCatalogRequestedDuringAnActiveScan() = runBlocking {
        val refreshStarted = CompletableDeferred<Unit>()
        val refreshGate = CompletableDeferred<Unit>()
        val repository = FakeSdkmanRepository(refreshStarted = refreshStarted, refreshGate = refreshGate)
        val viewModel = ZephyrViewModel(repository, Dispatchers.Default)
        withTimeout(1_000) { viewModel.state.filterIsInstance<ZephyrUiState.Ready>().first() }

        viewModel.scanLocalOnly()
        withTimeout(1_000) { refreshStarted.await() }
        viewModel.navigate(ZephyrRoute.BrowseSdks)
        refreshGate.complete(Unit)

        withTimeout(1_000) {
            viewModel.state.filterIsInstance<ZephyrUiState.Ready>().first {
                it.route is ZephyrRoute.BrowseSdks && repository.catalogRefreshRequests == listOf(true)
            }
        }

        viewModel.close()
    }

    @Test
    fun leavingASlowDetailRequestClearsOnlyTheDetailLoadingState() = runBlocking {
        val detailStarted = CompletableDeferred<Unit>()
        val detailGate = CompletableDeferred<Unit>()
        val detailCompleted = CompletableDeferred<Unit>()
        val repository = FakeSdkmanRepository(
            remoteDetail = remoteCandidate("gradle"),
            detailStarted = detailStarted,
            detailGate = detailGate,
            detailCompleted = detailCompleted,
        )
        val viewModel = ZephyrViewModel(repository, Dispatchers.Default)
        withTimeout(1_000) { viewModel.state.filterIsInstance<ZephyrUiState.Ready>().first() }

        viewModel.navigate(ZephyrRoute.SdkDetail("gradle"))
        withTimeout(1_000) { detailStarted.await() }
        viewModel.navigate(ZephyrRoute.InstalledSdks)
        detailGate.complete(Unit)
        withTimeout(1_000) { detailCompleted.await() }

        val state = assertIs<ZephyrUiState.Ready>(viewModel.state.value)
        assertIs<ZephyrRoute.InstalledSdks>(state.route)
        assertEquals(null, state.detailLoadingCandidate)
        assertFalse(state.isRefreshing)
        viewModel.close()
    }

    @Test
    fun mutationRunsOnlyAfterItsTypedTransactionIsConfirmed() {
        val repository = FakeSdkmanRepository()
        val viewModel = ZephyrViewModel(repository, testScope())
        val transaction = SdkmanTransaction.Install("java", "21.0.5-tem")

        viewModel.requestTransaction(transaction)

        val pending = assertIs<ZephyrUiState.Ready>(viewModel.state.value)
        assertEquals(transaction, pending.pendingTransaction)
        assertEquals(DiskImpactKind.None, pending.pendingTransactionDiskImpact?.kind)
        assertTrue(repository.mutationCalls.isEmpty())

        viewModel.confirmTransaction()

        assertEquals(listOf("install:java:21.0.5-tem"), repository.mutationCalls)
        val ready = assertIs<ZephyrUiState.Ready>(viewModel.state.value)
        assertEquals(null, ready.pendingTransaction)
        assertEquals(OperationStatus.Succeeded, ready.operationJournal.single().status)
        assertEquals("Installed", ready.operationJournal.single().outcome)
        viewModel.close()
    }

    @Test
    fun batchInstallRunsTargetsSequentiallyAndRetainsPerItemResults() {
        val repository = FakeSdkmanRepository()
        val viewModel = ZephyrViewModel(repository, testScope())
        val transaction = SdkmanTransaction.BatchInstall(
            listOf(
                InstallTarget("gradle", "8.14"),
                InstallTarget("kotlin", "2.2.0"),
            ),
        )

        viewModel.requestTransaction(transaction)
        viewModel.confirmTransaction()

        assertEquals(
            listOf("install:gradle:8.14", "install:kotlin:2.2.0"),
            repository.mutationCalls,
        )
        val ready = assertIs<ZephyrUiState.Ready>(viewModel.state.value)
        assertEquals(
            listOf(BatchItemStatus.Succeeded, BatchItemStatus.Succeeded),
            ready.batchInstallProgress.map { it.status },
        )
        assertEquals(OperationStatus.Succeeded, ready.operationJournal.single().status)
        viewModel.close()
    }

    @Test
    fun snapshotRestoreRunsInstallsBeforeDefaultsAndRetainsProgress() {
        val repository = FakeSdkmanRepository()
        val viewModel = ZephyrViewModel(repository, testScope())
        val transaction = SdkmanTransaction.SnapshotRestore(
            listOf(
                PlannedSdkmanCommand(SdkmanCommandAction.Install, "java", "21-tem"),
                PlannedSdkmanCommand(SdkmanCommandAction.SetDefault, "java", "21-tem"),
            ),
        )

        viewModel.requestTransaction(transaction)
        viewModel.confirmTransaction()

        assertEquals(
            listOf("install:java:21-tem", "default:java:21-tem"),
            repository.mutationCalls,
        )
        val ready = assertIs<ZephyrUiState.Ready>(viewModel.state.value)
        assertEquals(
            listOf(BatchItemStatus.Succeeded, BatchItemStatus.Succeeded),
            ready.snapshotRestoreProgress.map { it.status },
        )
        assertEquals(OperationStatus.Succeeded, ready.operationJournal.single().status)
        viewModel.close()
    }

    @Test
    fun profileActivationRunsMissingInstallsThenDefaultsWithoutUninstalls() {
        val repository = FakeSdkmanRepository()
        val viewModel = ZephyrViewModel(repository, testScope())
        val transaction = SdkmanTransaction.ToolchainActivation(
            profileName = "Backend",
            commands = listOf(
                PlannedSdkmanCommand(SdkmanCommandAction.Install, "java", "21-tem"),
                PlannedSdkmanCommand(SdkmanCommandAction.SetDefault, "java", "21-tem"),
            ),
        )

        viewModel.requestTransaction(transaction)
        viewModel.confirmTransaction()

        assertEquals(
            listOf("install:java:21-tem", "default:java:21-tem"),
            repository.mutationCalls,
        )
        assertTrue(repository.mutationCalls.none { it.startsWith("uninstall:") })
        val ready = assertIs<ZephyrUiState.Ready>(viewModel.state.value)
        assertEquals(OperationStatus.Succeeded, ready.operationJournal.single().status)
        assertEquals(
            listOf(BatchItemStatus.Succeeded, BatchItemStatus.Succeeded),
            ready.snapshotRestoreProgress.map { it.status },
        )
        viewModel.close()
    }

    @Test
    fun stableUpdateSkipsOnlyDefaultWhoseInstallFailedAndContinuesUnrelatedTargets() {
        val repository = FakeSdkmanRepository(
            installOutcomes = mapOf(
                ("gradle" to "8.14") to CommandOutcome(false, "Download failed"),
                ("kotlin" to "2.2.0") to CommandOutcome(true, "Installed"),
            ),
        )
        val viewModel = ZephyrViewModel(repository, testScope())
        val transaction = SdkmanTransaction.UpdateActivation(
            listOf(
                UpdateActivationTarget("gradle", "8.14", true),
                UpdateActivationTarget("kotlin", "2.2.0", true),
            ),
        )

        viewModel.requestTransaction(transaction)
        viewModel.confirmTransaction()

        assertEquals(
            listOf(
                "install:gradle:8.14",
                "install:kotlin:2.2.0",
                "default:kotlin:2.2.0",
            ),
            repository.mutationCalls,
        )
        val ready = assertIs<ZephyrUiState.Ready>(viewModel.state.value)
        assertEquals(
            listOf(
                OperationStepStatus.Failed,
                OperationStepStatus.Succeeded,
                OperationStepStatus.Skipped,
                OperationStepStatus.Succeeded,
            ),
            ready.operationJournal.single().steps.map { it.status },
        )
        assertEquals(
            listOf(
                BatchItemStatus.Failed,
                BatchItemStatus.Succeeded,
                BatchItemStatus.Skipped,
                BatchItemStatus.Succeeded,
            ),
            ready.updateActivationProgress.map { it.status },
        )
        assertTrue(repository.installedCandidatesCalls >= 2)
        viewModel.close()
    }

    @Test
    fun updateActivationStopsAndClearsBusyStateWhenLedgerCannotRecordAResult() {
        val repository = FakeSdkmanRepository(
            installedCandidate = remoteCandidate("java", CandidateKind.Jdk),
        )
        val operationStore = FailingOperationStore(failOnSaveCalls = setOf(3))
        val viewModel = ZephyrViewModel(
            repository = repository,
            dispatcher = testScope(),
            operationStore = operationStore,
        )
        viewModel.scanLocalOnly()
        assertEquals(
            listOf("java"),
            assertIs<ZephyrUiState.Ready>(viewModel.state.value)
                .localOnlyScanProgress
                ?.failures
                ?.map { it.candidate },
        )
        val transaction = SdkmanTransaction.UpdateActivation(
            listOf(UpdateActivationTarget("gradle", "8.14", true)),
        )

        viewModel.requestTransaction(transaction)
        viewModel.confirmTransaction()

        val ready = assertIs<ZephyrUiState.Ready>(viewModel.state.value)
        assertEquals(listOf("install:gradle:8.14"), repository.mutationCalls)
        assertTrue(!ready.isRefreshing)
        assertEquals(OperationStatus.Interrupted, ready.operationJournal.single().status)
        // Persistence uncertainty must not erase a known execution receipt in memory.
        assertEquals(OperationStepStatus.Succeeded, ready.operationJournal.single().steps.first().status)
        assertTrue(ready.errorMessage.orEmpty().contains("execution stopped"))
        assertEquals(null, ready.localOnlyScanProgress)
        val installedCallsAfterAbort = repository.installedCandidatesCalls
        viewModel.retryFailedLocalOnlyReads()
        assertEquals(installedCallsAfterAbort, repository.installedCandidatesCalls)
        viewModel.close()
    }

    @Test
    fun batchMutationInvalidatesFailedLocalOnlyRetrySnapshot() {
        val java = remoteCandidate("java", CandidateKind.Jdk)
        val repository = FakeSdkmanRepository(installedCandidate = java)
        val viewModel = ZephyrViewModel(repository, testScope())

        viewModel.scanLocalOnly()
        val failedAudit = assertIs<ZephyrUiState.Ready>(viewModel.state.value)
        assertEquals(listOf("java"), failedAudit.localOnlyScanProgress?.failures?.map { it.candidate })

        viewModel.requestTransaction(
            SdkmanTransaction.UpdateActivation(
                listOf(UpdateActivationTarget("java", "21-tem", false)),
            ),
        )
        viewModel.confirmTransaction()
        val callsAfterMutation = repository.installedCandidatesCalls

        val updated = assertIs<ZephyrUiState.Ready>(viewModel.state.value)
        assertEquals(null, updated.localOnlyScanProgress)
        viewModel.retryFailedLocalOnlyReads()
        assertEquals(callsAfterMutation, repository.installedCandidatesCalls)
        viewModel.close()
    }

    @Test
    fun stableSwitchOnlyPlanWorksOfflineButMissingInstallUsesOnlinePreflight() {
        val repository = FakeSdkmanRepository(
            connectivity = testConnectivity(ConnectivityOutcome.Service),
        )
        val viewModel = ZephyrViewModel(repository, testScope())
        val connectivityCallsAfterLoad = repository.connectivityCalls
        val switchOnly = SdkmanTransaction.UpdateActivation(
            listOf(UpdateActivationTarget("gradle", "8.14", false)),
        )

        viewModel.requestTransaction(switchOnly)
        assertEquals(switchOnly, assertIs<ZephyrUiState.Ready>(viewModel.state.value).pendingTransaction)
        assertEquals(connectivityCallsAfterLoad, repository.connectivityCalls)
        viewModel.dismissTransaction()

        viewModel.requestTransaction(
            SdkmanTransaction.UpdateActivation(
                listOf(UpdateActivationTarget("kotlin", "2.2.0", true)),
            ),
        )
        val ready = assertIs<ZephyrUiState.Ready>(viewModel.state.value)
        assertEquals(null, ready.pendingTransaction)
        assertTrue(ready.errorMessage.orEmpty().contains("offline"))
        assertEquals(connectivityCallsAfterLoad + 1, repository.connectivityCalls)
        viewModel.close()
    }

    @Test
    fun batchUninstallRunsTargetsSequentiallyAndRetainsPerItemResults() {
        val repository = FakeSdkmanRepository()
        val viewModel = ZephyrViewModel(repository, testScope())
        val transaction = SdkmanTransaction.BatchUninstall(
            listOf(
                UninstallTarget("gradle", "8.10"),
                UninstallTarget("kotlin", "2.1.0"),
            ),
        )

        viewModel.requestTransaction(transaction)
        viewModel.confirmTransaction()

        assertEquals(
            listOf("uninstall:gradle:8.10", "uninstall:kotlin:2.1.0"),
            repository.mutationCalls,
        )
        val ready = assertIs<ZephyrUiState.Ready>(viewModel.state.value)
        assertEquals(
            listOf(BatchItemStatus.Succeeded, BatchItemStatus.Succeeded),
            ready.batchUninstallProgress.map { it.status },
        )
        assertEquals(OperationStatus.Succeeded, ready.operationJournal.single().status)
        viewModel.close()
    }

    @Test
    fun exportsTheCompletedSessionJournal() {
        val repository = FakeSdkmanRepository()
        val exporter = FakeOperationJournalExporter()
        var now = 1_000L
        val viewModel = ZephyrViewModel(
            repository = repository,
            dispatcher = testScope(),
            journalExporter = exporter,
            clock = { now++ },
        )

        viewModel.requestTransaction(SdkmanTransaction.SetDefault("java", "21.0.5-tem"))
        viewModel.confirmTransaction()
        viewModel.exportJournal()

        val exported = exporter.exported.single()
        assertEquals(OperationStatus.Succeeded, exported.single().status)
        assertEquals(1_000L, exported.single().startedAtEpochMillis)
        assertTrue(requireNotNull(exported.single().completedAtEpochMillis) > exported.single().startedAtEpochMillis)
        assertEquals(
            "Exported 1 journal entries to /tmp/zephyr-journal.csv.",
            assertIs<ZephyrUiState.Ready>(viewModel.state.value).lastOutcome,
        )
        viewModel.close()
    }

    @Test
    fun exportsCurrentStateAsADiagnosticsSnapshot() {
        val exporter = FakeDiagnosticsExporter()
        val viewModel = ZephyrViewModel(
            repository = FakeSdkmanRepository(),
            dispatcher = testScope(),
            diagnosticsExporter = exporter,
            clock = { 1_234L },
        )

        viewModel.exportDiagnostics()

        val snapshot = exporter.exported.single()
        assertEquals(1_234L, snapshot.generatedAtEpochMillis)
        assertEquals("SDKMAN 5", snapshot.sdkmanStatus.cliVersion)
        assertEquals(ConnectivityState.Online, snapshot.connectivityStatus.state)
        assertEquals(1, snapshot.integrityChecks.size)
        assertEquals(
            "Exported a redacted support bundle to /tmp/zephyr-support.txt.",
            assertIs<ZephyrUiState.Ready>(viewModel.state.value).lastOutcome,
        )
        viewModel.close()
    }

    @Test
    fun failedMutationIsRetainedInTheJournal() {
        val repository = FakeSdkmanRepository(
            installOutcome = CommandOutcome(false, "Download unavailable"),
        )
        val viewModel = ZephyrViewModel(repository, testScope())

        viewModel.requestTransaction(SdkmanTransaction.Install("java", "21.0.5-tem"))
        viewModel.confirmTransaction()

        val entry = assertIs<ZephyrUiState.Ready>(viewModel.state.value).operationJournal.single()
        assertEquals(OperationStatus.Failed, entry.status)
        assertEquals("Download unavailable", entry.outcome)
        viewModel.close()
    }

    @Test
    fun cleanupRetryIncludesOnlyVersionsStillVerifiedAsLocalOnly() {
        val installed = remoteCandidate("java", CandidateKind.Jdk).copy(
            installedVersions = listOf(
                CandidateVersion("17.0.1-tem", true, false, false),
            ),
            hasLocalOnlyVersions = true,
            localOnlyVersionCount = 1,
            localOnlyVersions = listOf("17.0.1-tem"),
            remoteEvidence = RemoteEvidenceState.LiveComplete,
        )
        val viewModel = ZephyrViewModel(
            FakeSdkmanRepository(
                installedCandidate = installed,
                remoteDetail = installed,
            ),
            testScope(),
        )
        viewModel.scanLocalOnly()

        viewModel.retryTransaction(
            SdkmanTransaction.CleanLocalOnly(
                "java",
                listOf("17.0.1-tem", "19.0.2-tem"),
            ),
        )

        val retry = assertIs<SdkmanTransaction.CleanLocalOnly>(
            assertIs<ZephyrUiState.Ready>(viewModel.state.value).pendingTransaction,
        )
        assertEquals(listOf("17.0.1-tem"), retry.versions)
        viewModel.close()
    }

    @Test
    fun reconcilesInterruptedTasksAndReviewsOnlyRemainingSteps() {
        val transaction = SdkmanTransaction.BatchInstall(
            listOf(
                InstallTarget("java", "21.0.5-tem"),
                InstallTarget("gradle", "9.1.0"),
            ),
        )
        val stored = OperationJournalEntry(
            id = 72,
            transaction = transaction,
            startedAtEpochMillis = 100,
            status = OperationStatus.Running,
            steps = transaction.commands.mapIndexed { index, command ->
                OperationStep(
                    index = index,
                    command = command,
                    status = if (index == 0) OperationStepStatus.Succeeded else OperationStepStatus.Running,
                )
            },
        )
        val store = InMemoryOperationStore(listOf(stored))
        val remaining = transaction.commands[1]
        val repository = FakeSdkmanRepository(
            commandSatisfaction = mapOf(remaining to CommandSatisfaction.Unsatisfied),
        )
        val viewModel = ZephyrViewModel(
            repository = repository,
            dispatcher = testScope(),
            operationStore = store,
        )

        val interrupted = assertIs<ZephyrUiState.Ready>(viewModel.state.value).operationJournal.single()
        assertEquals(OperationStatus.Interrupted, interrupted.status)
        assertEquals(
            listOf(OperationStepStatus.Succeeded, OperationStepStatus.Interrupted),
            interrupted.steps.map { it.status },
        )

        viewModel.requestResumeOperation(interrupted.id)

        val pending = assertIs<SdkmanTransaction.BatchInstall>(
            assertIs<ZephyrUiState.Ready>(viewModel.state.value).pendingTransaction,
        )
        assertEquals(listOf(InstallTarget("gradle", "9.1.0")), pending.targets)
        assertTrue(store.saved.any { entries -> entries.single().status == OperationStatus.Interrupted })
        viewModel.close()
    }

    @Test
    fun indeterminateInterruptedStepsAreNeverAddedToResumePlan() {
        val transaction = SdkmanTransaction.Install("java", "21.0.5-tem")
        val stored = OperationJournalEntry(
            id = 73,
            transaction = transaction,
            startedAtEpochMillis = 100,
            status = OperationStatus.Running,
            steps = listOf(
                OperationStep(0, transaction.commands.single(), OperationStepStatus.Running),
            ),
        )
        val store = InMemoryOperationStore(listOf(stored))
        val repository = FakeSdkmanRepository(
            commandSatisfaction = mapOf(
                transaction.commands.single() to CommandSatisfaction.Indeterminate,
            ),
        )
        val viewModel = ZephyrViewModel(
            repository = repository,
            dispatcher = testScope(),
            operationStore = store,
        )

        val interrupted = assertIs<ZephyrUiState.Ready>(viewModel.state.value).operationJournal.single()
        assertEquals(OperationStepStatus.Indeterminate, interrupted.steps.single().status)

        viewModel.requestResumeOperation(interrupted.id)

        val ready = assertIs<ZephyrUiState.Ready>(viewModel.state.value)
        assertEquals(null, ready.pendingTransaction)
        assertTrue(ready.lastOutcome.orEmpty().contains("Could not verify"))
        assertTrue(repository.mutationCalls.isEmpty())
        viewModel.close()
    }

    @Test
    fun verifyingIndeterminateOnlyTaskCanConfirmSuccessWithoutReplayingMutation() = runTest {
        val transaction = SdkmanTransaction.Uninstall("gradle", "8.10")
        val stored = OperationJournalEntry(
            id = 74,
            transaction = transaction,
            startedAtEpochMillis = 100,
            status = OperationStatus.Indeterminate,
            steps = listOf(OperationStep(0, transaction.commands.single(), OperationStepStatus.Indeterminate)),
        )
        val satisfactionStarted = CompletableDeferred<Unit>()
        val satisfactionGate = CompletableDeferred<Unit>()
        val store = InMemoryOperationStore(listOf(stored))
        val repository = FakeSdkmanRepository(satisfactionReader = {
            satisfactionStarted.complete(Unit)
            satisfactionGate.await()
            CommandSatisfaction.Satisfied
        })
        val viewModel = ZephyrViewModel(repository, StandardTestDispatcher(testScheduler), operationStore = store)
        runCurrent()
        try {
            assertEquals(OperationStatus.Indeterminate, assertIs<ZephyrUiState.Ready>(viewModel.state.value).operationJournal.single().status)
            viewModel.requestResumeOperation(stored.id)
            runCurrent()
            satisfactionStarted.await()
            assertTrue(repository.mutationCalls.isEmpty())
            satisfactionGate.complete(Unit)
            runCurrent()
            val ready = assertIs<ZephyrUiState.Ready>(viewModel.state.value)
            assertEquals(OperationStatus.Succeeded, ready.operationJournal.single().status)
            assertEquals(OperationStepStatus.Succeeded, ready.operationJournal.single().steps.single().status)
            assertEquals(OperationStatus.Succeeded, store.load().single().status)
            assertEquals(null, ready.pendingTransaction)
            assertTrue(repository.mutationCalls.isEmpty())
        } finally {
            satisfactionGate.complete(Unit)
            viewModel.close()
            runCurrent()
        }
    }

    @Test
    fun retainsConsecutiveOperationActivityInOrder() {
        val repository = FakeSdkmanRepository()
        var now = 10L
        val viewModel = ZephyrViewModel(
            repository = repository,
            dispatcher = testScope(),
            clock = { now++ },
        )

        viewModel.requestTransaction(SdkmanTransaction.Install("java", "21.0.5-tem"))
        viewModel.confirmTransaction()
        viewModel.requestTransaction(SdkmanTransaction.Uninstall("java", "17.0.1-tem"))
        viewModel.confirmTransaction()

        val events = assertIs<ZephyrUiState.Ready>(viewModel.state.value).activityEvents
        assertEquals(listOf("Uninstalled", "Installed"), events.map { it.message })
        assertTrue(events.zipWithNext().all { (newer, older) -> newer.id > older.id })
        assertTrue(events.none { it.acknowledged })
        viewModel.close()
    }

    @Test
    fun protectionChangesAreReflectedInReadyState() {
        val viewModel = ZephyrViewModel(FakeSdkmanRepository(), testScope())
        val protected = ProtectedVersion("java", "21.0.5-tem")

        viewModel.setVersionProtected(protected.candidate, protected.version, true)

        assertTrue(protected in assertIs<ZephyrUiState.Ready>(viewModel.state.value).protectedVersions)

        viewModel.setVersionProtected(protected.candidate, protected.version, false)

        assertFalse(protected in assertIs<ZephyrUiState.Ready>(viewModel.state.value).protectedVersions)
        viewModel.close()
    }

    @Test
    fun offlinePreflightBlocksNetworkTransactionsButAllowsLocalOnes() {
        val repository = FakeSdkmanRepository(
            connectivity = testConnectivity(ConnectivityOutcome.Service),
        )
        val viewModel = ZephyrViewModel(repository, testScope())

        viewModel.requestTransaction(SdkmanTransaction.Install("java", "21.0.5-tem"))

        val offline = assertIs<ZephyrUiState.Ready>(viewModel.state.value)
        assertEquals(null, offline.pendingTransaction)
        assertTrue(offline.errorMessage.orEmpty().contains("offline"))
        assertTrue(repository.mutationCalls.isEmpty())

        viewModel.requestTransaction(SdkmanTransaction.Uninstall("java", "17.0.1-tem"))

        assertIs<SdkmanTransaction.Uninstall>(
            assertIs<ZephyrUiState.Ready>(viewModel.state.value).pendingTransaction,
        )
        viewModel.close()
    }

    @Test
    fun previewAdmissionRequiresTheSafeOnlineClassification() {
        ConnectivityOutcome.entries.forEach { outcome ->
            val repository = FakeSdkmanRepository(connectivity = testConnectivity(outcome))
            val viewModel = ZephyrViewModel(repository, testScope())

            viewModel.requestTransaction(SdkmanTransaction.Install("java", "21.0.5-tem"))

            val ready = assertIs<ZephyrUiState.Ready>(viewModel.state.value)
            assertEquals(
                outcome == ConnectivityOutcome.Online,
                ready.pendingTransaction != null,
                "Unexpected preview admission for $outcome",
            )
            assertTrue(repository.mutationCalls.isEmpty(), "Preflight must not mutate for $outcome")
            viewModel.close()
        }
    }

    @Test
    fun olderConnectivityResultCannotOverwriteNewerDiagnostic() = runBlocking {
        val firstStarted = CompletableDeferred<Unit>()
        val firstGate = CompletableDeferred<Unit>()
        val repository = FakeSdkmanRepository(
            connectivityResponses = listOf(
                testConnectivity(ConnectivityOutcome.Service),
                testConnectivity(ConnectivityOutcome.Online),
            ),
            firstConnectivityStarted = firstStarted,
            firstConnectivityGate = firstGate,
        )
        val viewModel = ZephyrViewModel(repository, Dispatchers.Default)
        withTimeout(1_000) { viewModel.state.filterIsInstance<ZephyrUiState.Ready>().first() }
        withTimeout(1_000) { firstStarted.await() }

        viewModel.refreshConnectivity()
        firstGate.complete(Unit)

        val ready = withTimeout(1_000) {
            viewModel.state.filterIsInstance<ZephyrUiState.Ready>().first {
                repository.connectivityCalls == 2 &&
                    it.connectivityStatus.diagnostic?.outcome == ConnectivityOutcome.Online
            }
        }
        assertEquals(ConnectivityOutcome.Online, ready.connectivityStatus.diagnostic?.outcome)
        viewModel.close()
    }

    @Test
    fun journaledConfirmationCannotBeDroppedByNavigationRead() = runTest {
        val saveStarted = CompletableDeferred<Unit>()
        val saveGate = CompletableDeferred<Unit>()
        val detailStarted = CompletableDeferred<Unit>()
        val detailGate = CompletableDeferred<Unit>()
        val store = object : OperationStore {
            var entries = emptyList<OperationJournalEntry>()
            override suspend fun load() = entries
            override suspend fun save(entries: List<OperationJournalEntry>) {
                saveStarted.complete(Unit)
                saveGate.await()
                this.entries = entries
            }
        }
        val repository = FakeSdkmanRepository(detailStarted = detailStarted, detailGate = detailGate)
        val vm = ZephyrViewModel(repository, StandardTestDispatcher(testScheduler), operationStore = store)
        runCurrent()
        try {
            vm.requestTransaction(SdkmanTransaction.Uninstall("gradle", "8.10"))
            runCurrent()
            vm.confirmTransaction()
            runCurrent()
            saveStarted.await()
            vm.navigate(ZephyrRoute.SdkDetail("gradle"))
            runCurrent()
            // With exclusive ownership the read may queue; with independent reads it may start.
            saveGate.complete(Unit)
            runCurrent()
            assertEquals(listOf("uninstall:gradle:8.10"), repository.mutationCalls)
            assertEquals(OperationStatus.Succeeded, store.entries.single().status)
            detailGate.complete(Unit)
            runCurrent()
        } finally {
            detailGate.complete(Unit)
            saveGate.complete(Unit)
            vm.close()
        }
    }

    @Test
    fun successfulMutationReceiptSurvivesGatedInventoryFailure() = runTest {
        val refreshStarted = CompletableDeferred<Unit>()
        val refreshGate = CompletableDeferred<Unit>()
        val repository = FakeSdkmanRepository(
            refreshStarted = refreshStarted,
            refreshGate = refreshGate,
            refreshFailure = IllegalStateException("inventory unavailable"),
        )
        val vm = ZephyrViewModel(repository, StandardTestDispatcher(testScheduler))
        runCurrent()
        try {
            vm.requestTransaction(SdkmanTransaction.Uninstall("gradle", "8.10"))
            runCurrent()
            vm.confirmTransaction()
            runCurrent()
            refreshStarted.await()
            refreshGate.complete(Unit)
            runCurrent()
            val ready = assertIs<ZephyrUiState.Ready>(vm.state.value)
            assertEquals(OperationStatus.Succeeded, ready.operationJournal.single().status)
            assertEquals("Uninstalled", ready.operationJournal.single().outcome)
            assertEquals(listOf("uninstall:gradle:8.10"), repository.mutationCalls)
            assertTrue(ready.errorMessage.orEmpty().contains("refresh", ignoreCase = true))
            assertEquals(null, ready.storageInventory)
            assertEquals(null, ready.localOnlyScanProgress)
        } finally {
            refreshGate.complete(Unit)
            vm.close()
        }
    }

    @Test
    fun recoveryRejectsLiveOwnedBatchBeforeAnyReconciliationRead() = runTest {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val repository = FakeSdkmanRepository(installStarted = started, installGate = gate)
        val vm = ZephyrViewModel(repository, StandardTestDispatcher(testScheduler))
        runCurrent()
        try {
            vm.requestTransaction(SdkmanTransaction.BatchInstall(listOf(
                InstallTarget("gradle", "8.14"), InstallTarget("kotlin", "2.2.0"),
            )))
            runCurrent()
            vm.confirmTransaction()
            runCurrent()
            started.await()
            val id = assertIs<ZephyrUiState.Ready>(vm.state.value).operationJournal.single().id
            vm.requestResumeOperation(id)
            runCurrent()
            assertEquals(0, repository.commandSatisfactionCalls)
            gate.complete(Unit)
            runCurrent()
            val entry = assertIs<ZephyrUiState.Ready>(vm.state.value).operationJournal.single()
            assertEquals(OperationStatus.Succeeded, entry.status)
            assertTrue(entry.steps.all { it.status == OperationStepStatus.Succeeded })
        } finally {
            gate.complete(Unit)
            vm.close()
        }
    }

    @Test
    fun singleOperationsRetainIndeterminateReceipts() = runTest {
        val uncertain = CommandOutcome(false, "Could not verify", CommandOutcomeStatus.Indeterminate)
        listOf(
            SdkmanTransaction.Install("gradle", "8.10"),
            SdkmanTransaction.Uninstall("gradle", "8.10"),
            SdkmanTransaction.SetDefault("gradle", "8.10"),
        ).forEach { transaction ->
            val repository = FakeSdkmanRepository(
                installOutcome = uncertain, uninstallOutcomes = mapOf("8.10" to uncertain), defaultOutcome = uncertain,
            )
            val store = InMemoryOperationStore()
            val vm = ZephyrViewModel(repository, StandardTestDispatcher(testScheduler), operationStore = store)
            runCurrent()
            vm.requestTransaction(transaction)
            runCurrent()
            vm.confirmTransaction()
            runCurrent()
            val entry = store.load().single()
            assertEquals(OperationStatus.Indeterminate, entry.status)
            assertEquals(OperationStepStatus.Indeterminate, entry.steps.single().status)
            vm.close()
        }
    }

    @Test
    fun cleanupRetainsEachVerifiedRemovalReceipt() = runTest {
        val finding = remoteCandidate("gradle").copy(
            installedVersions = listOf(CandidateVersion("8.10", true, false, false), CandidateVersion("8.11", true, false, false)),
            hasLocalOnlyVersions = true, localOnlyVersionCount = 2, localOnlyVersions = listOf("8.10", "8.11"),
            remoteEvidence = RemoteEvidenceState.LiveComplete,
        )
        val repository = FakeSdkmanRepository(
            installedCandidate = finding, remoteDetail = finding,
            cleanupOutcomes = mapOf(
                "8.10" to CommandOutcome(true, "Removed first"),
                "8.11" to CommandOutcome(false, "Second uncertain", CommandOutcomeStatus.Indeterminate),
            ),
        )
        val store = InMemoryOperationStore()
        val vm = ZephyrViewModel(repository, StandardTestDispatcher(testScheduler), operationStore = store)
        runCurrent()
        vm.scanLocalOnly()
        runCurrent()
        vm.requestTransaction(SdkmanTransaction.CleanLocalOnly("gradle", listOf("8.10", "8.11")))
        runCurrent()
        vm.confirmTransaction()
        runCurrent()
        val entry = store.load().single()
        assertEquals(listOf(OperationStepStatus.Succeeded, OperationStepStatus.Indeterminate), entry.steps.map { it.status })
        assertEquals(listOf("Removed first", "Second uncertain"), entry.steps.map { it.outcome })
        assertEquals(OperationStatus.Indeterminate, entry.status)
        assertEquals(listOf("clean:gradle:8.10", "clean:gradle:8.11"), repository.mutationCalls)
        vm.close()
    }

    @Test
    fun returningToSameRouteDoesNotPublishObsoleteDetailRead() = runTest {
        val oldGate = CompletableDeferred<Unit>()
        val latestGate = CompletableDeferred<Unit>()
        val repository = FakeSdkmanRepository(detailReader = { candidate, call ->
            if (call == 1) {
                oldGate.await()
                remoteCandidate(candidate).copy(description = "obsolete")
            } else {
                latestGate.await()
                remoteCandidate(candidate).copy(description = "latest")
            }
        })
        val vm = ZephyrViewModel(repository, StandardTestDispatcher(testScheduler))
        runCurrent()
        try {
            vm.navigate(ZephyrRoute.SdkDetail("gradle"))
            runCurrent()
            vm.navigate(ZephyrRoute.SdkDetail("kotlin"))
            vm.navigate(ZephyrRoute.SdkDetail("gradle"))
            oldGate.complete(Unit)
            runCurrent()
            assertEquals(null, assertIs<ZephyrUiState.Ready>(vm.state.value).selectedCandidate)
            latestGate.complete(Unit)
            runCurrent()
            assertEquals("latest", assertIs<ZephyrUiState.Ready>(vm.state.value).selectedCandidate?.description)
        } finally {
            oldGate.complete(Unit)
            latestGate.complete(Unit)
            vm.close()
        }
    }

    @Test
    fun unreadableLedgerIsVisibleAndBlocksAdmissionWithoutSaving() = runTest {
        val store = object : OperationStore {
            var saves = 0
            override suspend fun load(): List<OperationJournalEntry> = error("corrupt original")
            override suspend fun save(entries: List<OperationJournalEntry>) { saves += 1 }
        }
        val repository = FakeSdkmanRepository()
        val vm = ZephyrViewModel(repository, StandardTestDispatcher(testScheduler), operationStore = store)
        runCurrent()
        vm.requestTransaction(SdkmanTransaction.Uninstall("gradle", "8.10"))
        runCurrent()
        vm.confirmTransaction()
        runCurrent()
        val ready = assertIs<ZephyrUiState.Ready>(vm.state.value)
        assertTrue(ready.errorMessage.orEmpty().contains("ledger", ignoreCase = true))
        assertTrue(repository.mutationCalls.isEmpty())
        assertEquals(0, store.saves)
        vm.close()
    }

    @Test
    fun cancellingLiveWorkPersistsInterruptedOwnershipAndUncertainty() = runTest {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val repository = FakeSdkmanRepository(installStarted = started, installGate = gate)
        val store = InMemoryOperationStore()
        val vm = ZephyrViewModel(repository, StandardTestDispatcher(testScheduler), operationStore = store)
        runCurrent()
        vm.requestTransaction(SdkmanTransaction.BatchInstall(listOf(
            InstallTarget("gradle", "8.14"), InstallTarget("kotlin", "2.2.0"),
        )))
        runCurrent()
        vm.confirmTransaction()
        runCurrent()
        started.await()
        vm.close()
        runCurrent()
        val entry = store.load().single()
        assertEquals(OperationStatus.Interrupted, entry.status)
        assertEquals(OperationStepStatus.Indeterminate, entry.steps.first().status)
        assertEquals(OperationStepStatus.Pending, entry.steps.last().status)
        assertEquals(listOf("install:gradle:8.14"), repository.mutationCalls)
    }

    @Test
    fun shutdownAcknowledgementWaitsForInterruptedReceiptSave() = runTest {
        val started = CompletableDeferred<Unit>()
        val executionGate = CompletableDeferred<Unit>()
        val receiptStarted = CompletableDeferred<Unit>()
        val receiptGate = CompletableDeferred<Unit>()
        val store = object : OperationStore {
            var entries = emptyList<OperationJournalEntry>()
            override suspend fun load() = entries
            override suspend fun save(entries: List<OperationJournalEntry>) {
                if (entries.any { it.status == OperationStatus.Interrupted }) {
                    receiptStarted.complete(Unit)
                    receiptGate.await()
                }
                this.entries = entries
            }
        }
        val repository = FakeSdkmanRepository(installStarted = started, installGate = executionGate)
        val vm = ZephyrViewModel(repository, StandardTestDispatcher(testScheduler), operationStore = store)
        runCurrent()
        vm.requestTransaction(SdkmanTransaction.Install("gradle", "8.14"))
        runCurrent()
        val admission = requireNotNull(vm.confirmTransaction())
        runCurrent()
        started.await()
        assertIs<OperationAdmission.Accepted>(admission.await())
        val shutdown = async { vm.shutdownAndJoin() }
        runCurrent()
        receiptStarted.await()
        assertFalse(shutdown.isCompleted)
        assertEquals(OperationStatus.Running, store.entries.single().status)
        receiptGate.complete(Unit)
        runCurrent()
        assertTrue(shutdown.await())
        assertEquals(OperationStatus.Interrupted, store.entries.single().status)
        assertEquals(OperationStepStatus.Indeterminate, store.entries.single().steps.single().status)
    }

    @Test
    fun coordinatorQueuesAdmissionAndExplicitlyRejectsQueuedWorkOnShutdown() = runTest {
        val gate = CompletableDeferred<Unit>()
        val repository = FakeSdkmanRepository(installGate = gate)
        val store = InMemoryOperationStore()
        val coordinator = OperationCoordinator(repository, store, StandardTestDispatcher(testScheduler), { 1L })
        coordinator.initialize()
        val first = coordinator.submit(SdkmanTransaction.Install("gradle", "8.14"))
        runCurrent()
        assertIs<OperationAdmission.Accepted>(first.await())
        val second = coordinator.submit(SdkmanTransaction.Install("kotlin", "2.2.0"))
        runCurrent()
        assertFalse(second.isCompleted)
        val shutdown = async { coordinator.shutdownAndJoin() }
        runCurrent()
        assertTrue(shutdown.await())
        assertIs<OperationAdmission.Rejected>(second.await())
        assertEquals(listOf("install:gradle:8.14"), repository.mutationCalls)
        assertEquals(OperationStatus.Interrupted, store.load().single().status)
    }

    @Test
    fun recoveryEvidenceAndExecutionWritesAreSerialized() = runTest {
        val evidenceStarted = CompletableDeferred<Unit>()
        val evidenceGate = CompletableDeferred<Unit>()
        val transaction = SdkmanTransaction.Install("gradle", "8.14")
        val interrupted = OperationJournalEntry(72, transaction, 1, status = OperationStatus.Interrupted)
        val repository = FakeSdkmanRepository(satisfactionReader = {
            evidenceStarted.complete(Unit)
            evidenceGate.await()
            CommandSatisfaction.Unsatisfied
        })
        val store = InMemoryOperationStore(listOf(interrupted))
        val coordinator = OperationCoordinator(repository, store, StandardTestDispatcher(testScheduler), { 2L })
        coordinator.initialize()
        val review = async { coordinator.reviewRemaining(72) }
        runCurrent()
        evidenceStarted.await()
        val admission = coordinator.submit(SdkmanTransaction.Install("kotlin", "2.2.0"))
        runCurrent()
        assertFalse(admission.isCompleted)
        assertTrue(repository.mutationCalls.isEmpty())
        evidenceGate.complete(Unit)
        runCurrent()
        assertIs<OperationReview.Reviewed>(review.await())
        assertIs<OperationAdmission.Accepted>(admission.await())
        val saved = store.load()
        assertEquals(OperationStatus.Succeeded, saved.first().status)
        assertEquals(OperationStatus.Interrupted, saved.last().status)
        assertTrue(coordinator.shutdownAndJoin())
    }

    @Test
    fun shutdownDoesNotRewriteKnownSuccessWhileTerminalSaveSuspends() = runTest {
        val terminalStarted = CompletableDeferred<Unit>()
        val store = object : OperationStore {
            var entries = emptyList<OperationJournalEntry>()
            var terminalAttempts = 0
            override suspend fun load() = entries
            override suspend fun save(entries: List<OperationJournalEntry>) {
                if (entries.any { it.status == OperationStatus.Succeeded }) {
                    terminalAttempts += 1
                    if (terminalAttempts == 1) {
                        terminalStarted.complete(Unit)
                        CompletableDeferred<Unit>().await()
                    }
                }
                this.entries = entries
            }
        }
        val coordinator = OperationCoordinator(FakeSdkmanRepository(), store, StandardTestDispatcher(testScheduler), { 1L })
        coordinator.initialize()
        val admission = coordinator.submit(SdkmanTransaction.Uninstall("gradle", "8.10"))
        runCurrent()
        terminalStarted.await()
        assertIs<OperationAdmission.Accepted>(admission.await())
        val shutdown = async { coordinator.shutdownAndJoin() }
        runCurrent()
        assertTrue(shutdown.await())
        assertEquals(OperationStatus.Succeeded, store.entries.single().status)
        assertEquals(OperationStepStatus.Succeeded, store.entries.single().steps.single().status)
    }

    @Test
    fun latestRouteReadDoesNotWaitForObsoleteSuspendedDetail() = runTest {
        val obsoleteGate = CompletableDeferred<Unit>()
        val repository = FakeSdkmanRepository(detailReader = { candidate, _ ->
            if (candidate == "gradle") obsoleteGate.await()
            remoteCandidate(candidate)
        })
        val vm = ZephyrViewModel(repository, StandardTestDispatcher(testScheduler))
        runCurrent()
        try {
            vm.navigate(ZephyrRoute.SdkDetail("gradle"))
            runCurrent()
            vm.navigate(ZephyrRoute.SdkDetail("kotlin"))
            runCurrent()
            val ready = assertIs<ZephyrUiState.Ready>(vm.state.value)
            assertEquals("kotlin", ready.selectedCandidate?.name)
            assertEquals(null, ready.detailLoadingCandidate)
        } finally {
            vm.close()
            obsoleteGate.complete(Unit)
        }
    }

    @Test
    fun mutationDetailRefreshCannotPublishCandidateAIntoFailedRouteB() = runTest {
        val refreshGate = CompletableDeferred<Unit>()
        val refreshStarted = CompletableDeferred<Unit>()
        val repository = FakeSdkmanRepository(detailReader = { candidate, call ->
            if (call == 2) {
                refreshStarted.complete(Unit)
                refreshGate.await()
            }
            if (candidate == "kotlin") error("B unavailable")
            remoteCandidate(candidate)
        })
        val store = InMemoryOperationStore()
        val vm = ZephyrViewModel(repository, StandardTestDispatcher(testScheduler), operationStore = store, readRetryDelaysMillis = emptyList())
        runCurrent()
        try {
            vm.navigate(ZephyrRoute.SdkDetail("gradle"))
            runCurrent()
            vm.requestTransaction(SdkmanTransaction.Uninstall("gradle", "8.10"))
            runCurrent()
            vm.confirmTransaction()
            runCurrent()
            refreshStarted.await()
            vm.navigate(ZephyrRoute.SdkDetail("kotlin"))
            runCurrent()
            refreshGate.complete(Unit)
            runCurrent()
            val ready = assertIs<ZephyrUiState.Ready>(vm.state.value)
            assertEquals(ZephyrRoute.SdkDetail("kotlin"), ready.route)
            assertEquals(null, ready.selectedCandidate)
            assertTrue(ready.errorMessage.orEmpty().contains("B unavailable"))
            assertEquals(OperationStatus.Succeeded, store.load().single().status)
        } finally {
            refreshGate.complete(Unit)
            vm.close()
        }
    }

    @Test
    fun shutdownReportsUnacknowledgedReceiptInsteadOfClaimingSuccess() = runTest {
        val executionGate = CompletableDeferred<Unit>()
        val store = object : OperationStore {
            var entries = emptyList<OperationJournalEntry>()
            override suspend fun load() = entries
            override suspend fun save(entries: List<OperationJournalEntry>) {
                if (entries.any { it.status == OperationStatus.Interrupted }) CompletableDeferred<Unit>().await()
                this.entries = entries
            }
        }
        val coordinator = OperationCoordinator(
            FakeSdkmanRepository(installGate = executionGate), store, StandardTestDispatcher(testScheduler), { 1L },
            receiptTimeoutMillis = 50L,
        )
        coordinator.initialize()
        val admission = coordinator.submit(SdkmanTransaction.Install("gradle", "8.14"))
        runCurrent()
        assertIs<OperationAdmission.Accepted>(admission.await())
        val shutdown = async { coordinator.shutdownAndJoin(timeoutMillis = 100L) }
        advanceUntilIdle()
        assertFalse(shutdown.await())
        assertTrue(coordinator.state.value.ledgerFailure.orEmpty().contains("timed out"))
        assertEquals(OperationStatus.Interrupted, coordinator.state.value.entries.single().status)
        // The last durable running plan remains complete for startup verification.
        assertEquals(OperationStatus.Running, store.entries.single().status)
        assertEquals(SdkmanTransaction.Install("gradle", "8.14"), store.entries.single().transaction)
    }

    @Test
    fun cancellationDuringStepAdmissionDoesNotInventAnExternalOutcome() = runTest {
        val stepSaveStarted = CompletableDeferred<Unit>()
        val store = object : OperationStore {
            var entries = emptyList<OperationJournalEntry>()
            var saves = 0
            override suspend fun load() = entries
            override suspend fun save(entries: List<OperationJournalEntry>) {
                saves += 1
                if (saves == 2) {
                    stepSaveStarted.complete(Unit)
                    CompletableDeferred<Unit>().await()
                }
                this.entries = entries
            }
        }
        val repository = FakeSdkmanRepository()
        val coordinator = OperationCoordinator(repository, store, StandardTestDispatcher(testScheduler), { 1L })
        coordinator.initialize()
        val admission = coordinator.submit(SdkmanTransaction.Install("gradle", "8.14"))
        runCurrent()
        stepSaveStarted.await()
        assertIs<OperationAdmission.Accepted>(admission.await())
        val shutdown = async { coordinator.shutdownAndJoin() }
        runCurrent()
        assertTrue(shutdown.await())
        assertTrue(repository.mutationCalls.isEmpty())
        assertEquals(OperationStatus.Interrupted, store.entries.single().status)
        assertEquals(OperationStepStatus.Interrupted, store.entries.single().steps.single().status)
    }

    private fun testScope() = Dispatchers.Unconfined
}

private fun remoteCandidate(name: String, kind: CandidateKind = CandidateKind.Sdk) = Candidate(
    name = name,
    displayName = name,
    description = null,
    websiteUrl = null,
    kind = kind,
    installedVersions = emptyList(),
    defaultVersion = null,
    hasLocalOnlyVersions = false,
    localOnlyVersionCount = 0,
    localOnlyVersions = emptyList(),
)

private class FakeSdkmanRepository(
    private val catalogFailure: Throwable? = null,
    private val cachedCatalog: CandidateMetadataCache? = null,
    private var catalogFailuresBeforeSuccess: Int = 0,
    private val selfUpdateFailure: Throwable? = null,
    private val remoteDetail: Candidate? = null,
    private val installedCandidate: Candidate? = null,
    private val refreshStarted: CompletableDeferred<Unit>? = null,
    private val refreshGate: CompletableDeferred<Unit>? = null,
    private val refreshFailure: Throwable? = null,
    private val detailStarted: CompletableDeferred<Unit>? = null,
    private val detailGate: CompletableDeferred<Unit>? = null,
    private val detailCompleted: CompletableDeferred<Unit>? = null,
    private val detailReader: (suspend (String, Int) -> Candidate?)? = null,
    private val installOutcome: CommandOutcome = CommandOutcome(true, "Installed"),
    private val installOutcomes: Map<Pair<String, String>, CommandOutcome> = emptyMap(),
    private val installFailure: Throwable? = null,
    private val uninstallOutcomes: Map<String, CommandOutcome> = emptyMap(),
    private val defaultOutcome: CommandOutcome = CommandOutcome(true, "Default"),
    private val cleanupOutcomes: Map<String, CommandOutcome> = emptyMap(),
    private val installStarted: CompletableDeferred<Unit>? = null,
    private val installGate: CompletableDeferred<Unit>? = null,
    private var connectivity: ConnectivityStatus = testConnectivity(),
    private val connectivityResponses: List<ConnectivityStatus> = emptyList(),
    private val firstConnectivityStarted: CompletableDeferred<Unit>? = null,
    private val firstConnectivityGate: CompletableDeferred<Unit>? = null,
    private val commandSatisfaction: Map<PlannedSdkmanCommand, CommandSatisfaction> = emptyMap(),
    private val satisfactionReader: (suspend (PlannedSdkmanCommand) -> CommandSatisfaction)? = null,
    private val storageInventory: StorageInventory = StorageInventory.Empty,
) : SdkmanRepository {
    var installedCandidatesCalls: Int = 0
        private set
    var catalogCalls: Int = 0
        private set
    val catalogRefreshRequests = mutableListOf<Boolean>()
    var metadataRefreshCalls: Int = 0
        private set
    val mutationCalls = mutableListOf<String>()
    var commandSatisfactionCalls = 0
        private set
    var connectivityCalls: Int = 0
        private set
    var storageInventoryCalls: Int = 0
        private set
    private val protected = mutableSetOf<ProtectedVersion>()
    private var detailCalls = 0

    override suspend fun detect(): SdkmanStatus = SdkmanStatus(isInstalled = true, home = "/tmp/sdkman")

    override suspend fun cliVersion(): String = "SDKMAN 5"

    override suspend fun installedCandidates(): List<Candidate> {
        installedCandidatesCalls += 1
        if (installedCandidatesCalls > 1) {
            refreshStarted?.complete(Unit)
            refreshGate?.await()
            refreshFailure?.let { throw it }
        }
        return listOfNotNull(installedCandidate)
    }

    override suspend fun catalog(refreshMetadata: Boolean): List<CandidateCatalogItem> {
        catalogCalls += 1
        catalogRefreshRequests += refreshMetadata
        catalogFailure?.let { throw it }
        if (catalogFailuresBeforeSuccess > 0) {
            catalogFailuresBeforeSuccess -= 1
            throw IllegalStateException("temporary catalog failure")
        }
        return emptyList()
    }

    override suspend fun cachedCatalog(): CandidateMetadataCache? = cachedCatalog

    override suspend fun versions(candidate: String): List<CandidateVersion> = emptyList()

    override suspend fun mergedCandidate(candidate: String): Candidate? {
        detailCalls += 1
        detailReader?.let { return it(candidate, detailCalls) }
        detailStarted?.complete(Unit)
        try {
            detailGate?.await()
        } finally {
            detailCompleted?.complete(Unit)
        }
        return remoteDetail?.takeIf { it.name == candidate }
    }

    override suspend fun checkConnectivity(): ConnectivityStatus {
        connectivityCalls += 1
        if (connectivityCalls == 1) {
            firstConnectivityStarted?.complete(Unit)
            firstConnectivityGate?.await()
        }
        return connectivityResponses.getOrNull(connectivityCalls - 1) ?: connectivity
    }

    override suspend fun integrityChecks(): List<IntegrityCheck> =
        listOf(
            IntegrityCheck(
                IntegrityCheckId.RequiredScripts,
                "Required scripts",
                IntegrityStatus.Passed,
                "Available",
            ),
        )

    override suspend fun estimateDiskImpact(transaction: SdkmanTransaction): DiskImpactEstimate =
        DiskImpactEstimate(
            kind = DiskImpactKind.None,
            bytes = 0,
            confidence = EstimateConfidence.Exact,
            explanation = "No test disk impact.",
        )

    override suspend fun storageInventory(candidates: List<Candidate>): StorageInventory {
        storageInventoryCalls += 1
        return storageInventory
    }

    override suspend fun protectedVersions(): Set<ProtectedVersion> = protected

    override suspend fun setVersionProtected(
        candidate: String,
        version: String,
        protected: Boolean,
    ): CommandOutcome {
        val target = ProtectedVersion(candidate, version)
        if (protected) this.protected += target else this.protected -= target
        return CommandOutcome(true, if (protected) "Protected" else "Unprotected")
    }

    override suspend fun refreshCandidateMetadata(): CommandOutcome {
        metadataRefreshCalls += 1
        return CommandOutcome(true, "Metadata refreshed")
    }

    override suspend fun selfUpdate(): SdkmanSelfUpdateStatus {
        selfUpdateFailure?.let { throw it }
        return SdkmanSelfUpdateStatus.UpToDate
    }

    override suspend fun install(candidate: String, version: String): CommandOutcome {
        mutationCalls += "install:$candidate:$version"
        installStarted?.complete(Unit)
        installGate?.await()
        installFailure?.let { throw it }
        return installOutcomes[candidate to version] ?: installOutcome
    }

    override suspend fun uninstall(candidate: String, version: String): CommandOutcome {
        mutationCalls += "uninstall:$candidate:$version"
        return uninstallOutcomes[version] ?: CommandOutcome(true, "Uninstalled")
    }

    override suspend fun setDefault(candidate: String, version: String): CommandOutcome {
        mutationCalls += "default:$candidate:$version"
        return defaultOutcome
    }

    override suspend fun cleanLocalOnly(candidate: String, versions: List<String>): CommandOutcome {
        mutationCalls += "clean:$candidate:${versions.joinToString(",")}"
        return cleanupOutcomes[versions.singleOrNull()] ?: CommandOutcome(true, "Cleaned")
    }

    override suspend fun commandSatisfaction(command: PlannedSdkmanCommand): CommandSatisfaction {
        commandSatisfactionCalls += 1
        satisfactionReader?.let { return it(command) }
        return commandSatisfaction[command] ?: CommandSatisfaction.Indeterminate
    }
}

private class InMemoryOperationStore(
    initial: List<OperationJournalEntry> = emptyList(),
) : OperationStore {
    private var entries = initial
    val saved = mutableListOf<List<OperationJournalEntry>>()

    override suspend fun load(): List<OperationJournalEntry> = entries

    override suspend fun save(entries: List<OperationJournalEntry>) {
        this.entries = entries
        saved += entries
    }
}

private class FailingOperationStore(
    private val failOnSaveCalls: Set<Int>,
) : OperationStore {
    private var entries: List<OperationJournalEntry> = emptyList()
    private var saveCalls = 0

    override suspend fun load(): List<OperationJournalEntry> = entries

    override suspend fun save(entries: List<OperationJournalEntry>) {
        saveCalls += 1
        if (saveCalls in failOnSaveCalls) error("ledger unavailable")
        this.entries = entries
    }
}

private class InMemoryActivityStore(
    initial: List<ActivityEvent> = emptyList(),
) : ActivityStore {
    var events: List<ActivityEvent> = initial
        private set

    override suspend fun load(): List<ActivityEvent> = events

    override suspend fun save(events: List<ActivityEvent>) {
        this.events = events
    }
}

private class FakeOperationJournalExporter : OperationJournalExporter {
    val exported = mutableListOf<List<OperationJournalEntry>>()

    override suspend fun export(entries: List<OperationJournalEntry>): JournalExportResult {
        exported += entries
        return JournalExportResult("/tmp/zephyr-journal.csv", entries.size)
    }
}

private class FakeDiagnosticsExporter : DiagnosticsExporter {
    val exported = mutableListOf<DiagnosticsSnapshot>()

    override suspend fun export(snapshot: DiagnosticsSnapshot): SupportBundleExportResult {
        exported += snapshot
        return SupportBundleExportResult("/tmp/zephyr-support.txt")
    }
}

private fun testConnectivity(
    outcome: ConnectivityOutcome = ConnectivityOutcome.Online,
    route: ConnectivityRouteKind = ConnectivityRouteKind.Direct,
): ConnectivityStatus =
    ConnectivityStatus.from(
        ConnectivityDiagnostic(
            route = route,
            checkedAtEpochMillis = 1_000,
            latencyMillis = 12,
            outcome = outcome,
        ),
    )
