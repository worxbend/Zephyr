package com.worxbend.zephyr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.TextButton
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.worxbend.zephyr.domain.CandidateKind
import com.worxbend.zephyr.domain.BatchItemStatus
import com.worxbend.zephyr.domain.CandidateMetadataStatus
import com.worxbend.zephyr.domain.ConnectivityState
import com.worxbend.zephyr.domain.IntegrityCheck
import com.worxbend.zephyr.domain.IntegrityStatus
import com.worxbend.zephyr.domain.InstallTarget
import com.worxbend.zephyr.domain.UpdateActivationTarget
import com.worxbend.zephyr.domain.OperationJournalEntry
import com.worxbend.zephyr.domain.OperationStatus
import com.worxbend.zephyr.domain.RecoveryAction
import com.worxbend.zephyr.domain.SdkmanCommandAction
import com.worxbend.zephyr.domain.SdkmanSelfUpdateStatus
import com.worxbend.zephyr.domain.SdkmanTransaction
import com.worxbend.zephyr.domain.displayNameFor
import com.worxbend.zephyr.domain.javaProviderName
import com.worxbend.zephyr.domain.recoveryGuidance
import com.worxbend.zephyr.domain.resumableCommands
import com.worxbend.zephyr.domain.searchOperationJournal
import com.worxbend.zephyr.domain.calculateDesiredStateDrift
import com.worxbend.zephyr.data.formatLocalTimestamp
import com.worxbend.zephyr.data.captureEnvironmentSnapshot
import com.worxbend.zephyr.data.createBrowserLauncher
import com.worxbend.zephyr.data.createEnvironmentSnapshotService
import com.worxbend.zephyr.data.currentEpochMillis
import com.worxbend.zephyr.data.diffEnvironmentSnapshots
import com.worxbend.zephyr.data.planSnapshotRestore
import com.worxbend.zephyr.data.SdkmanRcDocument
import com.worxbend.zephyr.data.ProjectWorkspaceDocument
import com.worxbend.zephyr.data.createProjectToolchainService
import com.worxbend.zephyr.data.createTerminalLauncher
import com.worxbend.zephyr.data.ProxyConfiguration
import com.worxbend.zephyr.data.createProxyConfigurationService
import com.worxbend.zephyr.data.createSdkmanHomeConfigurationService
import com.worxbend.zephyr.data.createPortablePreferencesService
import com.worxbend.zephyr.settings.AppSettings
import com.worxbend.zephyr.settings.CleanupGracePeriod
import com.worxbend.zephyr.settings.MetadataRefreshSchedule
import com.worxbend.zephyr.settings.MotionPreference
import com.worxbend.zephyr.settings.OperationNotificationPolicy
import com.worxbend.zephyr.settings.ThemePreference
import com.worxbend.zephyr.settings.TextScale
import com.worxbend.zephyr.settings.ToolchainProfile
import com.worxbend.zephyr.settings.UiDensity
import com.worxbend.zephyr.settings.UpdateNotificationPolicy
import com.worxbend.zephyr.settings.applyPortablePreferences
import com.worxbend.zephyr.settings.portablePreferences
import com.worxbend.zephyr.viewmodel.ZephyrRoute
import com.worxbend.zephyr.viewmodel.ZephyrUiState
import com.worxbend.zephyr.viewmodel.ZephyrViewModel
import kotlinx.coroutines.launch

@Composable
internal fun OverviewScreen(
    state: ZephyrUiState.Ready,
    viewModel: ZephyrViewModel,
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
) {
    val metrics = LocalZephyrMetrics.current
    val jdk = state.candidates.firstOrNull { it.kind == CandidateKind.Jdk }
    val sdks = state.candidates.count { it.kind == CandidateKind.Sdk }
    val installedVersions = state.candidates.sumOf { candidate -> candidate.installedVersions.count { it.isInstalled } }
    val localOnly = state.candidates.sumOf { it.localOnlyVersionCount }
    val desiredState = settings.desiredToolchainState
    val desiredDrift = desiredState?.let { calculateDesiredStateDrift(it, state.candidates) }

    ZephyrScrollPane(
        modifier = Modifier.fillMaxSize(),
    ) {
        PageTitle("Overview", "Your SDKMAN toolchain at a glance.")
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val tiles: @Composable (Modifier) -> Unit = { tileModifier ->
                ZephyrMetricTile(
                    label = "Default JDK",
                    value = jdk?.defaultVersion ?: "Not set",
                    detail = if (jdk == null) "Install a JDK to get started" else "${jdk.installedVersions.count { it.isInstalled }} installed",
                    tone = if (jdk?.defaultVersion != null) StatusTone.Success else StatusTone.Warning,
                    modifier = tileModifier,
                )
                ZephyrMetricTile(
                    label = "Installed SDKs",
                    value = sdks.toString(),
                    detail = "$installedVersions total versions",
                    tone = StatusTone.Accent,
                    modifier = tileModifier,
                )
                ZephyrMetricTile(
                    label = "Local-only",
                    value = localOnly.toString(),
                    detail = if (localOnly == 0) "No cleanup needed" else "Review before cleaning",
                    tone = if (localOnly == 0) StatusTone.Success else StatusTone.Warning,
                    modifier = tileModifier,
                )
            }
            // Row weights divide the actual pixel budget, including spacing. Independently
            // rounded Dp widths in a FlowRow can overflow by one pixel and wrap tile three.
            if (maxWidth >= 840.dp * zephyrContentScale()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(metrics.spacing)) {
                    tiles(Modifier.weight(1f))
                }
            } else {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(metrics.spacing)) {
                    tiles(Modifier.fillMaxWidth())
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            PanelHeading("Quick actions", "Common SDKMAN workflows")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ZephyrToolbarButton("Browse JDKs", onClick = { viewModel.navigate(ZephyrRoute.BrowseJdks) }, suggested = true)
                ZephyrToolbarButton("Browse SDKs", onClick = { viewModel.navigate(ZephyrRoute.BrowseSdks) })
                ZephyrToolbarButton("Profiles", onClick = { viewModel.navigate(ZephyrRoute.Profiles) })
                ZephyrToolbarButton("Update Center", onClick = { viewModel.navigate(ZephyrRoute.UpdateCenter) })
            }
        }
        OverviewColumns(
            primary = {
                OverviewSection("Desired state", "Continuous drift visibility; extra versions are report-only") {
                    if (desiredState == null || desiredDrift == null) {
                        Text(
                            "Choose a toolchain profile or environment snapshot as the desired baseline.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ZephyrToolbarButton(
                            "Choose in Profiles",
                            onClick = { viewModel.navigate(ZephyrRoute.Profiles) },
                        )
                    } else {
                        Text(
                            "${desiredState.sourceKind.label}: ${desiredState.sourceLabel}",
                            fontWeight = FontWeight.SemiBold,
                        )
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            Badge(
                                if (desiredDrift.isAligned) "Aligned" else "Drift detected",
                                if (desiredDrift.isAligned) BadgeTone.Success else BadgeTone.Warning,
                            )
                            if (desiredDrift.missingVersions.isNotEmpty()) {
                                Badge("${desiredDrift.missingVersions.size} missing", BadgeTone.Warning)
                            }
                            if (desiredDrift.defaultChanges.isNotEmpty()) {
                                Badge("${desiredDrift.defaultChanges.size} defaults differ", BadgeTone.Primary)
                            }
                            if (desiredDrift.extraInstalledVersions.isNotEmpty()) {
                                Badge("${desiredDrift.extraInstalledVersions.size} extra (report only)")
                            }
                            if (desiredDrift.localOnlyDesiredVersions.isNotEmpty()) {
                                Badge("${desiredDrift.localOnlyDesiredVersions.size} desired local-only", BadgeTone.Warning)
                            }
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            if (desiredDrift.remediationCommands.isNotEmpty()) {
                                ZephyrToolbarButton(
                                    "Review repair (${desiredDrift.remediationCommands.size})",
                                    onClick = {
                                        viewModel.requestTransaction(
                                            SdkmanTransaction.ToolchainActivation(
                                                profileName = "Desired state · ${desiredState.sourceLabel}",
                                                commands = desiredDrift.remediationCommands,
                                            ),
                                        )
                                    },
                                )
                            }
                            ZephyrToolbarButton(
                                "Clear desired state",
                                onClick = {
                                    onSettingsChange { it.copy(desiredToolchainState = null) }
                                },
                            )
                        }
                    }
                }
                OverviewSection("Toolchain summary", "Persisted SDKMAN defaults") {
                    KeyValueRow("SDKMAN", sdkmanVersionLabel(state))
                    KeyValueRow("Default JDK", jdk?.defaultVersion ?: "Not configured")
                    KeyValueRow("Candidates", state.candidates.size.toString())
                    KeyValueRow("Catalog", if (state.catalog.isEmpty()) "Not loaded" else "${state.catalog.size} packages")
                }
                OverviewSection("Recent items", "Last-viewed candidate details") {
                    if (settings.recentCandidates.isEmpty()) {
                        Text(
                            "Candidate details you open will appear here.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            settings.recentCandidates.forEach { candidate ->
                                val installed = state.candidates.firstOrNull { it.name == candidate }
                                val remote = state.catalog.firstOrNull { it.name == candidate }
                                val label = installed?.displayName ?: remote?.displayName ?: displayNameFor(candidate)
                                val kind = installed?.kind ?: remote?.kind
                                ZephyrToolbarButton(
                                    label = label,
                                    onClick = {
                                        viewModel.navigate(
                                            if (candidate == "java" || kind == CandidateKind.Jdk) {
                                                ZephyrRoute.JdkDetail(candidate)
                                            } else {
                                                ZephyrRoute.SdkDetail(candidate)
                                            },
                                        )
                                    },
                                )
                            }
                        }
                    }
                }
            },
            secondary = {
                OverviewSection("Environment health", "Read-only diagnostics") {
                    HealthRow("SDKMAN detected", true)
                    HealthRow("SDKMAN service online", state.connectivityStatus.state == ConnectivityState.Online)
                    HealthRow("CLI version available", state.sdkmanStatus.cliVersion != null)
                    HealthRow("Default JDK configured", jdk?.defaultVersion != null)
                    HealthRow("No local-only versions", localOnly == 0)
                    HealthRow(
                        "SDKMAN integrity",
                        state.integrityChecks.none { it.status == IntegrityStatus.Failed },
                    )
                    ZephyrToolbarButton(
                        label = "Open diagnostics",
                        onClick = { viewModel.navigate(ZephyrRoute.Diagnostics) },
                    )
                }
                OverviewSection("Favorites", "Pinned SDKs and JDK vendors") {
                    if (settings.favoriteCandidates.isEmpty() && settings.favoriteJdkVendors.isEmpty()) {
                        Text(
                            "Pin SDKs or JDK vendors from Browse to keep them close.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            settings.favoriteCandidates.sorted().forEach { candidate ->
                                val label = state.catalog.firstOrNull { it.name == candidate }?.displayName
                                    ?: state.candidates.firstOrNull { it.name == candidate }?.displayName
                                    ?: displayNameFor(candidate)
                                ZephyrToolbarButton(
                                    label = "★ $label",
                                    onClick = { viewModel.navigate(ZephyrRoute.SdkDetail(candidate)) },
                                )
                            }
                            settings.favoriteJdkVendors.sorted().forEach { vendor ->
                                ZephyrToolbarButton(
                                    label = "★ ${javaProviderName(vendor) ?: vendor}",
                                    onClick = { viewModel.navigate(ZephyrRoute.BrowseJdks) },
                                )
                            }
                        }
                    }
                }
            },
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ZephyrToolbarButton("Refresh local state", onClick = viewModel::refreshInstalled)
            ZephyrToolbarButton("Scan local-only", onClick = viewModel::scanLocalOnly)
            TextButton(onClick = { viewModel.navigate(ZephyrRoute.BatchUninstall) }) { Text("Batch Uninstall") }
        }
    }
}

@Composable
private fun OverviewColumns(
    primary: @Composable () -> Unit,
    secondary: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth >= 960.dp * zephyrContentScale()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Column(Modifier.weight(3f), verticalArrangement = Arrangement.spacedBy(24.dp)) { primary() }
                Column(Modifier.weight(2f), verticalArrangement = Arrangement.spacedBy(24.dp)) { secondary() }
            }
        } else {
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                primary()
                secondary()
            }
        }
    }
}

@Composable
private fun OverviewSection(
    title: String,
    subtitle: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    ZephyrPanel(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().padding(LocalZephyrMetrics.current.panelPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PanelHeading(title, subtitle)
            content()
        }
    }
}

@Composable
internal fun UpdateCenterScreen(
    state: ZephyrUiState.Ready,
    viewModel: ZephyrViewModel,
) {
    val metrics = LocalZephyrMetrics.current
    val browserLauncher = remember { createBrowserLauncher() }
    val updates = availableCandidateUpdates(state.candidates, state.catalog)
    val updateIds = updates.map { "${it.candidate}:${it.targetVersion}" }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var releaseNotesMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(updateIds) {
        selected = selected.intersect(updateIds.toSet())
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(metrics.spacing * 2),
    ) {
        ZephyrRecordLayout(
            modifier = Modifier.fillMaxWidth(),
            content = {
                PageTitle(
                    "Update Center",
                    "Stable SDKMAN targets that still need installation or default activation.",
                )
            },
        ) {
            ZephyrToolbarButton(
                label = "Refresh metadata",
                onClick = { viewModel.requestTransaction(SdkmanTransaction.RefreshMetadata) },
                enabled = !state.isRefreshing && !state.isCatalogLoading,
            )
        }
        releaseNotesMessage?.let {
            Text(
                it,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        when {
            state.isCatalogLoading -> {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ZephyrProgressIndicator()
                    Text("Loading SDKMAN update metadata…")
                }
            }
            state.catalog.isEmpty() -> {
                EmptyState(
                    "Update metadata unavailable",
                    "Load the SDKMAN catalog to check installed candidates for stable updates.",
                    "Refresh metadata",
                ) {
                    viewModel.requestTransaction(SdkmanTransaction.RefreshMetadata)
                }
            }
            updates.isEmpty() -> {
                EmptyState(
                    "Toolchain is current",
                    "Every installed candidate with a stable catalog target has that version installed and active as the SDKMAN default.",
                    "Browse SDKs",
                ) {
                    viewModel.navigate(ZephyrRoute.BrowseSdks)
                }
            }
            else -> {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ZephyrToolbarButton(
                        label = if (selected.size == updates.size) "Clear selection" else "Select all",
                        onClick = {
                            selected = if (selected.size == updates.size) emptySet() else updateIds.toSet()
                        },
                    )
                    Text(
                        "${selected.size} selected • " +
                            "${updates.count { it.state == StableTargetState.Missing }} install(s) • " +
                            "${updates.count { it.state == StableTargetState.InstalledInactive }} activation(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ZephyrToolbarButton(
                        label = "Review selected (${selected.size})",
                        onClick = {
                            val targets = updates
                                .filter { "${it.candidate}:${it.targetVersion}" in selected }
                                .map {
                                    UpdateActivationTarget(
                                        it.candidate,
                                        it.targetVersion,
                                        requiresInstall = it.state == StableTargetState.Missing,
                                    )
                                }
                            if (targets.isNotEmpty()) {
                                viewModel.requestTransaction(SdkmanTransaction.UpdateActivation(targets))
                            }
                        },
                        enabled = selected.isNotEmpty(),
                    )
                }
                if (state.updateActivationProgress.isNotEmpty()) {
                    ZephyrPanel(Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(metrics.panelPadding),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            PanelHeading("Update progress", "Sequential install and activation results")
                            state.updateActivationProgress.forEach { item ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Badge(item.status.label, when (item.status) {
                                        BatchItemStatus.Succeeded -> BadgeTone.Success
                                        BatchItemStatus.Failed -> BadgeTone.Error
                                        BatchItemStatus.Skipped -> BadgeTone.Warning
                                        BatchItemStatus.Running -> BadgeTone.Primary
                                        BatchItemStatus.Pending -> BadgeTone.Neutral
                                    })
                                    Text(
                                        "${item.command.action.label}: ${item.command.candidate} ${item.command.version}",
                                        modifier = Modifier.weight(1f),
                                    )
                                    item.outcome?.let {
                                        Text(
                                            it,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(metrics.spacing),
                ) {
                    updates.groupBy { it.kind }.forEach { (kind, group) ->
                        item {
                            ZephyrSectionHeading(if (kind == CandidateKind.Jdk) "JDK updates" else "SDK updates")
                        }
                        items(group, key = { "${it.candidate}:${it.targetVersion}" }) { update ->
                            val id = "${update.candidate}:${update.targetVersion}"
                            ZephyrPanel(Modifier.fillMaxWidth()) {
                                ZephyrRecordLayout(
                                    modifier = Modifier.fillMaxWidth().padding(metrics.panelPadding),
                                    content = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    ) {
                                    Checkbox(
                                        checked = id in selected,
                                        onCheckedChange = { checked ->
                                            selected = if (checked) selected + id else selected - id
                                        },
                                    )
                                    CandidateIcon(update.kind)
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(5.dp),
                                    ) {
                                        Text(update.displayName, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "${update.currentVersion ?: "No default"} → ${update.targetVersion}",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                                            Badge("SDKMAN key: ${update.candidate}")
                                            Badge("Stable target", BadgeTone.Success)
                                            Badge(
                                                update.state.label,
                                                if (update.state == StableTargetState.Missing) {
                                                    BadgeTone.Warning
                                                } else {
                                                    BadgeTone.Primary
                                                },
                                            )
                                        }
                                    }
                                    }
                                    },
                                ) {
                                    ZephyrToolbarButton(
                                        label = "Inspect",
                                        onClick = {
                                            viewModel.navigate(
                                                if (update.kind == CandidateKind.Jdk) {
                                                    ZephyrRoute.JdkDetail(update.candidate)
                                                } else {
                                                    ZephyrRoute.SdkDetail(update.candidate)
                                                },
                                            )
                                        },
                                    )
                                    releaseNotesUrl(update.candidate, update.targetVersion)?.let { url ->
                                        ZephyrToolbarButton(
                                            label = "Release notes",
                                            onClick = {
                                                releaseNotesMessage = if (browserLauncher.openHttps(url)) {
                                                    "Opened upstream release notes for ${update.displayName}."
                                                } else {
                                                    "A browser could not be opened for the validated release-notes URL."
                                                }
                                            },
                                        )
                                    }
                                    ZephyrToolbarButton(
                                        label = "Review update",
                                        onClick = {
                                            viewModel.requestTransaction(
                                                SdkmanTransaction.UpdateActivation(
                                                    listOf(
                                                        UpdateActivationTarget(
                                                            update.candidate,
                                                            update.targetVersion,
                                                            requiresInstall =
                                                                update.state == StableTargetState.Missing,
                                                        ),
                                                    ),
                                                ),
                                            )
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun BatchUninstallScreen(
    state: ZephyrUiState.Ready,
    viewModel: ZephyrViewModel,
) {
    val metrics = LocalZephyrMetrics.current
    val items = uninstallSelectionItems(state.candidates, state.protectedVersions)
    val eligible = items.filter { it.blockedReason == null }
    val eligibleIds = eligible.map { "${it.target.candidate}:${it.target.version}" }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    LaunchedEffect(eligibleIds) {
        selected = selected.intersect(eligibleIds.toSet())
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(metrics.spacing * 2),
    ) {
        PageTitle(
            "Batch Uninstall",
            "Remove selected non-default, unprotected versions with one reviewed transaction.",
        )
        if (items.isEmpty()) {
            EmptyState(
                "No installed versions",
                "Install a JDK or SDK version before preparing an uninstall batch.",
                "Browse SDKs",
            ) {
                viewModel.navigate(ZephyrRoute.BrowseSdks)
            }
            return@Column
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZephyrToolbarButton(
                label = if (selected.size == eligible.size && eligible.isNotEmpty()) {
                    "Clear selection"
                } else {
                    "Select all eligible"
                },
                onClick = {
                    selected = if (selected.size == eligible.size) emptySet() else eligibleIds.toSet()
                },
                enabled = eligible.isNotEmpty(),
            )
            Text(
                "${selected.size} selected • ${items.size - eligible.size} excluded",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ZephyrDestructiveButton(
                label = "Review uninstall (${selected.size})",
                onClick = {
                    val targets = eligible
                        .filter { "${it.target.candidate}:${it.target.version}" in selected }
                        .map { it.target }
                    if (targets.isNotEmpty()) {
                        viewModel.requestTransaction(SdkmanTransaction.BatchUninstall(targets))
                    }
                },
                enabled = selected.isNotEmpty(),
            )
        }
        if (state.batchUninstallProgress.isNotEmpty()) {
            ZephyrPanel(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(metrics.panelPadding),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    PanelHeading("Batch progress", "Sequential uninstall results")
                    state.batchUninstallProgress.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Badge(item.status.label, batchStatusTone(item.status))
                            Text(
                                "${item.target.candidate} ${item.target.version}",
                                modifier = Modifier.weight(1f),
                            )
                            item.outcome?.let {
                                Text(
                                    it,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(metrics.spacing),
        ) {
            items.groupBy { it.displayName }.forEach { (candidate, versions) ->
                item {
                    Text(candidate, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                }
                items(versions, key = { "${it.target.candidate}:${it.target.version}" }) { item ->
                    val id = "${item.target.candidate}:${item.target.version}"
                    val enabled = item.blockedReason == null
                    ZephyrPanel(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(metrics.panelPadding),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = id in selected,
                                enabled = enabled,
                                onCheckedChange = { checked ->
                                    selected = if (checked) selected + id else selected - id
                                },
                            )
                            CandidateIcon(item.kind)
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(item.target.version, fontWeight = FontWeight.SemiBold)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                                    Badge("SDKMAN key: ${item.target.candidate}")
                                    item.blockedReason?.let { Badge(it, BadgeTone.Warning) }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProjectWorkspacesScreen(
    state: ZephyrUiState.Ready,
    viewModel: ZephyrViewModel,
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
) {
    val metrics = LocalZephyrMetrics.current
    val service = remember { createProjectToolchainService() }
    val terminalLauncher = remember { createTerminalLauncher() }
    val scope = rememberCoroutineScope()
    var documents by remember { mutableStateOf<Map<String, ProjectWorkspaceDocument>>(emptyMap()) }
    var errors by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var launchMessages by remember { mutableStateOf<Map<String, String>>(emptyMap()) }
    var refreshGeneration by remember { mutableStateOf(0) }
    var loading by remember { mutableStateOf(false) }
    var pinning by remember { mutableStateOf(false) }

    LaunchedEffect(settings.projectWorkspaces, refreshGeneration) {
        loading = true
        val nextDocuments = mutableMapOf<String, ProjectWorkspaceDocument>()
        val nextErrors = mutableMapOf<String, String>()
        settings.projectWorkspaces.forEach { reference ->
            runCatching { service.readWorkspace(reference) }
                .onSuccess { nextDocuments[reference.sdkmanRcPath] = it }
                .onFailure { failure ->
                    nextErrors[reference.sdkmanRcPath] =
                        failure.message ?: "The project .sdkmanrc could not be read safely."
                }
        }
        documents = nextDocuments
        errors = nextErrors
        loading = false
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(metrics.spacing * 2),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                PageTitle(
                    "Project Workspaces",
                    "Pin local .sdkmanrc projects and open terminals scoped to their directory and complete toolchain.",
                )
            }
            ZephyrToolbarButton(
                label = if (loading) "Refreshing…" else "Refresh",
                onClick = { refreshGeneration += 1 },
                enabled = !loading && !pinning,
            )
            ZephyrToolbarButton(
                label = if (pinning) "Choosing…" else "Pin .sdkmanrc",
                onClick = {
                    if (!pinning) {
                        scope.launch {
                            pinning = true
                            runCatching { service.chooseWorkspace() }
                                .onSuccess { selected ->
                                    if (selected != null) {
                                        onSettingsChange {
                                            it.copy(
                                                projectWorkspaces = (
                                                    it.projectWorkspaces.filterNot { existing ->
                                                        existing.sdkmanRcPath == selected.reference.sdkmanRcPath
                                                    } + selected.reference
                                                    ).sortedBy { reference -> reference.displayName.lowercase() },
                                            )
                                        }
                                        documents = documents + (selected.reference.sdkmanRcPath to selected)
                                        errors = errors - selected.reference.sdkmanRcPath
                                    }
                                }
                                .onFailure { failure ->
                                    launchMessages = launchMessages + (
                                        PIN_WORKSPACE_MESSAGE_KEY to
                                            (failure.message ?: "Unable to pin the selected .sdkmanrc.")
                                        )
                                }
                            pinning = false
                        }
                    }
                },
                enabled = !pinning && !loading,
            )
        }
        launchMessages[PIN_WORKSPACE_MESSAGE_KEY]?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }
        if (settings.projectWorkspaces.isEmpty()) {
            EmptyState(
                "No project workspaces pinned",
                "Pin a real project .sdkmanrc. Its machine-local path stays out of portable preference exports.",
                "Pin .sdkmanrc",
            ) {
                if (!pinning) {
                    scope.launch {
                        pinning = true
                        runCatching { service.chooseWorkspace() }
                            .onSuccess { selected ->
                                if (selected != null) {
                                    onSettingsChange {
                                        it.copy(projectWorkspaces = listOf(selected.reference))
                                    }
                                }
                            }
                            .onFailure { failure ->
                                launchMessages = launchMessages + (
                                    PIN_WORKSPACE_MESSAGE_KEY to
                                        (failure.message ?: "Unable to pin the selected .sdkmanrc.")
                                    )
                            }
                        pinning = false
                    }
                }
            }
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(metrics.spacing),
        ) {
            items(settings.projectWorkspaces, key = { it.sdkmanRcPath }) { reference ->
                val document = documents[reference.sdkmanRcPath]
                val error = errors[reference.sdkmanRcPath]
                val diff = document?.let { compareProjectToolchain(it.targets, state.candidates) }.orEmpty()
                val status = projectWorkspaceStatus(diff)
                val missing = diff
                    .filter { it.status == ProjectTargetStatus.Install }
                    .map(ProjectTargetDiff::target)
                ZephyrPanel(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(metrics.panelPadding),
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(
                                    document?.reference?.displayName ?: reference.displayName,
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    when {
                                        error != null -> "Workspace unavailable"
                                        document == null -> "Loading local .sdkmanrc"
                                        document.targets.isEmpty() -> "No valid targets"
                                        status == ProjectWorkspaceStatus.DefaultsDiffer ->
                                            "All versions are installed; global defaults differ and will remain unchanged."
                                        status == ProjectWorkspaceStatus.Ready ->
                                            "Every target is installed and already matches the persisted defaults."
                                        else -> "${missing.size} target(s) must be installed before launch."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (document != null && document.targets.isNotEmpty()) {
                                Badge(
                                    status.label,
                                    when (status) {
                                        ProjectWorkspaceStatus.Ready -> BadgeTone.Success
                                        ProjectWorkspaceStatus.DefaultsDiffer -> BadgeTone.Primary
                                        ProjectWorkspaceStatus.Missing -> BadgeTone.Warning
                                    },
                                )
                            }
                            ZephyrToolbarButton(
                                "Remove",
                                onClick = {
                                    onSettingsChange {
                                        it.copy(
                                            projectWorkspaces = it.projectWorkspaces.filterNot { workspace ->
                                                workspace.sdkmanRcPath == reference.sdkmanRcPath
                                            },
                                        )
                                    }
                                },
                            )
                        }
                        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                        document?.let { workspace ->
                            if (workspace.warnings.isNotEmpty()) {
                                Text(
                                    "${workspace.warnings.size} invalid line(s) were ignored; only validated targets are used.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.tertiary,
                                )
                            }
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(7.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp),
                            ) {
                                diff.forEach { item ->
                                    Badge(
                                        "${item.target.candidate} ${item.target.version}",
                                        when (item.status) {
                                            ProjectTargetStatus.Current -> BadgeTone.Success
                                            ProjectTargetStatus.DefaultChange -> BadgeTone.Primary
                                            ProjectTargetStatus.Install -> BadgeTone.Warning
                                        },
                                    )
                                }
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (missing.isNotEmpty()) {
                                    ZephyrToolbarButton(
                                        "Review missing (${missing.size})",
                                        onClick = {
                                            viewModel.requestTransaction(SdkmanTransaction.BatchInstall(missing))
                                        },
                                    )
                                }
                                ZephyrToolbarButton(
                                    "Open scoped terminal",
                                    enabled = missing.isEmpty() &&
                                        workspace.targets.isNotEmpty() &&
                                        !state.sdkmanStatus.home.isNullOrBlank(),
                                    onClick = {
                                        val result = terminalLauncher.launchWorkspace(
                                            sdkmanHome = state.sdkmanStatus.home.orEmpty(),
                                            workingDirectory = workspace.projectDirectory,
                                            targets = workspace.targets,
                                        )
                                        launchMessages = launchMessages + (
                                            reference.sdkmanRcPath to result.message
                                            )
                                    },
                                )
                                launchMessages[reference.sdkmanRcPath]?.let { message ->
                                    Text(
                                        message,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private const val PIN_WORKSPACE_MESSAGE_KEY = "<pin-workspace>"

@Composable
internal fun ToolchainProfilesScreen(
    state: ZephyrUiState.Ready,
    viewModel: ZephyrViewModel,
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
) {
    val metrics = LocalZephyrMetrics.current
    var profileName by remember { mutableStateOf("") }
    val currentDefaults = state.candidates.mapNotNull { candidate ->
        candidate.defaultVersion?.let { InstallTarget(candidate.name, it) }
    }
    val saveProfile: (String) -> Unit = { name ->
        val profile = ToolchainProfile(name.trim(), currentDefaults)
        onSettingsChange {
            it.copy(
                toolchainProfiles = (
                    it.toolchainProfiles.filterNot { existing ->
                        existing.name.equals(profile.name, ignoreCase = true)
                    } + profile
                    ).sortedBy { saved -> saved.name.lowercase() },
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(metrics.spacing * 2),
    ) {
        PageTitle(
            "Toolchain Profiles",
            "Save named default-version sets, compare them with this machine, and activate the complete environment.",
        )
        ZephyrPanel(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(metrics.panelPadding),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = profileName,
                    onValueChange = { profileName = it.take(60) },
                    modifier = Modifier.width(300.dp),
                    singleLine = true,
                    label = { Text("Profile name") },
                    placeholder = { Text("Backend, Android, Data…") },
                )
                Column(Modifier.weight(1f)) {
                    Text("Capture current defaults", fontWeight = FontWeight.SemiBold)
                    Text(
                        "${currentDefaults.size} candidate default(s) will be saved.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ZephyrToolbarButton(
                    label = "Save profile",
                    onClick = {
                        saveProfile(profileName)
                        profileName = ""
                    },
                    enabled = profileName.isNotBlank() && currentDefaults.isNotEmpty(),
                )
            }
        }
        if (settings.toolchainProfiles.isEmpty()) {
            EmptyState(
                "No profiles saved",
                if (currentDefaults.isEmpty()) {
                    "Set at least one SDKMAN default before capturing a reusable toolchain profile."
                } else {
                    "Capture the ${currentDefaults.size} current default(s) as a reusable starting point."
                },
                if (currentDefaults.isEmpty()) "Browse SDKs" else "Save Current toolchain",
            ) {
                if (currentDefaults.isEmpty()) {
                    viewModel.navigate(ZephyrRoute.BrowseSdks)
                } else {
                    saveProfile("Current toolchain")
                }
            }
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(metrics.spacing),
        ) {
            items(settings.toolchainProfiles, key = ToolchainProfile::name) { profile ->
                val activationPlan = planToolchainActivation(profile.targets, state.candidates)
                val profileDesiredState = desiredStateFromProfile(profile)
                val isDesiredState = settings.desiredToolchainState == profileDesiredState
                val missingCount = activationPlan.count { it.action == SdkmanCommandAction.Install }
                val defaultChangeCount = activationPlan.count { it.action == SdkmanCommandAction.SetDefault }
                ZephyrPanel(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(metrics.panelPadding),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    when {
                                        activationPlan.isEmpty() -> "${profile.targets.size} target(s) active"
                                        else -> "$missingCount missing • $defaultChangeCount default change(s)"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (activationPlan.isNotEmpty()) {
                                ZephyrToolbarButton(
                                    label = "Review activation (${activationPlan.size})",
                                    onClick = {
                                        viewModel.requestTransaction(
                                            SdkmanTransaction.ToolchainActivation(profile.name, activationPlan),
                                        )
                                    },
                                )
                            } else {
                                Badge("Active", BadgeTone.Success)
                            }
                            if (isDesiredState) {
                                Badge("Desired state", BadgeTone.Primary)
                            } else {
                                ZephyrToolbarButton(
                                    label = "Use as desired",
                                    onClick = {
                                        onSettingsChange {
                                            it.copy(desiredToolchainState = profileDesiredState)
                                        }
                                    },
                                )
                            }
                            ZephyrToolbarButton(
                                label = "Delete profile",
                                onClick = {
                                    onSettingsChange {
                                        it.copy(toolchainProfiles = it.toolchainProfiles - profile)
                                    }
                                },
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            profile.targets.forEach { target ->
                                val current = state.candidates.firstOrNull { it.name == target.candidate }
                                val installed = current
                                    ?.installedVersions
                                    .orEmpty()
                                    .any { it.isInstalled && it.version == target.version }
                                val isDefault = current?.defaultVersion == target.version
                                Badge(
                                    "${target.candidate} ${target.version}",
                                    when {
                                        isDefault -> BadgeTone.Success
                                        !installed -> BadgeTone.Warning
                                        else -> BadgeTone.Primary
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProjectToolchainImportScreen(
    state: ZephyrUiState.Ready,
) {
    val metrics = LocalZephyrMetrics.current
    val service = remember { createProjectToolchainService() }
    val scope = rememberCoroutineScope()
    var document by remember { mutableStateOf<SdkmanRcDocument?>(null) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val diff = document?.let { compareProjectToolchain(it.targets, state.candidates) }.orEmpty()
    val chooseDocument: () -> Unit = choose@{
        if (loading) return@choose
        scope.launch {
            loading = true
            error = null
            runCatching { service.chooseAndRead() }
                .onSuccess { selected -> if (selected != null) document = selected }
                .onFailure { failure -> error = failure.message ?: "Unable to read the selected file." }
            loading = false
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(metrics.spacing * 2),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                PageTitle(
                    "Project Toolchain Import",
                    "Read a project .sdkmanrc and review its required local changes without modifying SDKMAN.",
                )
            }
            ZephyrToolbarButton(
                label = if (loading) "Choosing…" else "Choose .sdkmanrc",
                onClick = chooseDocument,
                enabled = !loading,
            )
        }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        val current = document
        if (current == null) {
            EmptyState(
                "Choose a project toolchain",
                "Zephyr reads candidate=version entries locally and shows a reviewable diff.",
                "Choose .sdkmanrc",
                chooseDocument,
            )
            return@Column
        }
        ZephyrPanel(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(metrics.panelPadding),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(current.fileName, fontWeight = FontWeight.SemiBold)
                    Text(
                        "${current.targets.size} target(s) • ${current.warnings.size} warning(s)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        if (current.warnings.isNotEmpty()) {
            ZephyrPanel(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.padding(metrics.panelPadding),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    PanelHeading("Parser warnings", "Ignored lines do not enter the review")
                    current.warnings.forEach { warning ->
                        Text("• $warning", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (diff.isEmpty()) {
            EmptyState(
                "No valid targets",
                "The selected file contains no valid candidate=version entries.",
                "Choose another file",
                chooseDocument,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(metrics.spacing),
            ) {
                items(diff, key = { "${it.target.candidate}:${it.target.version}" }) { item ->
                    ZephyrPanel(Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(metrics.panelPadding),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "${item.target.candidate} ${item.target.version}",
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    state.candidates.firstOrNull { it.name == item.target.candidate }?.displayName
                                        ?: displayNameFor(item.target.candidate),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            Badge(
                                item.status.label,
                                when (item.status) {
                                    ProjectTargetStatus.Current -> BadgeTone.Success
                                    ProjectTargetStatus.DefaultChange -> BadgeTone.Primary
                                    ProjectTargetStatus.Install -> BadgeTone.Warning
                                },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ProjectToolchainExportScreen(
    state: ZephyrUiState.Ready,
    viewModel: ZephyrViewModel,
) {
    val metrics = LocalZephyrMetrics.current
    val service = remember { createProjectToolchainService() }
    val scope = rememberCoroutineScope()
    val defaults = state.candidates.mapNotNull { candidate ->
        candidate.defaultVersion?.let { version ->
            Triple(candidate.name, candidate.displayName, InstallTarget(candidate.name, version))
        }
    }
    val defaultIds = defaults.map { it.first }
    var selected by remember(defaultIds) { mutableStateOf(defaultIds.toSet()) }
    var exporting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(metrics.spacing * 2),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                PageTitle(
                    "Project Toolchain Export",
                    "Choose persisted defaults and generate a deterministic .sdkmanrc for a project.",
                )
            }
            ZephyrToolbarButton(
                label = if (exporting) "Exporting…" else "Export selected (${selected.size})",
                onClick = {
                    val targets = defaults.filter { it.first in selected }.map { it.third }
                    scope.launch {
                        exporting = true
                        error = null
                        message = null
                        runCatching { service.chooseAndWrite(targets) }
                            .onSuccess { result ->
                                if (result != null) {
                                    message = "Exported ${result.exportedTargets} defaults to ${result.fileName}."
                                }
                            }
                            .onFailure { failure ->
                                error = failure.message ?: "Unable to export .sdkmanrc."
                            }
                        exporting = false
                    }
                },
                enabled = !exporting && selected.isNotEmpty(),
            )
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        if (defaults.isEmpty()) {
            EmptyState(
                "No defaults to export",
                "Set a persisted default for at least one installed candidate first.",
                "Browse SDKs",
            ) {
                viewModel.navigate(ZephyrRoute.BrowseSdks)
            }
            return@Column
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ZephyrToolbarButton(
                label = if (selected.size == defaults.size) "Clear selection" else "Select all",
                onClick = {
                    selected = if (selected.size == defaults.size) emptySet() else defaultIds.toSet()
                },
            )
            Text(
                "Existing files require an explicit overwrite confirmation.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(metrics.spacing),
        ) {
            items(defaults, key = { it.first }) { (candidate, displayName, target) ->
                ZephyrPanel(Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(metrics.panelPadding),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = candidate in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + candidate else selected - candidate
                            },
                        )
                        Column(Modifier.weight(1f)) {
                            Text(displayName, fontWeight = FontWeight.SemiBold)
                            Text(
                                "${target.candidate}=${target.version}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Badge("Persisted default", BadgeTone.Primary)
                    }
                }
            }
        }
    }
}

@Composable
internal fun EnvironmentSnapshotScreen(
    state: ZephyrUiState.Ready,
    viewModel: ZephyrViewModel,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
) {
    val metrics = LocalZephyrMetrics.current
    val service = remember { createEnvironmentSnapshotService() }
    val scope = rememberCoroutineScope()
    val current = remember(state.candidates) {
        captureEnvironmentSnapshot(state.candidates, currentEpochMillis())
    }
    var baseline by remember { mutableStateOf<com.worxbend.zephyr.data.EnvironmentSnapshot?>(null) }
    var restoreSnapshot by remember { mutableStateOf<com.worxbend.zephyr.data.EnvironmentSnapshot?>(null) }
    var choosingSnapshot by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val changes = baseline?.let { diffEnvironmentSnapshots(it, current) }.orEmpty()
    val restorePlan = restoreSnapshot?.let { planSnapshotRestore(it, state.candidates) }.orEmpty()
    val versionCount = current.candidates.sumOf { it.installedVersions.size }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(metrics.spacing * 2),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                PageTitle(
                    "Environment Snapshot",
                    "Capture installed SDKMAN versions and persisted defaults in a deterministic, portable file.",
                )
            }
            ZephyrToolbarButton(
                label = if (choosingSnapshot) "Choosing…" else "Import restore…",
                onClick = {
                    scope.launch {
                        choosingSnapshot = true
                        error = null
                        message = null
                        runCatching { service.chooseAndRead() }
                            .onSuccess { selected ->
                                if (selected != null) {
                                    restoreSnapshot = selected
                                    message = "Loaded snapshot with ${selected.candidates.size} candidate(s)."
                                }
                            }
                            .onFailure { failure ->
                                error = failure.message ?: "Unable to read the environment snapshot."
                            }
                        choosingSnapshot = false
                    }
                },
                enabled = !choosingSnapshot && !state.isRefreshing,
            )
            ZephyrToolbarButton(
                label = when {
                    restoreSnapshot == null -> "Review restore"
                    restorePlan.isEmpty() -> "Already restored"
                    state.snapshotRestoreProgress.any { it.status == BatchItemStatus.Failed } ->
                        "Resume ${restorePlan.size} step(s)"
                    else -> "Restore ${restorePlan.size} step(s)"
                },
                onClick = {
                    viewModel.requestTransaction(SdkmanTransaction.SnapshotRestore(restorePlan))
                },
                enabled = restorePlan.isNotEmpty() && !state.isRefreshing,
            )
            ZephyrToolbarButton(
                label = "Capture baseline",
                onClick = {
                    baseline = captureEnvironmentSnapshot(state.candidates, currentEpochMillis())
                    message = "Baseline captured for this session."
                    error = null
                },
                enabled = current.candidates.isNotEmpty(),
            )
            ZephyrToolbarButton(
                label = if (exporting) "Exporting…" else "Export snapshot",
                onClick = {
                    scope.launch {
                        exporting = true
                        message = null
                        error = null
                        runCatching { service.chooseAndWrite(current) }
                            .onSuccess { result ->
                                if (result != null) {
                                    message = "Exported ${result.versionCount} version(s) to ${result.fileName}."
                                }
                            }
                            .onFailure { failure ->
                                error = failure.message ?: "Unable to export the environment snapshot."
                            }
                        exporting = false
                    }
                },
                enabled = !exporting && current.candidates.isNotEmpty(),
            )
        }
        message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        restoreSnapshot?.let {
            ZephyrPanel(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(metrics.panelPadding),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    PanelHeading(
                        "Restore preview",
                        if (restorePlan.isEmpty()) {
                            "This environment already matches every version and default in the imported snapshot."
                        } else {
                            "${restorePlan.count { command -> command.action == SdkmanCommandAction.Install }} install(s) and " +
                                "${restorePlan.count { command -> command.action == SdkmanCommandAction.SetDefault }} default change(s)"
                        },
                    )
                    restorePlan.forEach { command ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Badge(command.action.label, BadgeTone.Primary)
                            Text(
                                "${command.candidate} ${command.version}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                    if (state.snapshotRestoreProgress.isNotEmpty()) {
                        Text(
                            "Last run: " + state.snapshotRestoreProgress
                                .groupingBy { progress -> progress.status }
                                .eachCount()
                                .entries
                                .sortedBy { entry -> entry.key.ordinal }
                                .joinToString { entry -> "${entry.value} ${entry.key.label.lowercase()}" },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    ZephyrToolbarButton(
                        "Use imported as desired",
                        enabled = it.candidates.any { candidate -> candidate.installedVersions.isNotEmpty() },
                        onClick = {
                            onSettingsChange {
                                it.copy(desiredToolchainState = desiredStateFromSnapshot(restoreSnapshot!!))
                            }
                            message = "Imported snapshot is now the desired state."
                        },
                    )
                }
            }
        }
        if (current.candidates.isEmpty()) {
            EmptyState(
                "Nothing to capture",
                "Install a JDK or SDK with SDKMAN, then return to create an environment snapshot.",
                "Browse SDKs",
            ) {
                viewModel.navigate(ZephyrRoute.BrowseSdks)
            }
            return@Column
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(metrics.spacing),
        ) {
            ZephyrMetricTile(
                label = "Candidates",
                value = current.candidates.size.toString(),
                detail = "with installed versions or defaults",
                tone = StatusTone.Accent,
                modifier = Modifier.weight(1f),
            )
            ZephyrMetricTile(
                label = "Installed versions",
                value = versionCount.toString(),
                detail = "included in export",
                tone = StatusTone.Success,
                modifier = Modifier.weight(1f),
            )
            ZephyrMetricTile(
                label = "Baseline diff",
                value = if (baseline == null) "Not captured" else "${changes.size} changed",
                detail = if (baseline == null) "Capture to compare during this session" else "stable candidate-level comparison",
                tone = if (changes.isEmpty()) StatusTone.Success else StatusTone.Warning,
                modifier = Modifier.weight(1f),
            )
        }
        ZephyrPanel(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(metrics.panelPadding),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Desired-state baseline", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Persist this snapshot’s candidate/version identifiers without retaining a source file path.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ZephyrToolbarButton(
                    "Use current as desired",
                    onClick = {
                        onSettingsChange {
                            it.copy(desiredToolchainState = desiredStateFromSnapshot(current))
                        }
                        message = "Current environment is now the desired state."
                    },
                )
            }
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(metrics.spacing),
        ) {
            items(current.candidates, key = { it.candidate }) { candidate ->
                val change = changes.firstOrNull { it.candidate == candidate.candidate }
                ZephyrPanel(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(metrics.panelPadding),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(displayNameFor(candidate.candidate), fontWeight = FontWeight.SemiBold)
                                Text(
                                    candidate.candidate,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            candidate.defaultVersion?.let { Badge("Default $it", BadgeTone.Primary) }
                            Badge("${candidate.installedVersions.size} installed", BadgeTone.Success)
                        }
                        Text(
                            candidate.installedVersions.joinToString("  •  "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        change?.let {
                            val summary = buildList {
                                if (it.previousDefault != it.currentDefault) {
                                    add("default ${it.previousDefault ?: "none"} → ${it.currentDefault ?: "none"}")
                                }
                                if (it.addedVersions.isNotEmpty()) add("added ${it.addedVersions.joinToString()}")
                                if (it.removedVersions.isNotEmpty()) add("removed ${it.removedVersions.joinToString()}")
                            }
                            Text(
                                summary.joinToString(" • "),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.tertiary,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun CandidateComparisonScreen(
    state: ZephyrUiState.Ready,
    viewModel: ZephyrViewModel,
) {
    val metrics = LocalZephyrMetrics.current
    val candidates = state.candidates.filter { it.installedVersions.size >= 2 }
    val candidateKeys = candidates.map { it.name }
    var candidateName by remember(candidateKeys) { mutableStateOf(candidateKeys.firstOrNull()) }
    val candidate = candidates.firstOrNull { it.name == candidateName }
    val versions = candidate?.installedVersions.orEmpty()
    var selected by remember(candidateName, versions) {
        mutableStateOf(versions.take(2).map { it.version }.toSet())
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(metrics.spacing * 2),
    ) {
        PageTitle(
            "Candidate Comparison",
            "Select two or more versions and compare runtime, availability, and safety status.",
        )
        if (candidates.isEmpty()) {
            EmptyState(
                "Nothing to compare",
                "At least one candidate needs two loaded versions. Open Browse or a candidate detail first.",
                "Browse SDKs",
            ) {
                viewModel.navigate(ZephyrRoute.BrowseSdks)
            }
            return@Column
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            candidates.forEach { item ->
                ZephyrToolbarButton(
                    label = item.displayName,
                    detail = item.installedVersions.size.toString(),
                    onClick = { candidateName = item.name },
                )
            }
        }
        ZephyrPanel(Modifier.fillMaxWidth()) {
            FlowRow(
                modifier = Modifier.fillMaxWidth().padding(metrics.panelPadding),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                versions.forEach { version ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = version.version in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + version.version else selected - version.version
                            },
                        )
                        Text(version.version)
                    }
                }
            }
        }
        if (selected.size < 2) {
            EmptyState(
                "Select at least two versions",
                "Comparison remains hidden until two or more versions are selected.",
                "Select first two",
            ) {
                selected = versions.take(2).map { it.version }.toSet()
            }
            return@Column
        }
        val rows = candidate?.comparisonRows(selected, state.protectedVersions).orEmpty()
        ZephyrPanel(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().padding(metrics.panelPadding)) {
                ComparisonTableRow(
                    values = listOf("Version", "Vendor", "Installed", "Default", "Available", "Local-only", "Protected"),
                    header = true,
                )
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(rows, key = VersionComparisonRow::version) { row ->
                        ComparisonTableRow(
                            values = listOf(
                                row.version,
                                row.vendor,
                                row.installed.yesNo(),
                                row.default.yesNo(),
                                row.available.yesNo(),
                                row.localOnly.yesNo(),
                                row.protected.yesNo(),
                            ),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ComparisonTableRow(
    values: List<String>,
    header: Boolean = false,
) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        if (maxWidth < 900.dp * zephyrContentScale()) {
            if (!header) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(values.first(), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val labels = listOf("Version", "Vendor", "Installed", "Default", "Available", "Local-only", "Protected")
                        values.drop(1).forEachIndexed { index, value ->
                            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                Text(labels[index + 1], style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text(value, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                values.forEachIndexed { index, value ->
                    Text(
                        value,
                        modifier = Modifier.weight(if (index < 2) 1.45f else 1f),
                        style = if (header) MaterialTheme.typography.labelMedium else MaterialTheme.typography.bodySmall,
                        color = if (header) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                        fontWeight = if (header || index == 0) FontWeight.SemiBold else FontWeight.Normal,
                    )
                }
            }
        }
    }
}

private fun Boolean.yesNo(): String = if (this) "Yes" else "No"

private fun batchStatusTone(status: BatchItemStatus): BadgeTone =
    when (status) {
        BatchItemStatus.Succeeded -> BadgeTone.Success
        BatchItemStatus.Failed -> BadgeTone.Error
        BatchItemStatus.Skipped -> BadgeTone.Warning
        BatchItemStatus.Running -> BadgeTone.Primary
        BatchItemStatus.Pending -> BadgeTone.Neutral
    }

@Composable
internal fun DiagnosticsScreen(
    state: ZephyrUiState.Ready,
    onRunConnectionDiagnostics: () -> Unit,
    onRefreshIntegrity: () -> Unit,
    onExportDiagnostics: () -> Unit,
) {
    val metrics = LocalZephyrMetrics.current
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(metrics.spacing * 2),
    ) {
        item {
            ZephyrRecordLayout(
                modifier = Modifier.fillMaxWidth(),
                content = { PageTitle("Diagnostics", "Inspect the SDKMAN integration without changing your environment.") },
            ) {
                ZephyrToolbarButton(
                    label = if (state.connectivityStatus.state == ConnectivityState.Checking) "Checking…" else "Run connection diagnostic",
                    onClick = onRunConnectionDiagnostics,
                    enabled = state.connectivityStatus.state != ConnectivityState.Checking,
                )
                ZephyrToolbarButton(
                    label = if (state.diagnosticsExportInProgress) "Exporting…" else "Export support bundle",
                    onClick = onExportDiagnostics,
                    enabled = !state.diagnosticsExportInProgress,
                )
            }
        }
        item {
            ZephyrPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(metrics.panelPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PanelHeading("Installation", "Local SDKMAN environment")
                    DiagnosticRow("SDKMAN home", state.sdkmanStatus.home ?: "Unavailable", state.sdkmanStatus.home != null)
                    DiagnosticRow("CLI version", sdkmanVersionLabel(state), state.sdkmanStatus.cliVersion != null)
                    DiagnosticRow(
                        "SDKMAN service",
                        state.connectivityStatus.state.label,
                        state.connectivityStatus.state == ConnectivityState.Online,
                    )
                    state.connectivityStatus.diagnostic?.let { diagnostic ->
                        DiagnosticRow("Connection route", diagnostic.route.label, true)
                        DiagnosticRow(
                            "Last result",
                            diagnostic.outcome.label,
                            diagnostic.outcome == com.worxbend.zephyr.domain.ConnectivityOutcome.Online,
                        )
                        DiagnosticRow("Latency", "${diagnostic.latencyMillis} ms", true)
                        DiagnosticRow(
                            "Checked",
                            formatLocalTimestamp(diagnostic.checkedAtEpochMillis),
                            true,
                        )
                    }
                    DiagnosticRow("Installed candidates", state.candidates.size.toString(), true)
                    DiagnosticRow(
                        "Persisted default JDK",
                        state.candidates.firstOrNull { it.name == "java" }?.defaultVersion ?: "Not configured",
                        state.candidates.firstOrNull { it.name == "java" }?.defaultVersion != null,
                    )
                }
            }
        }
        item {
            ZephyrPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(metrics.panelPadding), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    PanelHeading("Remote metadata", "Catalog and updater state")
                    DiagnosticRow("Catalog packages", state.catalog.size.toString(), state.catalog.isNotEmpty())
                    DiagnosticRow("Metadata", metadataShortLabel(state.sdkmanStatus.metadataStatus), state.sdkmanStatus.metadataStatus !is CandidateMetadataStatus.Failed)
                    DiagnosticRow("SDKMAN update", selfUpdateShortLabel(state.sdkmanStatus.selfUpdateStatus), state.sdkmanStatus.selfUpdateStatus !is SdkmanSelfUpdateStatus.Failed)
                    DiagnosticRow(
                        "Local-only findings",
                        state.candidates.sumOf { it.localOnlyVersionCount }.toString(),
                        state.candidates.none { it.hasLocalOnlyVersions },
                    )
                }
            }
        }
        item {
            ZephyrPanel(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(metrics.panelPadding), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    ZephyrRecordLayout(
                        modifier = Modifier.fillMaxWidth(),
                        content = { PanelHeading("Integrity checks", "Filesystem and SDKMAN runtime boundaries") },
                    ) {
                        ZephyrToolbarButton("Run again", onClick = onRefreshIntegrity, enabled = !state.isRefreshing)
                    }
                    if (state.integrityChecks.isEmpty()) {
                        Text("Integrity checks are not available.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        state.integrityChecks.forEach { check -> IntegrityCheckRow(check) }
                    }
                }
            }
        }
        item {
            Text(
                "Diagnostics are read-only. Refresh, update, cleanup, and repair actions remain explicit elsewhere.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun IntegrityCheckRow(check: IntegrityCheck) {
    val tone = when (check.status) {
        IntegrityStatus.Passed -> StatusTone.Success
        IntegrityStatus.Warning -> StatusTone.Warning
        IntegrityStatus.Failed -> StatusTone.Error
    }
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusDot(tone, Modifier.padding(top = 6.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(check.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
                Badge(check.status.label, when (check.status) {
                    IntegrityStatus.Passed -> BadgeTone.Success
                    IntegrityStatus.Warning -> BadgeTone.Warning
                    IntegrityStatus.Failed -> BadgeTone.Error
                })
            }
            Text(
                check.detail,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
internal fun OperationHistoryScreen(
    state: ZephyrUiState.Ready,
    viewModel: ZephyrViewModel,
) {
    val metrics = LocalZephyrMetrics.current
    var query by remember { mutableStateOf("") }
    val entries = state.operationJournal.searchOperationJournal(query)

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(metrics.spacing * 2),
    ) {
        PageTitle(
            "Task Center",
            "Review durable SDKMAN tasks, verified step outcomes, interruptions, and safe resume plans.",
        )
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalArrangement = Arrangement.spacedBy(metrics.spacing),
        ) {
            SearchField(query, { query = it }, "Search operations", Modifier.width(320.dp))
            Text(
                "${entries.size} of ${state.operationJournal.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ZephyrToolbarButton(
                label = if (state.journalExportInProgress) "Exporting…" else "Export CSV",
                onClick = viewModel::exportJournal,
                enabled = state.operationJournal.isNotEmpty() && !state.journalExportInProgress,
            )
        }
        when {
            state.operationJournal.isEmpty() -> EmptyState(
                title = "No operations yet",
                text = "Confirmed installs, default changes, removals, and maintenance actions will appear here.",
                action = "Open Update Center",
                onAction = { viewModel.navigate(ZephyrRoute.UpdateCenter) },
            )
            entries.isEmpty() -> EmptyState(
                title = "No matching operations",
                text = "No journal entries match \"$query\".",
                action = "Clear search",
                onAction = { query = "" },
            )
            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(metrics.spacing),
            ) {
                items(entries, key = { it.id }) { entry ->
                    OperationJournalCard(
                        entry = entry,
                        onRecoveryAction = { action -> viewModel.handleRecoveryAction(entry, action) },
                        onResume = { viewModel.requestResumeOperation(entry.id) },
                    )
                }
            }
        }
    }
}

@Composable
private fun OperationJournalCard(
    entry: OperationJournalEntry,
    onRecoveryAction: (RecoveryAction) -> Unit,
    onResume: () -> Unit,
) {
    val metrics = LocalZephyrMetrics.current
    val tone = when (entry.status) {
        OperationStatus.Running -> StatusTone.Accent
        OperationStatus.Succeeded -> StatusTone.Success
        OperationStatus.Failed -> StatusTone.Error
        OperationStatus.Interrupted -> StatusTone.Warning
        OperationStatus.Indeterminate -> StatusTone.Warning
    }
    ZephyrPanel(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(metrics.panelPadding),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ZephyrRecordLayout(
                modifier = Modifier.fillMaxWidth(),
                content = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatusDot(tone)
                        Text(
                            entry.transaction.title.removeSuffix("?"),
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                },
            ) {
                Text(
                    formatLocalTimestamp(entry.startedAtEpochMillis),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Badge(
                    entry.status.label,
                    when (entry.status) {
                        OperationStatus.Succeeded -> BadgeTone.Success
                        OperationStatus.Failed -> BadgeTone.Error
                        OperationStatus.Running -> BadgeTone.Primary
                        OperationStatus.Interrupted, OperationStatus.Indeterminate -> BadgeTone.Warning
                    },
                )
            }
            entry.steps.sortedBy { it.index }.forEach { step ->
                FlowRow(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                    verticalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    Text(
                        "${step.index + 1}.",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Badge(step.command.action.label, BadgeTone.Primary)
                    step.command.candidate?.let { Badge(it) }
                    step.command.version?.let { Badge(it) }
                    Badge(
                        step.status.label,
                        when (step.status) {
                            com.worxbend.zephyr.domain.OperationStepStatus.Succeeded -> BadgeTone.Success
                            com.worxbend.zephyr.domain.OperationStepStatus.Failed,
                            com.worxbend.zephyr.domain.OperationStepStatus.Indeterminate,
                            -> BadgeTone.Warning
                            else -> BadgeTone.Neutral
                        },
                    )
                    step.outcome?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            entry.outcome?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (entry.status == OperationStatus.Failed) {
                val guidance = entry.transaction.recoveryGuidance()
                Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(
                        guidance.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                    guidance.steps.forEach { step ->
                        Text(
                            "• $step",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(7.dp),
                        verticalArrangement = Arrangement.spacedBy(7.dp),
                    ) {
                        guidance.actions.forEach { action ->
                            ZephyrToolbarButton(action.label, onClick = { onRecoveryAction(action) })
                        }
                    }
                }
            }
            if (entry.resumableCommands().isNotEmpty()) {
                ZephyrToolbarButton(
                    label = "Review remaining ${entry.resumableCommands().size}",
                    onClick = onResume,
                )
            }
        }
    }
}

private fun ZephyrViewModel.handleRecoveryAction(
    entry: OperationJournalEntry,
    action: RecoveryAction,
) {
    when (action) {
        RecoveryAction.Retry -> retryTransaction(entry.transaction)
        RecoveryAction.RefreshInstalled -> refreshInstalled()
        RecoveryAction.RefreshMetadata -> requestTransaction(SdkmanTransaction.RefreshMetadata)
        RecoveryAction.ScanLocalOnly -> scanLocalOnly()
        RecoveryAction.OpenDiagnostics -> navigate(ZephyrRoute.Diagnostics)
    }
}

@Composable
internal fun SettingsScreen(
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
) {
    val proxyService = remember { createProxyConfigurationService() }
    val sdkmanHomeService = remember { createSdkmanHomeConfigurationService() }
    val portablePreferencesService = remember { createPortablePreferencesService() }
    val scope = rememberCoroutineScope()
    var proxyConfiguration by remember { mutableStateOf(ProxyConfiguration()) }
    var proxyPort by remember { mutableStateOf("8080") }
    var proxyPassword by remember { mutableStateOf("") }
    var proxyMessage by remember { mutableStateOf<String?>(null) }
    var customSdkmanHome by remember { mutableStateOf<String?>(null) }
    var sdkmanHomeMessage by remember { mutableStateOf<String?>(null) }
    var portablePreferencesMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(proxyService) {
        proxyConfiguration = proxyService.load()
        proxyPort = proxyConfiguration.port.toString()
        customSdkmanHome = sdkmanHomeService.configuredPath()
    }
    ZephyrScrollPane(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(max = 920.dp)
            .fillMaxWidth(),
        spacing = 28.dp,
    ) {
        PageTitle("Settings", "Personalize Zephyr. Changes are saved for this desktop user.")
        SettingsGroup("Appearance", "Workbench colors and information density") {
            ZephyrSettingsRow(
                title = "Theme",
                description = "Follow the Linux desktop or use an explicit light or dark theme.",
            ) {
                ZephyrSegmentedControl(
                    options = ThemePreference.entries,
                    selected = settings.themePreference,
                    label = ThemePreference::label,
                    onSelected = { selected -> onSettingsChange { it.copy(themePreference = selected) } },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ZephyrSettingsRow(
                title = "UI density",
                description = "Compact fits more information; Comfortable adds spacing and larger controls.",
            ) {
                ZephyrSegmentedControl(
                    options = UiDensity.entries,
                    selected = settings.uiDensity,
                    label = UiDensity::label,
                    onSelected = { selected -> onSettingsChange { it.copy(uiDensity = selected) } },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ZephyrSettingsRow(
                title = "Text scale",
                description = "Scale all application text and fixed-height controls from 100% to 200%.",
            ) {
                ZephyrSegmentedControl(
                    options = TextScale.entries,
                    selected = settings.textScale,
                    label = TextScale::label,
                    onSelected = { selected -> onSettingsChange { it.copy(textScale = selected) } },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ZephyrSettingsRow(
                title = "Motion",
                description = "Follow the desktop preference or explicitly use full or reduced motion.",
            ) {
                ZephyrSegmentedControl(
                    options = MotionPreference.entries,
                    selected = settings.motionPreference,
                    label = MotionPreference::label,
                    onSelected = { selected -> onSettingsChange { it.copy(motionPreference = selected) } },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ZephyrSettingsRow(
                title = "Navigation width",
                description = if (settings.navigationWidthDp == 0) {
                    "Using the density-aware default. Drag the sidebar divider to resize."
                } else {
                    "${settings.navigationWidthDp} dp. Drag the sidebar divider to resize."
                },
            ) {
                ZephyrToolbarButton(
                    label = "Reset width",
                    onClick = { onSettingsChange { it.copy(navigationWidthDp = 0) } },
                    enabled = settings.navigationWidthDp != 0,
                )
            }
        }
        SettingsGroup("Automation", "Opt-in background metadata maintenance") {
            ZephyrSettingsRow(
                title = "Metadata refresh",
                description = "Refresh the SDKMAN catalog only while Zephyr is open and idle.",
            ) {
                ZephyrSegmentedControl(
                    options = MetadataRefreshSchedule.entries,
                    selected = settings.metadataRefreshSchedule,
                    label = MetadataRefreshSchedule::label,
                    onSelected = { selected ->
                        onSettingsChange { it.copy(metadataRefreshSchedule = selected) }
                    },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ZephyrSettingsRow(
                title = "Update notifications",
                description = "Show path-free desktop notices for available updates or every completed check.",
            ) {
                ZephyrSegmentedControl(
                    options = UpdateNotificationPolicy.entries,
                    selected = settings.updateNotificationPolicy,
                    label = UpdateNotificationPolicy::label,
                    onSelected = { selected ->
                        onSettingsChange { it.copy(updateNotificationPolicy = selected) }
                    },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ZephyrSettingsRow(
                title = "Operation notifications",
                description = "Show path-free desktop notices when reviewed operations finish.",
            ) {
                ZephyrSegmentedControl(
                    options = OperationNotificationPolicy.entries,
                    selected = settings.operationNotificationPolicy,
                    label = OperationNotificationPolicy::label,
                    onSelected = { selected ->
                        onSettingsChange { it.copy(operationNotificationPolicy = selected) }
                    },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ZephyrSettingsRow(
                title = "Local-only grace period",
                description = "Flag versions for review after this age; Zephyr never deletes them automatically.",
            ) {
                ZephyrSegmentedControl(
                    options = CleanupGracePeriod.entries,
                    selected = settings.cleanupGracePeriod,
                    label = CleanupGracePeriod::label,
                    onSelected = { selected ->
                        onSettingsChange { it.copy(cleanupGracePeriod = selected) }
                    },
                )
            }
        }
        SettingsGroup("Privacy", "Control machine-specific information in the application chrome") {
            ZephyrSettingsRow(
                title = "Show SDKMAN home path",
                description = "Display the local SDKMAN path in the toolbar and status bar.",
            ) {
                ZephyrToggle(
                    checked = settings.showSdkmanHome,
                    onCheckedChange = { visible -> onSettingsChange { it.copy(showSdkmanHome = visible) } },
                )
            }
        }
        SettingsGroup(
            "SDKMAN installation",
            "Choose an explicit SDKMAN home only when automatic discovery is not appropriate.",
        ) {
            KeyValueRow("Active after restart", customSdkmanHome ?: "Automatic discovery")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ZephyrToolbarButton(
                    label = "Choose SDKMAN home…",
                    onClick = {
                        scope.launch {
                            sdkmanHomeService.chooseAndSave()?.let { result ->
                                sdkmanHomeMessage = result.message
                                if (result.success) customSdkmanHome = result.path
                            }
                        }
                    },
                )
                ZephyrToolbarButton(
                    label = "Use automatic discovery",
                    onClick = {
                        scope.launch {
                            val result = sdkmanHomeService.clear()
                            sdkmanHomeMessage = result.message
                            customSdkmanHome = null
                        }
                    },
                    enabled = customSdkmanHome != null,
                )
            }
            sdkmanHomeMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Selection is accepted only when bin/sdkman-init.sh and candidates/ are present.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsGroup(
            "Enterprise proxy",
            "Coordinates stay in preferences; passwords use Linux Secret Service and never enter Zephyr settings.",
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = proxyConfiguration.enabled,
                    onCheckedChange = { proxyConfiguration = proxyConfiguration.copy(enabled = it) },
                )
                Text("Use proxy for SDKMAN network commands", modifier = Modifier.weight(1f))
            }
            if (proxyConfiguration.hasStoredPassword) {
                Badge("Password stored securely", BadgeTone.Success)
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = proxyConfiguration.host,
                    onValueChange = { proxyConfiguration = proxyConfiguration.copy(host = it.take(255)) },
                    label = { Text("Host") },
                    singleLine = true,
                    modifier = Modifier.width(280.dp * zephyrContentScale()),
                )
                OutlinedTextField(
                    value = proxyPort,
                    onValueChange = { proxyPort = it.filter(Char::isDigit).take(5) },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.width(120.dp * zephyrContentScale()),
                )
                OutlinedTextField(
                    value = proxyConfiguration.username,
                    onValueChange = { proxyConfiguration = proxyConfiguration.copy(username = it.take(128)) },
                    label = { Text("Username (optional)") },
                    singleLine = true,
                    modifier = Modifier.width(280.dp * zephyrContentScale()),
                )
                OutlinedTextField(
                    value = proxyPassword,
                    onValueChange = { proxyPassword = it.take(512) },
                    label = { Text(if (proxyConfiguration.hasStoredPassword) "New password (optional)" else "Password (optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.width(280.dp * zephyrContentScale()),
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ZephyrToolbarButton(
                    label = "Save proxy",
                    onClick = {
                        val port = proxyPort.toIntOrNull() ?: 0
                        scope.launch {
                            val result = proxyService.save(
                                proxyConfiguration.copy(port = port),
                                proxyPassword.takeIf(String::isNotEmpty),
                            )
                            proxyMessage = result.message
                            if (result.success) {
                                proxyPassword = ""
                                proxyConfiguration = proxyService.load()
                                proxyPort = proxyConfiguration.port.toString()
                            }
                        }
                    },
                )
                ZephyrToolbarButton(
                    label = "Clear password",
                    onClick = {
                        scope.launch {
                            val result = proxyService.clearPassword()
                            proxyMessage = result.message
                            proxyConfiguration = proxyService.load()
                        }
                    },
                    enabled = proxyConfiguration.hasStoredPassword,
                )
            }
            proxyMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SettingsGroup(
            "Portable preferences",
            "Move non-sensitive appearance, workflow, favorites, profiles, and filter choices between Zephyr installations.",
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ZephyrToolbarButton(
                    label = "Export preferences…",
                    onClick = {
                        scope.launch {
                            runCatching {
                                portablePreferencesService.chooseAndWrite(settings.portablePreferences())
                            }.onSuccess { file ->
                                if (file != null) portablePreferencesMessage = "Exported portable preferences to $file."
                            }.onFailure {
                                portablePreferencesMessage = it.message ?: "Preferences export failed."
                            }
                        }
                    },
                )
                ZephyrToolbarButton(
                    label = "Import preferences…",
                    onClick = {
                        scope.launch {
                            runCatching { portablePreferencesService.chooseAndRead() }
                                .onSuccess { portable ->
                                    if (portable != null) {
                                        onSettingsChange { it.applyPortablePreferences(portable) }
                                        portablePreferencesMessage = "Imported portable preferences."
                                    }
                                }
                                .onFailure {
                                    portablePreferencesMessage = it.message ?: "Preferences import failed."
                                }
                        }
                    },
                )
            }
            Text(
                "Excluded by design: machine paths, proxy settings and passwords, local observations, caches, and operation history.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            portablePreferencesMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SettingsGroup("Keyboard shortcuts", "Use Zephyr without leaving the keyboard") {
            keyboardShortcutHelp.forEach { shortcut ->
                ZephyrRecordLayout(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    content = { Text(shortcut.description, style = MaterialTheme.typography.bodyMedium) },
                    actions = { ZephyrKeycap(shortcut.keys) },
                )
            }
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PanelHeading(title, description)
        ZephyrPanel(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(LocalZephyrMetrics.current.panelPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}

@Composable
internal fun AboutScreen(state: ZephyrUiState.Ready) {
    val metrics = LocalZephyrMetrics.current
    Column(
        modifier = Modifier.fillMaxHeight().widthIn(max = 840.dp).fillMaxWidth().verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(metrics.spacing * 2),
    ) {
        PageTitle("About Zephyr", "A focused desktop control center for SDKMAN.")
        ZephyrPanel(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.padding(metrics.panelPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                CandidateIcon(CandidateKind.Jdk)
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Zephyr", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                    Text("Version 1.2.1", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Kotlin Multiplatform + Compose Desktop for Linux", style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        ZephyrPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(metrics.panelPadding), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PanelHeading("Runtime", "Connected local environment")
                KeyValueRow("SDKMAN", sdkmanVersionLabel(state))
                KeyValueRow("Installation", state.sdkmanStatus.home ?: "Unavailable")
                KeyValueRow("License", "MIT")
            }
        }
        ZephyrPanel(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(metrics.panelPadding), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                PanelHeading("Project", "Open-source and designed for safe local toolchain management")
                LinkText("SDKMAN: https://sdkman.io/")
                Text(
                    "Zephyr delegates package management to SDKMAN, validates command and filesystem boundaries, and keeps destructive cleanup behind explicit review.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PanelHeading(title: String, detail: String) {
    ZephyrSectionHeading(title = title, detail = detail)
}

@Composable
private fun KeyValueRow(label: String, value: String) {
    BoxWithConstraints(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        if (maxWidth < 520.dp * zephyrContentScale()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.bodyMedium)
            }
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                Text(label, modifier = Modifier.weight(0.4f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, modifier = Modifier.weight(0.6f), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun HealthRow(label: String, healthy: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
        StatusDot(if (healthy) StatusTone.Success else StatusTone.Warning)
        Text(label)
    }
}

@Composable
private fun DiagnosticRow(label: String, value: String, healthy: Boolean) {
    ZephyrRecordLayout(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        content = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusDot(if (healthy) StatusTone.Success else StatusTone.Warning)
                Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
        },
    ) {
        Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        CopyTextButton(value)
    }
}
