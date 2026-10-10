package com.worxbend.zephyr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.worxbend.zephyr.domain.CandidateKind
import com.worxbend.zephyr.domain.ProtectedVersion
import com.worxbend.zephyr.domain.toJavaVersion
import com.worxbend.zephyr.features.maintenance.trustedCleanupVersions
import com.worxbend.zephyr.features.maintenance.trustedCleanupVersionsByCandidate
import com.worxbend.zephyr.settings.AppSettings
import com.worxbend.zephyr.settings.CollectionViewMode
import com.worxbend.zephyr.viewmodel.ZephyrRoute
import com.worxbend.zephyr.viewmodel.ZephyrUiState

@Composable
internal fun InstalledJdkScreen(
    state: ZephyrUiState.Ready,
    onNavigate: (ZephyrRoute) -> Unit,
    onProtectionChange: (String, String, Boolean) -> Unit,
    onClean: (String, List<String>) -> Unit,
) {
    val jdk = state.candidates.firstOrNull { it.name == "java" }
    var query by remember { mutableStateOf("") }
    var grouping by remember { mutableStateOf(JavaVersionGrouping.None) }
    var terminalMessage by remember { mutableStateOf<String?>(null) }
    val terminalLauncher = LocalAppServices.current.terminalLauncher
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
                        cleanupEligible = jdk != null && version.identifier in trustedCleanupVersions(state, jdk),
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
internal fun InstalledSdksScreen(
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
                    cleanupVersionsByCandidate = trustedCleanupVersionsByCandidate(state, filtered),
                    onOpen = { onNavigate(ZephyrRoute.SdkDetail(it.name)) },
                    onClean = onClean,
                )
            } else {
                CandidateTable(
                    candidates = filtered,
                    protectedVersions = state.protectedVersions,
                    cleanupVersionsByCandidate = trustedCleanupVersionsByCandidate(state, filtered),
                    onOpen = { onNavigate(ZephyrRoute.SdkDetail(it.name)) },
                    onClean = onClean,
                )
            }
        }
    }
}
