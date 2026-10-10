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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.worxbend.zephyr.domain.CandidateCatalogItem
import com.worxbend.zephyr.domain.CandidateVersion
import com.worxbend.zephyr.domain.JDK_VENDOR_KNOWLEDGE_VERSION
import com.worxbend.zephyr.domain.JavaVersion
import com.worxbend.zephyr.domain.ProtectedVersion
import com.worxbend.zephyr.domain.SdkmanTransaction
import com.worxbend.zephyr.domain.jdkVendorKnowledge
import com.worxbend.zephyr.domain.toJavaVersion
import com.worxbend.zephyr.features.maintenance.trustedCleanupVersions
import com.worxbend.zephyr.settings.AppSettings
import com.worxbend.zephyr.settings.CollectionViewMode
import com.worxbend.zephyr.settings.SavedJdkFilter
import com.worxbend.zephyr.viewmodel.ZephyrUiState
import com.worxbend.zephyr.viewmodel.ZephyrViewModel

@Composable
internal fun BrowseScreen(
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
internal fun BrowseScreen(
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
                                    actions = com.worxbend.zephyr.features.catalog.CatalogActions(viewModel::requestTransaction, viewModel::setVersionProtected),
                                    sdkmanHome = state.sdkmanStatus.home,
                                    isProtected = ProtectedVersion("java", java.identifier) in state.protectedVersions,
                                    onClean = onClean,
                                    onUninstall = onUninstall,
                                    cleanupEligible = state.candidates.firstOrNull { it.name == "java" }?.let {
                                        java.identifier in trustedCleanupVersions(state, it)
                                    } == true,
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

internal fun Set<String>.updated(value: String, included: Boolean): Set<String> =
    if (included) this + value else this - value

private enum class CatalogFilter(val label: String) {
    All("All"),
    Installed("Installed"),
    Available("Available"),
}
