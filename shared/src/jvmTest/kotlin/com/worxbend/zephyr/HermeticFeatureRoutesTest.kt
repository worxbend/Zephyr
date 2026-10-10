package com.worxbend.zephyr

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.worxbend.zephyr.domain.*
import com.worxbend.zephyr.features.catalog.CatalogActions
import com.worxbend.zephyr.settings.*
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class HermeticFeatureRoutesTest {
    @Test
    fun actualSettingsRouteLoadsMemoryPortsAndImportsPreferences() = runDesktopComposeUiTest(width = 1000, height = 900) {
        val ports = MemoryRouteServices()
        var result = AppSettings()
        var retries = 0
        setContent {
            MemoryRouteHost(ports) {
                var settings by remember { mutableStateOf(AppSettings()) }
                SettingsScreen(
                    settings, { transform -> settings = transform(settings); result = settings },
                    SettingsSaveStatus.Failed(1, 0, SettingsFailureReason.Save), { retries++ },
                )
            }
        }
        onNodeWithText("Settings").assertExists()
        onNodeWithText("Retry settings save").performScrollTo().performClick()
        runOnIdle { assertEquals(1, retries) }
        onNodeWithText("Choose SDKMAN home…").performScrollTo().performClick()
        onNodeWithText("chosen-memory-home").performScrollTo().assertExists()
        onNodeWithText("Save proxy").performScrollTo().performClick()
        waitUntil { ports.proxySaveCount == 1 }
        onNodeWithText("Proxy saved in memory").performScrollTo().assertExists()
        onNodeWithText("Import preferences…").performScrollTo().performClick()
        waitUntil { result.themePreference == ThemePreference.Dark }
        onNodeWithText("Imported portable preferences.").performScrollTo().assertExists()
        runOnIdle { assertEquals(2, ports.proxyLoadCount) }
    }

    @Test
    fun actualSettingsRouteDistinguishesFailedLoadFromFailedSave() = runDesktopComposeUiTest(width = 1000, height = 900) {
        val ports = MemoryRouteServices()
        var status: SettingsSaveStatus by mutableStateOf(SettingsSaveStatus.Failed(0, 0, SettingsFailureReason.Load))
        setContent { MemoryRouteHost(ports) { SettingsScreen(AppSettings(), {}, status, {}) } }
        onNodeWithText("Saved settings could not be loaded. Existing persisted settings have not been replaced.").assertExists()
        onNodeWithText("Settings saved.").assertDoesNotExist()
        runOnIdle { status = SettingsSaveStatus.Saving(1, 0) }
        onNodeWithText("Saving settings…").assertExists()
        runOnIdle { status = SettingsSaveStatus.Failed(1, 0, SettingsFailureReason.Save) }
        onNodeWithText("Settings have unsaved changes. Saving failed; changes are not durably confirmed.").assertExists()
        onNodeWithText("Settings saved.").assertDoesNotExist()
    }

    @Test
    fun actualProjectImportUsesInjectedChooser() = runDesktopComposeUiTest(width = 1100, height = 900) {
        val ports = MemoryRouteServices()
        setContent { MemoryRouteHost(ports) { ProjectToolchainImportScreen(memoryReady()) } }
        onAllNodesWithText("Choose .sdkmanrc")[0].performClick()
        waitUntil { ports.projectChooseCount == 1 }
        onNodeWithText("memory.sdkmanrc").assertExists()
        onNodeWithText("gradle 9.0").assertExists()
    }

    @Test
    fun actualProjectExportWritesOnlyChosenDefaults() = runDesktopComposeUiTest(width = 1100, height = 900) {
        val ports = MemoryRouteServices()
        val candidate = memoryLocalOnlyCandidate().copy(defaultVersion = "9.0")
        setContent { MemoryRouteHost(ports) { ProjectToolchainExportScreen(memoryReady(listOf(candidate)), {}) } }
        onNodeWithText("Export selected (1)").performClick()
        waitUntil { ports.projectWrites.isNotEmpty() }
        runOnIdle { assertEquals(listOf(InstallTarget("gradle", "9.0")), ports.projectWrites) }
        onNodeWithText("Exported 1 defaults to memory.sdkmanrc.").assertExists()
    }

    @Test
    fun actualWorkspaceRouteReadsMemoryProjectAndLaunchesInjectedTerminal() = runDesktopComposeUiTest(width = 1200, height = 900) {
        val ports = MemoryRouteServices()
        val reference = ProjectWorkspaceReference("memory.sdkmanrc", "Memory workspace")
        val candidate = memoryLocalOnlyCandidate().copy(installedVersions = listOf(CandidateVersion("9.0", true, true, RemoteAvailability.Available)), defaultVersion = "9.0")
        setContent {
            MemoryRouteHost(ports) {
                ProjectWorkspacesScreen(memoryReady(listOf(candidate)), {}, AppSettings(projectWorkspaces = listOf(reference)), {})
            }
        }
        onNodeWithText("Memory workspace").assertExists()
        onNodeWithText("Open scoped terminal").performClick()
        onNodeWithText("Memory workspace terminal").assertExists()
    }

    @Test
    fun actualSnapshotRouteImportsReviewsAndExportsWithoutDialogs() = runDesktopComposeUiTest(width = 1400, height = 1000) {
        val ports = MemoryRouteServices()
        var reviewed = emptyList<PlannedSdkmanCommand>()
        setContent {
            MemoryRouteHost(ports) {
                EnvironmentSnapshotScreen(memoryReady(listOf(memoryLocalOnlyCandidate())), { reviewed = it }, {}, {})
            }
        }
        onNodeWithText("Import restore…").performClick()
        onNodeWithText("Loaded snapshot with 1 candidate(s).").assertExists()
        onNodeWithText("Restore 2 step(s)").performClick()
        runOnIdle { assertEquals(listOf(SdkmanCommandAction.Install, SdkmanCommandAction.SetDefault), reviewed.map { it.action }) }
        onNodeWithText("Export snapshot").performClick()
        waitUntil { ports.snapshotWrites.isNotEmpty() }
        runOnIdle { assertEquals(listOf("default", "protected", "removable"), ports.snapshotWrites.single().candidates.single().installedVersions) }
    }

    @Test
    fun actualTaskRouteVerifiesIndeterminateOnlyEntryWithoutReplayEligibility() = runDesktopComposeUiTest(width = 1100, height = 900) {
        val ports = MemoryRouteServices()
        val original = OperationJournalEntry(42, SdkmanTransaction.Install("gradle", "9.0"), 0)
        val entry = original.copy(
            status = OperationStatus.Interrupted,
            steps = original.steps.map { it.copy(status = OperationStepStatus.Indeterminate) },
        )
        val reviewed = mutableListOf<Long>()
        assertEquals(emptyList(), entry.resumableCommands())
        setContent {
            MemoryRouteHost(ports) {
                OperationHistoryScreen(memoryReady().copy(operationJournal = listOf(entry)), {}, {}, { _, _ -> error("Verification is not a recovery command") }, { reviewed += it })
            }
        }
        onNodeWithText("Review remaining 1").assertDoesNotExist()
        onNodeWithText("Verify outcome").performClick()
        runOnIdle { assertEquals(listOf(42L), reviewed) }
    }

    @Test
    fun actualTaskRouteDisablesVerificationWhileTerminalReceiptIsStillLiveOwned() = runDesktopComposeUiTest(width = 1100, height = 900) {
        val ports = MemoryRouteServices()
        val original = OperationJournalEntry(43, SdkmanTransaction.BatchInstall(listOf(InstallTarget("gradle", "9.0"), InstallTarget("maven", "3.9"))), 0)
        val entry = original.copy(
            status = OperationStatus.Interrupted,
            steps = original.steps.map { if (it.index == 0) it.copy(status = OperationStepStatus.Indeterminate) else it },
        )
        val reviewed = mutableListOf<Long>()
        setContent {
            MemoryRouteHost(ports) {
                OperationHistoryScreen(memoryReady().copy(operationJournal = listOf(entry), liveOperationIds = setOf(entry.id)), {}, {}, { _, _ -> error("No recovery for a live task") }, { reviewed += it })
            }
        }
        onNodeWithText("Verify outcome").assertIsNotEnabled().performClick()
        onNodeWithText("Review remaining 1").assertIsNotEnabled().performClick()
        runOnIdle { assertEquals(emptyList(), reviewed) }
    }

    @Test
    fun actualTaskRouteDisablesRecoveryForLiveEntries() = runDesktopComposeUiTest(width = 1100, height = 900) {
        val ports = MemoryRouteServices()
        val original = OperationJournalEntry(1, SdkmanTransaction.BatchInstall(listOf(InstallTarget("gradle", "9.0"), InstallTarget("maven", "3.9"))), 0)
        val entry = original.copy(steps = original.steps.map { if (it.index == 0) it.copy(status = OperationStepStatus.Indeterminate) else it })
        setContent {
            MemoryRouteHost(ports) {
                OperationHistoryScreen(memoryReady().copy(operationJournal = listOf(entry)), {}, {}, { _, _ -> error("No recovery for a live task") }, { error("No resume for a live task") })
            }
        }
        onNodeWithText("Verify outcome").assertIsNotEnabled().performClick()
        onNodeWithText("Review remaining 1").assertIsNotEnabled().performClick()
    }

    @Test
    fun actualTaskRouteVerifiesEntryLevelUncertaintyEvenWithoutIndeterminateSteps() = runDesktopComposeUiTest(width = 1100, height = 900) {
        val ports = MemoryRouteServices()
        val original = OperationJournalEntry(44, SdkmanTransaction.Install("gradle", "9.0"), 0)
        var entry by mutableStateOf(original.copy(
            status = OperationStatus.Indeterminate,
            steps = original.steps.map { it.copy(status = OperationStepStatus.Succeeded) },
        ))
        val reviewed = mutableListOf<Long>()
        setContent {
            MemoryRouteHost(ports) {
                OperationHistoryScreen(memoryReady().copy(operationJournal = listOf(entry)), {}, {}, { _, _ -> error("Verification is not a recovery command") }, { reviewed += it })
            }
        }
        onNodeWithText("Review remaining 1").assertDoesNotExist()
        onNodeWithText("Verify outcome").performClick()
        runOnIdle {
            assertEquals(listOf(44L), reviewed)
            entry = entry.copy(status = OperationStatus.Succeeded)
        }
        onNodeWithText("Verify outcome").assertDoesNotExist()
    }

    @Test
    fun actualTaskRoutePreservesReviewRemainingAlongsideVerificationForMixedSteps() = runDesktopComposeUiTest(width = 1100, height = 900) {
        val ports = MemoryRouteServices()
        val original = OperationJournalEntry(45, SdkmanTransaction.BatchInstall(listOf(InstallTarget("gradle", "9.0"), InstallTarget("maven", "3.9"))), 0)
        val entry = original.copy(
            status = OperationStatus.Interrupted,
            steps = original.steps.map { if (it.index == 0) it.copy(status = OperationStepStatus.Indeterminate) else it.copy(status = OperationStepStatus.Interrupted) },
        )
        val reviewed = mutableListOf<Long>()
        assertEquals(listOf(original.steps[1].command), entry.resumableCommands())
        setContent {
            MemoryRouteHost(ports) {
                OperationHistoryScreen(memoryReady().copy(operationJournal = listOf(entry)), {}, {}, { _, _ -> error("Review must not execute recovery commands") }, { reviewed += it })
            }
        }
        onNodeWithText("Verify outcome").performClick()
        onNodeWithText("Review remaining 1").performClick()
        runOnIdle { assertEquals(listOf(45L, 45L), reviewed) }
    }

    @Test
    fun actualTaskRouteKeepsReviewRemainingForEligibleStepsWithoutUncertainty() = runDesktopComposeUiTest(width = 1100, height = 900) {
        val ports = MemoryRouteServices()
        val original = OperationJournalEntry(46, SdkmanTransaction.Install("gradle", "9.0"), 0)
        val entry = original.copy(status = OperationStatus.Interrupted, steps = original.steps.map { it.copy(status = OperationStepStatus.Interrupted) })
        val reviewed = mutableListOf<Long>()
        setContent {
            MemoryRouteHost(ports) {
                OperationHistoryScreen(memoryReady().copy(operationJournal = listOf(entry)), {}, {}, { _, _ -> error("Review must not execute recovery commands") }, { reviewed += it })
            }
        }
        onNodeWithText("Verify outcome").assertDoesNotExist()
        onNodeWithText("Review remaining 1").performClick()
        runOnIdle { assertEquals(listOf(46L), reviewed) }
    }

    @Test
    fun actualTaskRouteDisablesFailedRecoveryActionsWhileEntryIsLiveOwned() = runDesktopComposeUiTest(width = 1100, height = 900) {
        val ports = MemoryRouteServices()
        val original = OperationJournalEntry(47, SdkmanTransaction.Install("gradle", "9.0"), 0)
        val entry = original.copy(status = OperationStatus.Failed, steps = original.steps.map { it.copy(status = OperationStepStatus.Failed) })
        setContent {
            MemoryRouteHost(ports) {
                OperationHistoryScreen(memoryReady().copy(operationJournal = listOf(entry), liveOperationIds = setOf(entry.id)), {}, {}, { _, _ -> error("No recovery for a live task") }, { error("No review for a live task") })
            }
        }
        entry.transaction.recoveryGuidance().actions.forEach { action ->
            onNodeWithText(action.label).assertIsNotEnabled().performClick()
        }
        onNodeWithText("Review remaining 1").assertIsNotEnabled().performClick()
    }

    @Test
    fun actualLocalOnlyRouteExcludesDefaultAndProtectedTargets() = runDesktopComposeUiTest(width = 1100, height = 900) {
        val ports = MemoryRouteServices()
        val candidate = memoryLocalOnlyCandidate()
        var cleaned = emptyList<String>()
        val ready = memoryReady(listOf(candidate)).copy(
            protectedVersions = setOf(ProtectedVersion("gradle", "protected")),
            localOnlyScanProgress = LocalOnlyScanProgress(listOf(LocalOnlyCandidateScanProgress("gradle", LocalOnlyCandidateScanStatus.Completed, candidate)), false),
        )
        setContent { MemoryRouteHost(ports) { LocalOnlyScreen(ready, CleanupGracePeriod.Off, emptySet(), {}, {}, {}, { _, versions -> cleaned = versions }) } }
        onNodeWithText("Clean unprotected").performClick()
        runOnIdle { assertEquals(listOf("removable"), cleaned) }
    }

    @Test
    fun actualInstalledTableUsesSameCleanupTargetsAsCards() = runDesktopComposeUiTest(width = 1100, height = 900) {
        val ports = MemoryRouteServices()
        val candidate = memoryLocalOnlyCandidate()
        var cleaned = emptyList<String>()
        val ready = memoryReady(listOf(candidate)).copy(
            protectedVersions = setOf(ProtectedVersion("gradle", "protected")),
            localOnlyScanProgress = LocalOnlyScanProgress(listOf(LocalOnlyCandidateScanProgress("gradle", LocalOnlyCandidateScanStatus.Completed, candidate)), false),
        )
        setContent {
            MemoryRouteHost(ports) {
                InstalledSdksScreen(ready, AppSettings(installedViewMode = CollectionViewMode.Table), {}, {}, { _, versions -> cleaned = versions })
            }
        }
        onNodeWithText("Clean").performClick()
        runOnIdle { assertEquals(listOf("removable"), cleaned) }
    }

    @Test
    fun actualLocalOnlyRouteOffersNoCleanupForDefaultOnlyOrStaleEvidence() = runDesktopComposeUiTest(width = 1100, height = 900) {
        val ports = MemoryRouteServices()
        val original = memoryLocalOnlyCandidate()
        val candidate = original.copy(installedVersions = original.installedVersions.take(1), localOnlyVersions = listOf("default"), localOnlyVersionCount = 1)
        var ready by mutableStateOf(memoryReady(listOf(candidate)).copy(
            localOnlyScanProgress = LocalOnlyScanProgress(listOf(LocalOnlyCandidateScanProgress("gradle", LocalOnlyCandidateScanStatus.Completed, candidate)), false),
        ))
        setContent { MemoryRouteHost(ports) { LocalOnlyScreen(ready, CleanupGracePeriod.Off, emptySet(), {}, {}, {}, { _, _ -> error("No eligible targets") }) } }
        onNodeWithText("Clean unprotected").assertDoesNotExist()
        runOnIdle {
            ready = memoryReady(listOf(original)).copy(localOnlyScanProgress = LocalOnlyScanProgress(
                listOf(LocalOnlyCandidateScanProgress("gradle", LocalOnlyCandidateScanStatus.Completed, original.copy(remoteEvidence = RemoteEvidenceState.LivePartial))), false,
            ))
        }
        onNodeWithText("Clean unprotected").assertDoesNotExist()
        onNodeWithText("Cleanup requires a completed trusted read").assertExists()
    }

    @Test
    fun actualDetailWithoutAuditDoesNotOfferCleanupDespiteLiveRemoteMetadata() = runDesktopComposeUiTest(width = 1100, height = 900) {
        val ports = MemoryRouteServices()
        val candidate = memoryLocalOnlyCandidate()
        setContent {
            MemoryRouteHost(ports) {
                CandidateDetailScreen(memoryReady(listOf(candidate)).copy(selectedCandidate = candidate), "gradle", false, CatalogActions({}, { _, _, _ -> }), { _, _ -> error("Unaudited cleanup") }, { _, _ -> })
            }
        }
        onNodeWithText("Clean").assertDoesNotExist()
        onNodeWithText("removable").assertExists()
    }

    @Test
    fun actualDetailRejectsWrongCandidateSelection() = runDesktopComposeUiTest(width = 1100, height = 900) {
        val ports = MemoryRouteServices()
        val wrong = memoryLocalOnlyCandidate().copy(name = "maven", displayName = "Wrong Maven")
        setContent {
            MemoryRouteHost(ports) {
                CandidateDetailScreen(memoryReady(listOf(memoryLocalOnlyCandidate())).copy(selectedCandidate = wrong), "gradle", false, CatalogActions({}, { _, _, _ -> }), { _, _ -> }, { _, _ -> })
            }
        }
        onNodeWithText("Wrong Maven").assertDoesNotExist()
        onNodeWithText("Gradle").assertExists()
    }
}

@Composable
private fun MemoryRouteHost(ports: MemoryRouteServices, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalAppServices provides ports.services) {
        ZephyrTheme(darkTheme = false, reducedMotion = true) { Surface(Modifier.fillMaxSize(), content = content) }
    }
}
