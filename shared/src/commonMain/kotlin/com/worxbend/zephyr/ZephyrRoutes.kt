package com.worxbend.zephyr

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.worxbend.zephyr.data.createClipboardService
import com.worxbend.zephyr.data.createTerminalLauncher
import com.worxbend.zephyr.data.currentEpochMillis
import com.worxbend.zephyr.domain.Candidate
import com.worxbend.zephyr.domain.CandidateCatalogItem
import com.worxbend.zephyr.domain.CandidateKind
import com.worxbend.zephyr.domain.CandidateVersion
import com.worxbend.zephyr.domain.JavaVersion
import com.worxbend.zephyr.domain.JDK_VENDOR_KNOWLEDGE_VERSION
import com.worxbend.zephyr.domain.ProtectedVersion
import com.worxbend.zephyr.domain.SdkmanTransaction
import com.worxbend.zephyr.domain.StorageCleanupDisposition
import com.worxbend.zephyr.domain.StorageMeasurement
import com.worxbend.zephyr.domain.VersionStorage
import com.worxbend.zephyr.domain.formatByteSize
import com.worxbend.zephyr.domain.displayNameFor
import com.worxbend.zephyr.domain.jdkVendorKnowledge
import com.worxbend.zephyr.domain.toJavaVersion
import com.worxbend.zephyr.settings.AppSettings
import com.worxbend.zephyr.settings.CleanupGracePeriod
import com.worxbend.zephyr.settings.CollectionViewMode
import com.worxbend.zephyr.settings.SavedJdkFilter
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
                ZephyrRoute.ProjectWorkspaces -> ProjectWorkspacesScreen(state, viewModel, settings, onSettingsChange)
                ZephyrRoute.ProjectImport -> ProjectToolchainImportScreen(state)
                ZephyrRoute.ProjectExport -> ProjectToolchainExportScreen(state, viewModel)
                ZephyrRoute.EnvironmentSnapshot -> EnvironmentSnapshotScreen(state, viewModel, onSettingsChange)
                ZephyrRoute.Comparison -> CandidateComparisonScreen(state, viewModel)
                ZephyrRoute.Diagnostics -> DiagnosticsScreen(
                    state,
                    viewModel::refreshConnectivity,
                    viewModel::refreshIntegrity,
                    viewModel::exportDiagnostics,
                )
                ZephyrRoute.History -> OperationHistoryScreen(state, viewModel)
                ZephyrRoute.Settings -> SettingsScreen(settings, onSettingsChange)
                ZephyrRoute.About -> AboutScreen(state)
                is ZephyrRoute.JdkDetail -> CandidateDetailScreen(state, route.candidate, true, viewModel, onClean, onUninstall)
                is ZephyrRoute.SdkDetail -> CandidateDetailScreen(state, route.candidate, false, viewModel, onClean, onUninstall)
            }
        }
    }
}

private fun ZephyrUiState.Ready.catalogFreshnessDescription(): String =
    catalogCachedAtEpochMillis
        ?.takeIf { catalogIsCached }
        ?.let { "Cached metadata (${candidateCacheAgeLabel(it, currentEpochMillis())}). Refresh to check upstream." }
        ?: if (isCatalogLoading) "Refreshing SDKMAN metadata." else "Live SDKMAN metadata."

@Composable
private fun InstalledJdkScreen(
    state: ZephyrUiState.Ready,
    onNavigate: (ZephyrRoute) -> Unit,
    onProtectionChange: (String, String, Boolean) -> Unit,
    onClean: (String, List<String>) -> Unit,
) {
    val jdk = state.candidates.firstOrNull { it.name == "java" }
    var query by remember { mutableStateOf("") }
    var grouping by remember { mutableStateOf(JavaVersionGrouping.None) }
    var terminalMessage by remember { mutableStateOf<String?>(null) }
    val terminalLauncher = remember { createTerminalLauncher() }
    val installed = jdk?.installedVersions.orEmpty()
        .asSequence()
        .filter { it.isInstalled }
        .map { it.toJavaVersion() }
        .toList()
    val filtered = installed.filterByQuery(query)
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        PageTitle("Installed JDK", "${installed.size} local Java version(s) managed by SDKMAN.")
        terminalMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchField(query, { query = it }, "Search JDKs", Modifier.width(280.dp))
            ZephyrSegmentedControl(
                options = JavaVersionGrouping.entries,
                selected = grouping,
                label = JavaVersionGrouping::label,
                onSelected = { grouping = it },
            )
            Text(
                "${filtered.size} shown",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (installed.isEmpty()) {
            EmptyState("No JDK Installed", "Open Browse JDKs to install a Java version.", "Browse JDKs") {
                onNavigate(ZephyrRoute.BrowseJdks)
            }
            return@Column
        }
        if (filtered.isEmpty()) {
            EmptyState("No matching JDKs", "No installed Java versions match \"$query\".", "Clear search") { query = "" }
            return@Column
        }
        val groups = filtered.groupBy(grouping)
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            groups.forEach { (title, groupVersions) ->
                if (title.isNotBlank()) item { ZephyrSectionHeading(title) }
                items(groupVersions) { version ->
                    val protected = ProtectedVersion("java", version.identifier) in state.protectedVersions
                    JdkVersionCard(
                        version = version,
                        default = jdk?.defaultVersion,
                        isProtected = protected,
                        onToggleProtected = { onProtectionChange("java", version.identifier, !protected) },
                        onClean = { onClean("java", listOf(version.identifier)) },
                        onOpenTerminal = state.sdkmanStatus.home?.takeIf(String::isNotBlank)?.let { sdkmanHome ->
                            {
                                terminalMessage = terminalLauncher
                                    .launch(sdkmanHome, "java", version.identifier)
                                    .message
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun InstalledSdksScreen(
    state: ZephyrUiState.Ready,
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onNavigate: (ZephyrRoute) -> Unit,
    onClean: (String, List<String>) -> Unit,
) {
    val sdks = state.candidates.filter { it.kind == CandidateKind.Sdk }
    var query by remember { mutableStateOf("") }
    val filtered = sdks.filter { candidate ->
        query.isBlank() ||
            candidate.displayName.contains(query, ignoreCase = true) ||
            candidate.name.contains(query, ignoreCase = true) ||
            candidate.defaultVersion.orEmpty().contains(query, ignoreCase = true)
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        PageTitle("Installed SDKs", "${sdks.size} package(s) currently present in your SDKMAN candidates directory.")
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            SearchField(query, { query = it }, "Search installed SDKs", Modifier.width(300.dp))
            ZephyrSegmentedControl(
                options = CollectionViewMode.entries,
                selected = settings.installedViewMode,
                label = CollectionViewMode::label,
                onSelected = { mode ->
                    onSettingsChange { it.copy(installedViewMode = mode) }
                },
            )
            Text(
                "${filtered.size} shown",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (sdks.isEmpty()) {
            EmptyState("No SDKs Installed", "Open Browse SDKs to install a package.", "Browse SDKs") {
                onNavigate(ZephyrRoute.BrowseSdks)
            }
        } else if (filtered.isEmpty()) {
            EmptyState("No matching SDKs", "No installed packages match \"$query\".", "Clear search") { query = "" }
        } else {
            if (settings.installedViewMode == CollectionViewMode.Cards) {
                CandidateGrid(
                    candidates = filtered,
                    protectedVersions = state.protectedVersions,
                    onOpen = { onNavigate(ZephyrRoute.SdkDetail(it.name)) },
                    onClean = onClean,
                )
            } else {
                CandidateTable(
                    candidates = filtered,
                    protectedVersions = state.protectedVersions,
                    onOpen = { onNavigate(ZephyrRoute.SdkDetail(it.name)) },
                    onClean = onClean,
                )
            }
        }
    }
}

@Composable
private fun BrowseScreen(
    title: String,
    subtitle: String,
    items: List<CandidateCatalogItem>,
    loading: Boolean,
    favoriteCandidates: Set<String>,
    viewMode: CollectionViewMode,
    onViewModeChange: (CollectionViewMode) -> Unit,
    onFavoriteChange: (String, Boolean) -> Unit,
    onOpen: (CandidateCatalogItem) -> Unit,
    onRefresh: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var catalogFilter by remember { mutableStateOf(CatalogFilter.All) }
    val filtered = items
        .filter { item ->
            val matchesQuery = query.isBlank() ||
                item.displayName.contains(query, ignoreCase = true) ||
                item.name.contains(query, ignoreCase = true) ||
                item.description.orEmpty().contains(query, ignoreCase = true)
            val matchesFilter = when (catalogFilter) {
                CatalogFilter.All -> true
                CatalogFilter.Installed -> item.isInstalled
                CatalogFilter.Available -> !item.isInstalled
            }
            matchesQuery && matchesFilter
        }
        .sortedWith(
            compareByDescending<CandidateCatalogItem> { it.name in favoriteCandidates }
                .thenBy { it.displayName.lowercase() },
        )
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        PageTitle(title, "$subtitle Explore ${items.size} SDKMAN package(s), then inspect available versions.")
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SearchField(query, { query = it }, "Search SDKs", Modifier.width(300.dp))
            ZephyrSegmentedControl(
                options = CatalogFilter.entries,
                selected = catalogFilter,
                label = CatalogFilter::label,
                onSelected = { catalogFilter = it },
            )
            ZephyrSegmentedControl(
                options = CollectionViewMode.entries,
                selected = viewMode,
                label = CollectionViewMode::label,
                onSelected = onViewModeChange,
            )
            Text(
                "${filtered.size} shown",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (loading) ZephyrProgressIndicator()
        if (!loading && filtered.isEmpty()) {
            val hasActiveFilters = query.isNotBlank() || catalogFilter != CatalogFilter.All
            EmptyState(
                if (hasActiveFilters) "No matching SDKs" else "SDK catalog unavailable",
                if (hasActiveFilters) {
                    "No SDKMAN packages match the active search and status filters."
                } else {
                    "Refresh SDKMAN metadata to load packages available for installation."
                },
                if (hasActiveFilters) "Clear filters" else "Refresh metadata",
            ) {
                if (hasActiveFilters) {
                    query = ""
                    catalogFilter = CatalogFilter.All
                } else {
                    onRefresh()
                }
            }
        } else if (!loading) {
            if (viewMode == CollectionViewMode.Cards) {
                val spacing = LocalZephyrMetrics.current.spacing
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(280.dp * zephyrContentScale()),
                    verticalArrangement = Arrangement.spacedBy(spacing),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    items(filtered, key = { it.name }) { item ->
                        val favorite = item.name in favoriteCandidates
                        PackageCard(
                            item = item,
                            isFavorite = favorite,
                            onToggleFavorite = { onFavoriteChange(item.name, !favorite) },
                            onClick = { onOpen(item) },
                        )
                    }
                }
            } else {
                PackageTable(
                    packages = filtered,
                    favoriteCandidates = favoriteCandidates,
                    onFavoriteChange = onFavoriteChange,
                    onOpen = onOpen,
                )
            }
        }
    }
}

@Composable
private fun BrowseScreen(
    state: ZephyrUiState.Ready,
    viewModel: ZephyrViewModel,
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    onClean: (String, List<String>) -> Unit,
    onUninstall: (String, String) -> Unit,
) {
    val jdkPackage = state.selectedCandidate ?: state.candidates.firstOrNull { it.name == "java" }
    var query by remember { mutableStateOf("") }
    var grouping by remember { mutableStateOf(JavaVersionGrouping.FeatureVersion) }
    var statusFilter by remember { mutableStateOf(JavaVersionStatusFilter.All) }
    var providerFilter by remember { mutableStateOf<String?>(null) }
    var versionSort by remember { mutableStateOf(JavaVersionSort.Catalog) }
    var providerMenuOpen by remember { mutableStateOf(false) }
    var savedFilterName by remember { mutableStateOf("") }
    var collapsedGroups by remember(grouping, query, statusFilter, providerFilter, versionSort) {
        mutableStateOf(emptySet<String>())
    }
    val allJavaVersions = jdkPackage?.installedVersions.orEmpty().map { it.toJavaVersion() }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        // Expanded filters remain reachable without pushing the version list out of the window.
        val filterMaxHeight = maxHeight * 0.5f
        Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier.fillMaxWidth().heightIn(max = filterMaxHeight).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
            PageTitle(
                "Browse JDKs",
                "${state.catalogFreshnessDescription()} ${jdkPackage?.installedVersions?.size ?: 0} version(s) loaded.",
            )
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                SearchField(query, { query = it }, "Search JDKs", Modifier.width(280.dp))
                ZephyrSegmentedControl(
                    options = JavaVersionGrouping.entries,
                    selected = grouping,
                    label = JavaVersionGrouping::label,
                    onSelected = { grouping = it },
                )
                ZephyrToolbarButton(
                    label = "Favorite vendors",
                    detail = settings.favoriteJdkVendors.size.takeIf { it > 0 }?.toString(),
                    onClick = { grouping = JavaVersionGrouping.Provider },
                )
            }
            val providers = allJavaVersions
                .mapNotNull { version ->
                    version.providerCode?.let { code -> code to (version.providerName ?: code) }
                }
                .distinctBy { it.first }
                .sortedBy { it.second }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ZephyrSegmentedControl(
                    options = JavaVersionStatusFilter.entries,
                    selected = statusFilter,
                    label = JavaVersionStatusFilter::label,
                    onSelected = { statusFilter = it },
                )
                Box {
                    ZephyrToolbarButton(
                        label = "Vendor",
                        detail = providerFilter?.let { selected ->
                            providers.firstOrNull { it.first == selected }?.second ?: selected
                        } ?: "all",
                        onClick = { providerMenuOpen = true },
                    )
                    DropdownMenu(
                        expanded = providerMenuOpen,
                        onDismissRequest = { providerMenuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("All vendors") },
                            onClick = {
                                providerFilter = null
                                providerMenuOpen = false
                            },
                        )
                        providers.forEach { (code, name) ->
                            DropdownMenuItem(
                                text = { Text(name) },
                                onClick = {
                                    providerFilter = code
                                    providerMenuOpen = false
                                },
                            )
                        }
                    }
                }
                ZephyrSegmentedControl(
                    options = JavaVersionSort.entries,
                    selected = versionSort,
                    label = JavaVersionSort::label,
                    onSelected = { versionSort = it },
                )
                Text(
                    "${allJavaVersions.filterAndSort(query, statusFilter, providerFilter, versionSort).size} shown",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val filtersActive = query.isNotBlank() ||
                statusFilter != JavaVersionStatusFilter.All ||
                providerFilter != null ||
                versionSort != JavaVersionSort.Catalog
            if (filtersActive) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    if (query.isNotBlank()) Badge("Search: $query")
                    if (statusFilter != JavaVersionStatusFilter.All) Badge(statusFilter.label, BadgeTone.Primary)
                    providerFilter?.let { code ->
                        Badge(
                            "Vendor: ${providers.firstOrNull { it.first == code }?.second ?: code}",
                            BadgeTone.Primary,
                        )
                    }
                    if (versionSort != JavaVersionSort.Catalog) Badge("Sort: ${versionSort.label}")
                    ZephyrToolbarButton(
                        label = "Clear filters",
                        onClick = {
                            query = ""
                            statusFilter = JavaVersionStatusFilter.All
                            providerFilter = null
                            versionSort = JavaVersionSort.Catalog
                        },
                    )
                    OutlinedTextField(
                        value = savedFilterName,
                        onValueChange = { savedFilterName = it.take(40) },
                        modifier = Modifier.width(180.dp),
                        singleLine = true,
                        label = { Text("Filter name") },
                    )
                    ZephyrToolbarButton(
                        label = "Save filter",
                        onClick = {
                            val saved = SavedJdkFilter(
                                name = savedFilterName.trim(),
                                query = query,
                                status = statusFilter.name,
                                providerCode = providerFilter,
                                sort = versionSort.name,
                            )
                            onSettingsChange {
                                it.copy(
                                    savedJdkFilters = (
                                        it.savedJdkFilters.filterNot { existing ->
                                            existing.name.equals(saved.name, ignoreCase = true)
                                        } + saved
                                        ).sortedBy { filter -> filter.name.lowercase() },
                                )
                            }
                            savedFilterName = ""
                        },
                        enabled = savedFilterName.isNotBlank(),
                    )
                }
            }
            providerFilter?.let(::jdkVendorKnowledge)?.let { knowledge ->
                ZephyrPanel(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(LocalZephyrMetrics.current.panelPadding),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column {
                                Text(knowledge.displayName, fontWeight = FontWeight.SemiBold)
                                Text(
                                    "Maintained by ${knowledge.maintainer}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Badge("Knowledge $JDK_VENDOR_KNOWLEDGE_VERSION", BadgeTone.Primary)
                        }
                        Text(knowledge.summary, style = MaterialTheme.typography.bodySmall)
                        Text(
                            knowledge.supportCharacteristics,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            "Source: ${knowledge.sourceUrl}",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.tertiary,
                        )
                    }
                }
            }
            if (settings.savedJdkFilters.isNotEmpty()) {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        "Saved:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    settings.savedJdkFilters.forEach { saved ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            ZephyrToolbarButton(
                                label = saved.name,
                                onClick = {
                                    query = saved.query
                                    statusFilter = JavaVersionStatusFilter.entries
                                        .firstOrNull { it.name == saved.status }
                                        ?: JavaVersionStatusFilter.All
                                    providerFilter = saved.providerCode
                                    versionSort = JavaVersionSort.entries
                                        .firstOrNull { it.name == saved.sort }
                                        ?: JavaVersionSort.Catalog
                                },
                            )
                            TextButton(
                                onClick = {
                                    onSettingsChange {
                                        it.copy(savedJdkFilters = it.savedJdkFilters - saved)
                                    }
                                },
                            ) {
                                Text("×")
                            }
                        }
                    }
                }
            }

            }
            if (state.detailLoadingCandidate == "java" && jdkPackage == null) {
                ZephyrProgressIndicator()
                return@Column
            }
            if (jdkPackage == null) {
                EmptyState(
                    "JDK versions unavailable",
                    "Refresh SDKMAN metadata to load Java distributions and versions.",
                    "Refresh metadata",
                ) {
                    viewModel.requestTransaction(SdkmanTransaction.RefreshMetadata)
                }
                return@Column
            }

            val filteredVersions = allJavaVersions.filterAndSort(
                query = query,
                status = statusFilter,
                providerCode = providerFilter,
                sort = versionSort,
            )
            if (filteredVersions.isEmpty()) {
                EmptyState(
                    "No matching JDKs",
                    "No Java versions match the active search, status, and vendor filters.",
                    "Clear filters",
                ) {
                    query = ""
                    statusFilter = JavaVersionStatusFilter.All
                    providerFilter = null
                    versionSort = JavaVersionSort.Catalog
                }
                return@Column
            }
            val groups = filteredVersions.groupBy(grouping)
            val orderedGroups = if (grouping == JavaVersionGrouping.Provider) {
                groups.entries.sortedWith(
                    compareByDescending<Map.Entry<String, List<JavaVersion>>> { entry ->
                        entry.value.firstOrNull()?.providerCode in settings.favoriteJdkVendors
                    }.thenBy { it.key },
                )
            } else {
                groups.entries.toList()
            }
            val updateTargets = remember(jdkPackage.installedVersions) { jdkPackage.installedVersions.updateTargets() }

            val listState = rememberLazyListState()
            Box(Modifier.fillMaxSize()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize().padding(end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    orderedGroups.forEach { (title, groupVersions) ->
                        if (title.isNotBlank()) {
                            item {
                                val providerCode = groupVersions.firstOrNull()?.providerCode
                                    .takeIf { grouping == JavaVersionGrouping.Provider }
                                val favorite = providerCode in settings.favoriteJdkVendors
                                AccordionHeader(
                                    title = title,
                                    count = groupVersions.size,
                                    collapsed = title in collapsedGroups,
                                    onClick = {
                                        collapsedGroups = if (title in collapsedGroups) {
                                            collapsedGroups - title
                                        } else {
                                            collapsedGroups + title
                                        }
                                    },
                                    actionLabel = providerCode?.let {
                                        if (favorite) "★ Favorite" else "☆ Favorite"
                                    },
                                    onAction = providerCode?.let { code ->
                                        {
                                            onSettingsChange {
                                                it.copy(
                                                    favoriteJdkVendors = it.favoriteJdkVendors.updated(code, !favorite),
                                                )
                                            }
                                        }
                                    },
                                )
                            }
                        }
                        if (title.isBlank() || title !in collapsedGroups) {
                            items(groupVersions, key = { it.identifier }) { java ->
                                VersionRow(
                                    candidateName = "java",
                                    version = CandidateVersion(
                                        java.identifier,
                                        java.isInstalled,
                                        java.isDefault,
                                        java.remoteAvailability,
                                    ),
                                    updateTargets = updateTargets,
                                    viewModel = viewModel,
                                    sdkmanHome = state.sdkmanStatus.home,
                                    isProtected = ProtectedVersion("java", java.identifier) in state.protectedVersions,
                                    onClean = onClean,
                                    onUninstall = onUninstall,
                                )
                            }
                        }
                    }
                }
                VerticalScrollbar(
                    adapter = rememberScrollbarAdapter(listState),
                    modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                )
            }
    }
    }
}

private fun Set<String>.updated(value: String, included: Boolean): Set<String> =
    if (included) this + value else this - value

@Composable
private fun StorageCenterScreen(
    state: ZephyrUiState.Ready,
    viewModel: ZephyrViewModel,
) {
    val inventory = state.storageInventory
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        PageTitle(
            "Storage Center",
            "Measure installed SDKMAN payloads and route every cleanup through a reviewed transaction.",
        )
        ZephyrPanel(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(LocalZephyrMetrics.current.panelPadding),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                ZephyrRecordLayout(
                    modifier = Modifier.fillMaxWidth(),
                    content = {
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(
                                inventory?.total?.let(::storageTotalLabel) ?: "Storage has not been measured",
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                "Symbolic links, unreadable entries, scan limits, and concurrent changes are reported as unknown—never guessed.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    },
                ) {
                    ZephyrToolbarButton(
                        if (state.storageScanInProgress) "Measuring…" else "Measure again",
                        onClick = viewModel::refreshStorage,
                        enabled = !state.storageScanInProgress,
                    )
                }
                inventory?.let { measured ->
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Badge("${measured.versions.size} installed versions")
                        measured.availableBytes?.let { Badge("${formatByteSize(it)} available", BadgeTone.Success) }
                        measured.candidates.forEach { candidate ->
                            Badge(
                                "${candidate.displayName}: ${storageTotalLabel(candidate.total)}",
                                if (candidate.total.isExact) BadgeTone.Neutral else BadgeTone.Warning,
                            )
                        }
                    }
                }
            }
        }
        when {
            state.storageScanInProgress && inventory == null -> ZephyrProgressIndicator()
            inventory == null -> EmptyState(
                "Storage not measured",
                "Run a safe filesystem scan to calculate logical payload sizes.",
                "Measure storage",
                viewModel::refreshStorage,
            )
            inventory.versions.isEmpty() -> EmptyState(
                "No installed payloads",
                "SDKMAN has no installed candidate versions to measure.",
            )
            else -> {
                val listState = rememberLazyListState()
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(
                            items = inventory.versions,
                            key = { "${it.candidate}:${it.version}" },
                        ) { entry ->
                            StorageVersionRow(
                                entry = entry,
                                onReviewCleanup = {
                                    viewModel.requestTransaction(
                                        if (entry.cleanupDisposition == StorageCleanupDisposition.VerifiedLocalOnly) {
                                            SdkmanTransaction.CleanLocalOnly(entry.candidate, listOf(entry.version))
                                        } else {
                                            SdkmanTransaction.Uninstall(entry.candidate, entry.version)
                                        },
                                    )
                                },
                            )
                        }
                    }
                    VerticalScrollbar(
                        adapter = rememberScrollbarAdapter(listState),
                        modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
                    )
                }
            }
        }
    }
}

private fun storageTotalLabel(total: com.worxbend.zephyr.domain.StorageTotal): String =
    buildString {
        append(formatByteSize(total.knownBytes))
        if (!total.isExact) append(" known + ${total.unknownEntries} unknown")
    }

@Composable
private fun StorageVersionRow(
    entry: VersionStorage,
    onReviewCleanup: () -> Unit,
) {
    ZephyrPanel(Modifier.fillMaxWidth()) {
        ZephyrRecordLayout(
            modifier = Modifier.fillMaxWidth().padding(LocalZephyrMetrics.current.panelPadding),
            content = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "${entry.candidateDisplayName} ${entry.version}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        when (val measurement = entry.measurement) {
                            is StorageMeasurement.Exact -> "${formatByteSize(measurement.bytes)} logical payload"
                            is StorageMeasurement.Unknown -> "Unknown · ${measurement.reason.label}"
                        },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        entry.bytes?.let { Badge(formatByteSize(it), BadgeTone.Primary) }
                        Badge(
                            entry.cleanupDisposition.label,
                            when (entry.cleanupDisposition) {
                                StorageCleanupDisposition.VerifiedLocalOnly -> BadgeTone.Warning
                                StorageCleanupDisposition.OptionalNonDefault -> BadgeTone.Neutral
                                StorageCleanupDisposition.BlockedDefault -> BadgeTone.Primary
                                StorageCleanupDisposition.BlockedProtected -> BadgeTone.Success
                            },
                        )
                        Badge(entry.remoteAvailability.label)
                    }
                }
            },
        ) {
            if (entry.cleanupDisposition.eligible) {
                OutlinedButton(onClick = onReviewCleanup) {
                    Text("Review cleanup")
                }
            }
        }
    }
}

@Composable
private fun LocalOnlyScreen(
    state: ZephyrUiState.Ready,
    cleanupGracePeriod: CleanupGracePeriod,
    reviewDueVersions: Set<ProtectedVersion>,
    onNavigate: (ZephyrRoute) -> Unit,
    onScan: () -> Unit,
    onRetryFailed: () -> Unit,
    onClean: (String, List<String>) -> Unit,
) {
    val progress = state.localOnlyScanProgress
    val items = state.candidates.filter { it.hasLocalOnlyVersions }
    val versionCount = items.sumOf { it.localOnlyVersionCount }
    val reviewDueCount = reviewDueVersions.count { target ->
        items.any { it.name == target.candidate && target.version in it.localOnlyVersions }
    }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        PageTitle(
            "Local-Only Versions",
            "$versionCount installed version(s) across ${items.size} package(s) are no longer listed remotely.",
        )
        ZephyrPanel(Modifier.fillMaxWidth()) {
            ZephyrRecordLayout(
                modifier = Modifier.fillMaxWidth().padding(LocalZephyrMetrics.current.panelPadding),
                content = {
                    Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusDot(if (versionCount == 0) StatusTone.Success else StatusTone.Warning)
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (versionCount == 0) "Environment is clean" else "Review before removing",
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            when {
                                reviewDueCount > 0 ->
                                    "$reviewDueCount version(s) passed the ${cleanupGracePeriod.label} grace period. Review is still required."
                                cleanupGracePeriod != CleanupGracePeriod.Off ->
                                    "The ${cleanupGracePeriod.label} grace policy is active; cleanup always requires review."
                                else ->
                                    "Zephyr re-verifies every selected version against remote metadata before cleanup."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    }
                },
            ) {
                ZephyrToolbarButton("Scan again", onClick = onScan)
            }
        }
        progress?.let { scan ->
            val progressDescription = buildString {
                append("Local-only audit ${scan.completed} of ${scan.total} completed.")
                if (scan.activeCandidates.isNotEmpty()) {
                    append(" Active: ${scan.activeCandidates.joinToString()}.")
                }
                append(" ${scan.trustedFindings.sumOf { it.localOnlyVersionCount }} trusted partial findings.")
                append(" ${scan.failures.size} failures.")
            }
            ZephyrPanel(
                Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = progressDescription
                        liveRegion = LiveRegionMode.Polite
                    },
            ) {
                Column(
                    Modifier.fillMaxWidth().padding(LocalZephyrMetrics.current.panelPadding),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    FlowRow(
                        Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (scan.running) ZephyrProgressIndicator(compact = true)
                        Text(
                            "${scan.completed}/${scan.total} candidate reads completed",
                            fontWeight = FontWeight.SemiBold,
                        )
                        if (scan.activeCandidates.isNotEmpty()) {
                            Text(
                                "Active: ${scan.activeCandidates.joinToString()}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Text(
                        "${scan.trustedFindings.sumOf { it.localOnlyVersionCount }} trusted local-only finding(s) published so far.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (scan.failures.isNotEmpty()) {
                        Text(
                            scan.failures.joinToString(prefix = "Failed reads: ", separator = "; ") {
                                "${it.candidate}: ${it.message}"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                        if (!scan.running) {
                            OutlinedButton(onClick = onRetryFailed) {
                                Text("Retry failed reads")
                            }
                        }
                    }
                }
            }
        }
        if (items.isEmpty()) {
            EmptyState("No Local-Only Versions", "Run Scan whenever SDKMAN metadata changes.", "Run scan", onScan)
        } else {
            CandidateGrid(
                candidates = items,
                protectedVersions = state.protectedVersions,
                reviewDueVersions = reviewDueVersions,
                cleanupEligibleCandidates = progress
                    ?.trustedFindings
                    .orEmpty()
                    .mapTo(mutableSetOf(), Candidate::name),
                onOpen = { candidate ->
                    onNavigate(if (candidate.kind == CandidateKind.Jdk) ZephyrRoute.JdkDetail(candidate.name) else ZephyrRoute.SdkDetail(candidate.name))
                },
                onClean = onClean,
            )
        }
    }
}

@Composable
private fun CandidateDetailScreen(
    state: ZephyrUiState.Ready,
    candidateName: String,
    jdk: Boolean,
    viewModel: ZephyrViewModel,
    onClean: (String, List<String>) -> Unit,
    onUninstall: (String, String) -> Unit,
) {
    val candidate = state.selectedCandidate ?: state.candidates.firstOrNull { it.name == candidateName }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.fillMaxSize()) {
        PageTitle(candidate?.displayName ?: displayNameFor(candidateName), "Inspect versions and manage the SDKMAN candidate \"$candidateName\".")
        ZephyrPanel(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(LocalZephyrMetrics.current.panelPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                candidate?.description?.let { LinkText(it) }
                candidate?.websiteUrl?.let { LinkText(it) }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Badge("SDKMAN key: $candidateName")
                    CopyTextButton(candidateName, "Copy key")
                    candidate?.defaultVersion?.let { Badge("Default: $it", BadgeTone.Primary) }
                    candidate?.installedVersions?.count { it.isInstalled }?.let { Badge("$it installed", BadgeTone.Success) }
                    candidate?.let {
                        Badge(
                            it.remoteEvidence.label,
                            if (it.remoteEvidence == com.worxbend.zephyr.domain.RemoteEvidenceState.LiveComplete) {
                                BadgeTone.Success
                            } else {
                                BadgeTone.Warning
                            },
                        )
                    }
                    if (candidate?.hasLocalOnlyVersions == true) {
                        Badge("${candidate.localOnlyVersionCount} local-only", BadgeTone.Warning)
                    }
                }
            }
        }
        if (candidate == null || state.detailLoadingCandidate == candidateName && state.selectedCandidate == null) {
            ZephyrProgressIndicator()
        } else if (jdk) {
            JdkDetailVersions(
                candidate,
                state.protectedVersions,
                state.sdkmanStatus.home,
                viewModel,
                onClean,
                onUninstall,
            )
        } else {
            VersionList(
                candidate,
                state.protectedVersions,
                state.sdkmanStatus.home,
                viewModel,
                onClean,
                onUninstall,
            )
        }
    }
}

@Composable
private fun JdkDetailVersions(
    candidate: Candidate,
    protectedVersions: Set<ProtectedVersion>,
    sdkmanHome: String?,
    viewModel: ZephyrViewModel,
    onClean: (String, List<String>) -> Unit,
    onUninstall: (String, String) -> Unit,
) {
    var grouping by remember { mutableStateOf(JavaVersionGrouping.FeatureVersion) }
    var query by remember { mutableStateOf("") }
    val groups = candidate.installedVersions
        .map { it.toJavaVersion() }
        .filterByQuery(query)
        .groupBy(grouping)
    val updateTargets = remember(candidate.installedVersions) { candidate.installedVersions.updateTargets() }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (candidate.installedVersions.size > 3) {
            SearchField(query, { query = it }, "Search versions", Modifier.width(280.dp))
        }
        ZephyrSegmentedControl(
            options = listOf(JavaVersionGrouping.FeatureVersion, JavaVersionGrouping.Provider),
            selected = grouping,
            label = JavaVersionGrouping::label,
            onSelected = { grouping = it },
        )
    }
    if (groups.isEmpty()) {
        val filteredOut = query.isNotBlank()
        EmptyState(
            if (filteredOut) "No matching versions" else "No versions available",
            if (filteredOut) "No JDK versions match \"$query\"." else "Refresh SDKMAN metadata to load JDK versions.",
            if (filteredOut) "Clear search" else "Refresh metadata",
            if (filteredOut) ({ query = "" }) else ({
                viewModel.requestTransaction(SdkmanTransaction.RefreshMetadata)
            }),
        )
        return
    }
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            groups.forEach { (title, group) ->
                item { ZephyrSectionHeading(title) }
                items(group) { java ->
                    VersionRow(
                        candidateName = candidate.name,
                        version = CandidateVersion(
                            java.identifier,
                            java.isInstalled,
                            java.isDefault,
                            java.remoteAvailability,
                        ),
                        updateTargets = updateTargets,
                        viewModel = viewModel,
                        sdkmanHome = sdkmanHome,
                        isProtected = ProtectedVersion(candidate.name, java.identifier) in protectedVersions,
                        onClean = onClean,
                        onUninstall = onUninstall,
                    )
                }
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
private fun VersionList(
    candidate: Candidate,
    protectedVersions: Set<ProtectedVersion>,
    sdkmanHome: String?,
    viewModel: ZephyrViewModel,
    onClean: (String, List<String>) -> Unit,
    onUninstall: (String, String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val versions = candidate.installedVersions.filter { version ->
        query.isBlank() || version.version.contains(query, ignoreCase = true) || statusText(version).contains(query, ignoreCase = true)
    }
    val updateTargets = remember(candidate.installedVersions) { candidate.installedVersions.updateTargets() }
    if (candidate.installedVersions.size > 3) {
        SearchField(query, { query = it }, "Search versions", Modifier.width(280.dp))
    }
    if (versions.isEmpty()) {
        val filteredOut = query.isNotBlank()
        EmptyState(
            if (filteredOut) "No matching versions" else "No versions available",
            if (filteredOut) "No versions match \"$query\"." else "Refresh SDKMAN metadata to load candidate versions.",
            if (filteredOut) "Clear search" else "Refresh metadata",
            if (filteredOut) ({ query = "" }) else ({
                viewModel.requestTransaction(SdkmanTransaction.RefreshMetadata)
            }),
        )
        return
    }
    val listState = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize().padding(end = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(versions, key = { it.version }) { version ->
                VersionRow(
                    candidateName = candidate.name,
                    version = version,
                    updateTargets = updateTargets,
                    viewModel = viewModel,
                    sdkmanHome = sdkmanHome,
                    isProtected = ProtectedVersion(candidate.name, version.version) in protectedVersions,
                    onClean = onClean,
                    onUninstall = onUninstall,
                )
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

private enum class CatalogFilter(val label: String) {
    All("All"),
    Installed("Installed"),
    Available("Available"),
}

@Composable
private fun VersionRow(
    candidateName: String,
    version: CandidateVersion,
    updateTargets: List<CandidateVersion>,
    viewModel: ZephyrViewModel,
    sdkmanHome: String?,
    isProtected: Boolean,
    onClean: (String, List<String>) -> Unit,
    onUninstall: (String, String) -> Unit,
) {
    var updateMenuOpen by remember { mutableStateOf(false) }
    var terminalMessage by remember(candidateName, version.version) { mutableStateOf<String?>(null) }
    val clipboard = remember { createClipboardService() }
    val terminalLauncher = remember { createTerminalLauncher() }
    val openTerminal = {
        terminalMessage = if (sdkmanHome.isNullOrBlank()) {
            "SDKMAN home is unavailable."
        } else {
            terminalLauncher.launch(sdkmanHome, candidateName, version.version).message
        }
    }
    ContextActionArea(
        actions = buildList {
            add(ContextAction("Copy version") { clipboard.copy(version.version) })
            if (!version.isInstalled && version.isRemoteAvailable) {
                add(ContextAction("Install") {
                    viewModel.requestTransaction(SdkmanTransaction.Install(candidateName, version.version))
                })
            }
            if (version.isInstalled && !version.isDefault) {
                add(ContextAction("Make default") {
                    viewModel.requestTransaction(SdkmanTransaction.SetDefault(candidateName, version.version))
                })
                if (!version.isConfirmedLocalOnly && !isProtected) {
                    add(ContextAction("Uninstall") { onUninstall(candidateName, version.version) })
                }
            }
            if (version.isInstalled) {
                add(
                    ContextAction(
                        label = "Open activated terminal",
                        enabled = !sdkmanHome.isNullOrBlank(),
                        onClick = openTerminal,
                    ),
                )
                add(ContextAction(if (isProtected) "Unpin" else "Protect") {
                    viewModel.setVersionProtected(candidateName, version.version, !isProtected)
                })
            }
            if (version.isInstalled && version.isConfirmedLocalOnly) {
                updateTargets.forEach { target ->
                    add(ContextAction("Install update ${target.version}") {
                        viewModel.requestTransaction(SdkmanTransaction.Install(candidateName, target.version))
                    })
                }
            }
            if (version.isInstalled && version.isConfirmedLocalOnly && !version.isDefault && !isProtected) {
                add(ContextAction("Clean") { onClean(candidateName, listOf(version.version)) })
            }
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        ZephyrPanel(Modifier.fillMaxWidth()) {
            ZephyrRecordLayout(
                modifier = Modifier.fillMaxWidth().padding(LocalZephyrMetrics.current.panelPadding),
                content = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(version.version, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            if (version.isDefault) Badge("Default", BadgeTone.Primary)
                            if (version.isInstalled) Badge("Installed", BadgeTone.Neutral)
                            when {
                                version.isRemoteAvailable -> Badge("Available", BadgeTone.Success)
                                version.isConfirmedLocalOnly -> Badge("Local only", BadgeTone.Warning)
                                else -> Badge("Availability unknown", BadgeTone.Neutral)
                            }
                            if (isProtected) Badge("Protected", BadgeTone.Primary)
                        }
                        terminalMessage?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
            ) {
                CopyTextButton(version.version, "Copy version")
                if (version.isInstalled) {
                    TextButton(
                        onClick = openTerminal,
                        enabled = !sdkmanHome.isNullOrBlank(),
                        modifier = Modifier.heightIn(min = LocalZephyrMetrics.current.controlHeight),
                    ) {
                        Text("Terminal")
                    }
                }
                if (!version.isInstalled && version.isRemoteAvailable) {
                    FilledTonalButton(
                        onClick = { viewModel.requestTransaction(SdkmanTransaction.Install(candidateName, version.version)) },
                        modifier = Modifier.heightIn(min = LocalZephyrMetrics.current.controlHeight),
                    ) {
                        Text("Install")
                    }
                }
                if (version.isInstalled && !version.isDefault) {
                    FilledTonalButton(
                        onClick = { viewModel.requestTransaction(SdkmanTransaction.SetDefault(candidateName, version.version)) },
                        modifier = Modifier.heightIn(min = LocalZephyrMetrics.current.controlHeight),
                    ) {
                        Text("Make default")
                    }
                }
                if (version.isInstalled) {
                    TextButton(
                        onClick = { viewModel.setVersionProtected(candidateName, version.version, !isProtected) },
                        modifier = Modifier.heightIn(min = LocalZephyrMetrics.current.controlHeight),
                    ) {
                        Text(if (isProtected) "Unpin" else "Protect")
                    }
                }
                if (version.isInstalled && version.isConfirmedLocalOnly) {
                    if (updateTargets.isNotEmpty()) Box {
                        OutlinedButton(onClick = { updateMenuOpen = true }, modifier = Modifier.heightIn(min = LocalZephyrMetrics.current.controlHeight)) { Text("Update") }
                        DropdownMenu(expanded = updateMenuOpen, onDismissRequest = { updateMenuOpen = false }) {
                            updateTargets.forEach { target ->
                                DropdownMenuItem(
                                    text = { Text(target.version) },
                                    onClick = {
                                        updateMenuOpen = false
                                        viewModel.requestTransaction(SdkmanTransaction.Install(candidateName, target.version))
                                    },
                                )
                            }
                        }
                    }
                }
                if (version.isInstalled && !version.isDefault && !version.isConfirmedLocalOnly && !isProtected) {
                    OutlinedButton(onClick = { onUninstall(candidateName, version.version) }, modifier = Modifier.heightIn(min = LocalZephyrMetrics.current.controlHeight)) {
                        Text("Uninstall")
                    }
                }
                if (version.isInstalled && version.isConfirmedLocalOnly && !version.isDefault && !isProtected) {
                    ZephyrDestructiveButton(
                        label = "Clean",
                        onClick = { onClean(candidateName, listOf(version.version)) },
                        modifier = Modifier.heightIn(min = LocalZephyrMetrics.current.controlHeight),
                    )
                }
            }
        }
    }
}
