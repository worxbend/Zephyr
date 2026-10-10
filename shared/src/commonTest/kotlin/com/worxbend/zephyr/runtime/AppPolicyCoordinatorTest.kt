package com.worxbend.zephyr.runtime

import com.worxbend.zephyr.data.DesktopNotificationService
import com.worxbend.zephyr.domain.Candidate
import com.worxbend.zephyr.domain.CandidateKind
import com.worxbend.zephyr.domain.LocalOnlyCandidateScanProgress
import com.worxbend.zephyr.domain.LocalOnlyCandidateScanStatus
import com.worxbend.zephyr.domain.LocalOnlyScanProgress
import com.worxbend.zephyr.domain.OperationJournalEntry
import com.worxbend.zephyr.domain.OperationStatus
import com.worxbend.zephyr.domain.RemoteEvidenceState
import com.worxbend.zephyr.domain.SdkmanStatus
import com.worxbend.zephyr.domain.SdkmanTransaction
import com.worxbend.zephyr.settings.AppSettings
import com.worxbend.zephyr.settings.AppSettingsRepository
import com.worxbend.zephyr.settings.AppSettingsStore
import com.worxbend.zephyr.settings.CleanupGracePeriod
import com.worxbend.zephyr.settings.LocalOnlyObservation
import com.worxbend.zephyr.settings.OperationNotificationPolicy
import com.worxbend.zephyr.viewmodel.ZephyrRoute
import com.worxbend.zephyr.viewmodel.ZephyrUiState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AppPolicyCoordinatorTest {
    private fun ready(journal: List<OperationJournalEntry> = emptyList(), scan: LocalOnlyScanProgress? = null) =
        ZephyrUiState.Ready(
            sdkmanStatus = SdkmanStatus(true, "fixture"), route = ZephyrRoute.Overview, previousRoute = null,
            candidates = emptyList(), catalog = emptyList(), selectedCandidate = null,
            isRefreshing = false, isCatalogLoading = false, localOnlyScanInProgress = false,
            errorMessage = null, lastOutcome = null, operationJournal = journal, localOnlyScanProgress = scan,
        )

    private fun entry(id: Long) = OperationJournalEntry(
        id, SdkmanTransaction.Install("gradle", "8.0"), 1L, 2L, OperationStatus.Succeeded, "fixture",
    )

    @Test
    fun policyTogglesNeverReplayHydratedOrAlreadyObservedCompletions() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settings = AppSettingsStore(MemorySettings(AppSettings(operationNotificationPolicy = OperationNotificationPolicy.AllCompletions)), dispatcher)
        val states = MutableStateFlow<ZephyrUiState>(ready(listOf(entry(1))))
        val notifications = mutableListOf<String>()
        val service = object : DesktopNotificationService {
            override fun show(title: String, message: String): Boolean { notifications += title; return true }
        }
        val coordinator = AppPolicyCoordinator(states, settings, service, {}, dispatcher) { 100L }
        try {
            runCurrent()
            assertTrue(notifications.isEmpty())
            settings.update { it.copy(operationNotificationPolicy = OperationNotificationPolicy.Off) }
            runCurrent()
            states.value = ready(listOf(entry(2), entry(1)))
            runCurrent()
            settings.update { it.copy(operationNotificationPolicy = OperationNotificationPolicy.AllCompletions) }
            runCurrent()
            assertTrue(notifications.isEmpty())
            states.value = ready(listOf(entry(3), entry(2), entry(1)))
            runCurrent()
            assertEquals(1, notifications.size)
        } finally { coordinator.close(); settings.close() }
    }

    @Test
    fun blockedSettingsHydrationNeverErasesPersistedObservationHistory() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val gate = CompletableDeferred<Unit>()
        val initial = AppSettings(
            cleanupGracePeriod = CleanupGracePeriod.SevenDays,
            localOnlyObservations = listOf(LocalOnlyObservation("gradle", "7.6", 1L)),
        )
        val saved = mutableListOf<AppSettings>()
        val settings = AppSettingsStore(object : AppSettingsRepository {
            override suspend fun load(): AppSettings { gate.await(); return initial }
            override suspend fun save(settings: AppSettings) { saved += settings }
        }, dispatcher)
        val coordinator = AppPolicyCoordinator(MutableStateFlow<ZephyrUiState>(ready()), settings, object : DesktopNotificationService {
            override fun show(title: String, message: String) = true
        }, {}, dispatcher) { 100L }
        runCurrent()
        gate.complete(Unit)
        runCurrent()
        assertEquals(initial.localOnlyObservations, settings.state.value.localOnlyObservations)
        assertTrue(saved.all { it.localOnlyObservations == initial.localOnlyObservations })
        assertTrue(coordinator.closeAndJoin())
        settings.close()
        runCurrent()
    }

    @Test
    fun policyShutdownStopsFutureCompletionNotifications() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settings = AppSettingsStore(MemorySettings(AppSettings(operationNotificationPolicy = OperationNotificationPolicy.AllCompletions)), dispatcher)
        val states = MutableStateFlow<ZephyrUiState>(ready())
        var shown = 0
        val coordinator = AppPolicyCoordinator(states, settings, object : DesktopNotificationService {
            override fun show(title: String, message: String): Boolean { shown++; return true }
        }, {}, dispatcher)
        runCurrent()
        assertTrue(coordinator.closeAndJoin())
        states.value = ready(listOf(entry(1)))
        runCurrent()
        assertEquals(0, shown)
        settings.close()
        runCurrent()
    }

    @Test
    fun partialAuditPreservesUnauditedHistoryAndVerifiedResolutionRemovesOnlyItsCandidate() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val initial = AppSettings(
            cleanupGracePeriod = CleanupGracePeriod.SevenDays,
            localOnlyObservations = listOf(LocalOnlyObservation("gradle", "7.6", 1L), LocalOnlyObservation("java", "17-tem", 1L)),
        )
        val settings = AppSettingsStore(MemorySettings(initial), dispatcher)
        val states = MutableStateFlow<ZephyrUiState>(ready())
        val coordinator = AppPolicyCoordinator(states, settings, object : DesktopNotificationService {
            override fun show(title: String, message: String) = true
        }, {}, dispatcher) { 100L }
        try {
            runCurrent()
            assertEquals(initial.localOnlyObservations, settings.state.value.localOnlyObservations)
            val resolved = Candidate("java", "JDK", kind = CandidateKind.Jdk, installedVersions = emptyList(),
                defaultVersion = null, hasLocalOnlyVersions = false, localOnlyVersionCount = 0,
                localOnlyVersions = emptyList(), remoteEvidence = RemoteEvidenceState.LiveComplete)
            states.value = ready(scan = LocalOnlyScanProgress(listOf(
                LocalOnlyCandidateScanProgress("java", LocalOnlyCandidateScanStatus.Completed, resolved),
                LocalOnlyCandidateScanProgress("gradle", LocalOnlyCandidateScanStatus.Failed),
            ), false))
            runCurrent()
            assertEquals(listOf(LocalOnlyObservation("gradle", "7.6", 1L)), settings.state.value.localOnlyObservations)
        } finally { coordinator.close(); settings.close() }
    }
}

private class MemorySettings(private var settings: AppSettings) : AppSettingsRepository {
    override suspend fun load() = settings
    override suspend fun save(settings: AppSettings) { this.settings = settings }
}
