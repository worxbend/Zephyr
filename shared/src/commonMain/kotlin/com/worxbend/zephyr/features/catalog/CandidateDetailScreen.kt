package com.worxbend.zephyr

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import com.worxbend.zephyr.domain.Candidate
import com.worxbend.zephyr.domain.CandidateVersion
import com.worxbend.zephyr.domain.ProtectedVersion
import com.worxbend.zephyr.domain.SdkmanTransaction
import com.worxbend.zephyr.domain.displayNameFor
import com.worxbend.zephyr.domain.toJavaVersion
import com.worxbend.zephyr.features.catalog.CatalogActions
import com.worxbend.zephyr.features.maintenance.trustedCleanupVersions
import com.worxbend.zephyr.viewmodel.ZephyrUiState

@Composable
internal fun CandidateDetailScreen(
    state: ZephyrUiState.Ready,
    candidateName: String,
    jdk: Boolean,
    actions: CatalogActions,
    onClean: (String, List<String>) -> Unit,
    onUninstall: (String, String) -> Unit,
) {
    val candidate = state.selectedCandidate?.takeIf { it.name == candidateName } ?: state.candidates.firstOrNull { it.name == candidateName }
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
                actions,
                onClean,
                onUninstall,
                trustedCleanupVersions(state, candidate).toSet(),
            )
        } else {
            VersionList(
                candidate,
                state.protectedVersions,
                state.sdkmanStatus.home,
                actions,
                onClean,
                onUninstall,
                trustedCleanupVersions(state, candidate).toSet(),
            )
        }
    }
}

@Composable
private fun JdkDetailVersions(
    candidate: Candidate,
    protectedVersions: Set<ProtectedVersion>,
    sdkmanHome: String?,
    actions: CatalogActions,
    onClean: (String, List<String>) -> Unit,
    onUninstall: (String, String) -> Unit,
    cleanupVersions: Set<String>,
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
                actions.review(SdkmanTransaction.RefreshMetadata)
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
                        actions = actions,
                        sdkmanHome = sdkmanHome,
                        isProtected = ProtectedVersion(candidate.name, java.identifier) in protectedVersions,
                        cleanupEligible = java.identifier in cleanupVersions,
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
    actions: CatalogActions,
    onClean: (String, List<String>) -> Unit,
    onUninstall: (String, String) -> Unit,
    cleanupVersions: Set<String>,
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
                actions.review(SdkmanTransaction.RefreshMetadata)
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
                    actions = actions,
                    sdkmanHome = sdkmanHome,
                    isProtected = ProtectedVersion(candidate.name, version.version) in protectedVersions,
                    onClean = onClean,
                    onUninstall = onUninstall,
                    cleanupEligible = version.version in cleanupVersions,
                )
            }
        }
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(listState),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
        )
    }
}

@Composable
internal fun VersionRow(
    candidateName: String,
    version: CandidateVersion,
    updateTargets: List<CandidateVersion>,
    actions: CatalogActions,
    sdkmanHome: String?,
    isProtected: Boolean,
    onClean: (String, List<String>) -> Unit,
    onUninstall: (String, String) -> Unit,
    cleanupEligible: Boolean,
) {
    var updateMenuOpen by remember { mutableStateOf(false) }
    var terminalMessage by remember(candidateName, version.version) { mutableStateOf<String?>(null) }
    val clipboard = LocalAppServices.current.clipboardService
    val terminalLauncher = LocalAppServices.current.terminalLauncher
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
                    actions.review(SdkmanTransaction.Install(candidateName, version.version))
                })
            }
            if (version.isInstalled && !version.isDefault) {
                add(ContextAction("Make default") {
                    actions.review(SdkmanTransaction.SetDefault(candidateName, version.version))
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
                    actions.protect(candidateName, version.version, !isProtected)
                })
            }
            if (version.isInstalled && version.isConfirmedLocalOnly) {
                updateTargets.forEach { target ->
                    add(ContextAction("Install update ${target.version}") {
                        actions.review(SdkmanTransaction.Install(candidateName, target.version))
                    })
                }
            }
            if (cleanupEligible) {
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
                        onClick = { actions.review(SdkmanTransaction.Install(candidateName, version.version)) },
                        modifier = Modifier.heightIn(min = LocalZephyrMetrics.current.controlHeight),
                    ) {
                        Text("Install")
                    }
                }
                if (version.isInstalled && !version.isDefault) {
                    FilledTonalButton(
                        onClick = { actions.review(SdkmanTransaction.SetDefault(candidateName, version.version)) },
                        modifier = Modifier.heightIn(min = LocalZephyrMetrics.current.controlHeight),
                    ) {
                        Text("Make default")
                    }
                }
                if (version.isInstalled) {
                    TextButton(
                        onClick = { actions.protect(candidateName, version.version, !isProtected) },
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
                                        actions.review(SdkmanTransaction.Install(candidateName, target.version))
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
                if (cleanupEligible) {
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
