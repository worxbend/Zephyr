package com.worxbend.zephyr

import com.worxbend.zephyr.data.SdkmanHomeSelectionResult
import com.worxbend.zephyr.features.settings.SettingsPresenter
import com.worxbend.zephyr.features.toolchains.EnvironmentSnapshotPresenter
import com.worxbend.zephyr.features.toolchains.ProjectToolchainPresenter
import com.worxbend.zephyr.features.toolchains.ProjectWorkspacesPresenter
import com.worxbend.zephyr.settings.AppSettings
import com.worxbend.zephyr.settings.ThemePreference
import com.worxbend.zephyr.settings.ProjectWorkspaceReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FeaturePresentersTest {
    @Test
    fun settingsOwnsLoadingAdmissionImportAndFailedHomeClear() = runTest {
        val ports = MemoryRouteServices()
        val gate = CompletableDeferred<Unit>()
        ports.proxyLoad = { gate.await(); ports.configuration }
        val presenter = SettingsPresenter(ports.proxy, ports.sdkmanHome, ports.preferences, backgroundScope)
        assertTrue(presenter.state.value.busy)
        presenter.saveProxy(null)
        runCurrent()
        assertEquals(0, ports.proxySaveCount)
        gate.complete(Unit)
        runCurrent()
        assertEquals("memory-proxy", presenter.state.value.proxy.host)
        assertEquals("memory-home", presenter.state.value.customSdkmanHome)
        presenter.clearHome()
        runCurrent()
        assertEquals("memory-home", presenter.state.value.customSdkmanHome)
        assertEquals("Clear failed", presenter.state.value.sdkmanHomeMessage)
        ports.clearHomeResult = SdkmanHomeSelectionResult(true, message = "Cleared")
        presenter.clearHome()
        runCurrent()
        assertEquals(null, presenter.state.value.customSdkmanHome)
        var settings = AppSettings()
        presenter.importPreferences { transform -> settings = transform(settings) }
        runCurrent()
        assertEquals(ThemePreference.Dark, settings.themePreference)
        assertFalse(presenter.state.value.busy)
        presenter.close()
    }

    @Test
    fun settingsCancellationIsNotReportedAsFailure() = runTest {
        val ports = MemoryRouteServices()
        var cancelled = false
        ports.proxyLoad = { try { awaitCancellation() } finally { cancelled = true } }
        val presenter = SettingsPresenter(ports.proxy, ports.sdkmanHome, ports.preferences, backgroundScope)
        runCurrent()
        presenter.close()
        runCurrent()
        assertTrue(cancelled)
        assertEquals(null, presenter.state.value.proxyMessage)
        assertFalse(presenter.state.value.busy)
    }

    @Test
    fun projectChooserIsSingleFlightAndCanRetryAfterFailure() = runTest {
        val ports = MemoryRouteServices()
        val gate = CompletableDeferred<Unit>()
        ports.chooseProject = { gate.await(); error("Unreadable memory document") }
        val presenter = ProjectToolchainPresenter(ports.projects, backgroundScope)
        presenter.chooseDocument()
        presenter.chooseDocument()
        runCurrent()
        assertEquals(1, ports.projectChooseCount)
        gate.complete(Unit)
        runCurrent()
        assertFalse(presenter.state.value.busy)
        assertEquals("Unreadable memory document", presenter.state.value.error)
        ports.chooseProject = null
        presenter.chooseDocument()
        runCurrent()
        assertEquals(ports.project, presenter.state.value.document)
        assertEquals(null, presenter.state.value.error)
        presenter.export(ports.project.targets)
        runCurrent()
        assertEquals(ports.project.targets, ports.projectWrites)
        presenter.close()
    }

    @Test
    fun snapshotOwnsCaptureImportAndExportWithInjectedClock() = runTest {
        val ports = MemoryRouteServices()
        val presenter = EnvironmentSnapshotPresenter(ports.snapshots, backgroundScope) { 123 }
        presenter.updateCandidates(listOf(memoryLocalOnlyCandidate()))
        presenter.captureBaseline()
        assertEquals(123, presenter.state.value.baseline?.capturedAtEpochMillis)
        presenter.importRestore()
        runCurrent()
        assertEquals(ports.snapshot, presenter.state.value.restoreSnapshot)
        presenter.export()
        runCurrent()
        assertEquals(presenter.state.value.current, ports.snapshotWrites.single())
        presenter.close()
    }

    @Test
    fun workspaceRefreshCancelsObsoleteReadAndPublishesOnlyNewestReferenceSet() = runTest {
        val ports = MemoryRouteServices()
        val old = ProjectWorkspaceReference("old", "old.sdkmanrc")
        val current = ProjectWorkspaceReference("new", "new.sdkmanrc")
        var oldCancelled = false
        ports.readWorkspace = { reference ->
            if (reference == old) {
                try { awaitCancellation() } finally { oldCancelled = true }
            } else com.worxbend.zephyr.data.ProjectWorkspaceDocument(reference, "memory-project", ports.project)
        }
        val presenter = ProjectWorkspacesPresenter(ports.projects, ports.terminal, backgroundScope)
        presenter.refresh(listOf(old))
        runCurrent()
        presenter.refresh(listOf(current))
        runCurrent()
        assertTrue(oldCancelled)
        assertEquals(setOf(current.sdkmanRcPath), presenter.state.value.documents.keys)
        assertTrue(presenter.state.value.errors.isEmpty())
        assertFalse(presenter.state.value.loading)
        presenter.close()
    }
}
