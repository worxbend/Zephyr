package com.worxbend.zephyr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.worxbend.zephyr.data.currentEpochMillis
import com.worxbend.zephyr.domain.CandidateKind
import com.worxbend.zephyr.domain.SdkmanTransaction
import com.worxbend.zephyr.settings.AppSettings
import com.worxbend.zephyr.settings.reviewDueLocalOnly
import com.worxbend.zephyr.viewmodel.ZephyrRoute
import com.worxbend.zephyr.viewmodel.ZephyrUiState
import com.worxbend.zephyr.viewmodel.ZephyrViewModel

@Composable
internal fun Content(
    state: ZephyrUiState.Ready,
    viewModel: ZephyrViewModel,
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onClean: (String, List<String>) -> Unit,
    onUninstall: (String, String) -> Unit,
    settingsSaveStatus: com.worxbend.zephyr.settings.SettingsSaveStatus? = null,
    onRetrySettingsSave: () -> Unit = {},
) {
    val metrics = LocalZephyrMetrics.current
    Box(Modifier.fillMaxSize().padding(metrics.pagePadding), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 1280.dp).fillMaxSize(), contentAlignment = Alignment.TopCenter) {
            when (val route = state.route) {
                ZephyrRoute.Overview -> OverviewScreen(state, viewModel, settings, onSettingsChange)
                ZephyrRoute.InstalledJdk -> InstalledJdkScreen(
                    state,
                    viewModel::navigate,
                    viewModel::setVersionProtected,
                    onClean,
                )
                ZephyrRoute.InstalledSdks -> InstalledSdksScreen(
                    state,
                    settings,
                    onSettingsChange,
                    viewModel::navigate,
                    onClean,
                )
                ZephyrRoute.BrowseJdks -> BrowseScreen(
                    state = state,
                    viewModel = viewModel,
                    settings = settings,
                    onSettingsChange = onSettingsChange,
                    onClean = onClean,
                    onUninstall = onUninstall,
                )
                ZephyrRoute.BrowseSdks -> BrowseScreen(
                    title = "Browse SDKs",
                    subtitle = state.catalogFreshnessDescription(),
                    items = state.catalog.filter { it.kind == CandidateKind.Sdk },
                    loading = state.isCatalogLoading,
                    favoriteCandidates = settings.favoriteCandidates,
                    viewMode = settings.catalogViewMode,
                    onViewModeChange = { mode ->
                        onSettingsChange { it.copy(catalogViewMode = mode) }
                    },
                    onFavoriteChange = { candidate, favorite ->
                        onSettingsChange {
                            it.copy(
                                favoriteCandidates = it.favoriteCandidates.updated(candidate, favorite),
                            )
                        }
                    },
                    onOpen = { viewModel.navigate(ZephyrRoute.SdkDetail(it.name)) },
                    onRefresh = { viewModel.requestTransaction(SdkmanTransaction.RefreshMetadata) },
                )
                ZephyrRoute.LocalOnly -> LocalOnlyScreen(
                    state = state,
                    cleanupGracePeriod = settings.cleanupGracePeriod,
                    reviewDueVersions = settings.reviewDueLocalOnly(currentEpochMillis()),
                    onNavigate = viewModel::navigate,
                    onScan = viewModel::scanLocalOnly,
                    onRetryFailed = viewModel::retryFailedLocalOnlyReads,
                    onClean = onClean,
                )
                ZephyrRoute.Storage -> StorageCenterScreen(state, viewModel)
                ZephyrRoute.UpdateCenter -> UpdateCenterScreen(state, viewModel)
                ZephyrRoute.BatchUninstall -> BatchUninstallScreen(state, viewModel)
                ZephyrRoute.Profiles -> ToolchainProfilesScreen(state, viewModel, settings, onSettingsChange)
                ZephyrRoute.ProjectWorkspaces -> ProjectWorkspacesScreen(state, { viewModel.requestTransaction(SdkmanTransaction.BatchInstall(it)) }, settings, onSettingsChange)
                ZephyrRoute.ProjectImport -> ProjectToolchainImportScreen(state)
                ZephyrRoute.ProjectExport -> ProjectToolchainExportScreen(state, { viewModel.navigate(ZephyrRoute.BrowseSdks) })
                ZephyrRoute.EnvironmentSnapshot -> EnvironmentSnapshotScreen(state, { viewModel.requestTransaction(SdkmanTransaction.SnapshotRestore(it)) }, { viewModel.navigate(ZephyrRoute.BrowseSdks) }, onSettingsChange)
                ZephyrRoute.Comparison -> CandidateComparisonScreen(state, viewModel)
                ZephyrRoute.Diagnostics -> DiagnosticsScreen(
                    state,
                    viewModel::refreshConnectivity,
                    viewModel::refreshIntegrity,
                    viewModel::exportDiagnostics,
                )
                ZephyrRoute.History -> OperationHistoryScreen(
                    state, viewModel::exportJournal, { viewModel.navigate(ZephyrRoute.UpdateCenter) },
                    { entry, action ->
                        when (action) {
                            com.worxbend.zephyr.domain.RecoveryAction.Retry -> viewModel.retryTransaction(entry.transaction)
                            com.worxbend.zephyr.domain.RecoveryAction.RefreshInstalled -> viewModel.refreshInstalled()
                            com.worxbend.zephyr.domain.RecoveryAction.RefreshMetadata -> viewModel.requestTransaction(SdkmanTransaction.RefreshMetadata)
                            com.worxbend.zephyr.domain.RecoveryAction.ScanLocalOnly -> viewModel.scanLocalOnly()
                            com.worxbend.zephyr.domain.RecoveryAction.OpenDiagnostics -> viewModel.navigate(ZephyrRoute.Diagnostics)
                        }
                    }, viewModel::requestResumeOperation,
                )
                ZephyrRoute.Settings -> SettingsScreen(settings, onSettingsChange, settingsSaveStatus, onRetrySettingsSave)
                ZephyrRoute.About -> AboutScreen(state)
                is ZephyrRoute.JdkDetail -> CandidateDetailScreen(state, route.candidate, true, com.worxbend.zephyr.features.catalog.CatalogActions(viewModel::requestTransaction, viewModel::setVersionProtected), onClean, onUninstall)
                is ZephyrRoute.SdkDetail -> CandidateDetailScreen(state, route.candidate, false, com.worxbend.zephyr.features.catalog.CatalogActions(viewModel::requestTransaction, viewModel::setVersionProtected), onClean, onUninstall)
            }
        }
    }
}

internal fun ZephyrUiState.Ready.catalogFreshnessDescription(): String =
    catalogCachedAtEpochMillis
        ?.takeIf { catalogIsCached }
        ?.let { "Cached metadata (${candidateCacheAgeLabel(it, currentEpochMillis())}). Refresh to check upstream." }
        ?: if (isCatalogLoading) "Refreshing SDKMAN metadata." else "Live SDKMAN metadata."
