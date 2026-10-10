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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.worxbend.zephyr.data.formatLocalTimestamp
import com.worxbend.zephyr.domain.BatchItemStatus
import com.worxbend.zephyr.domain.CandidateKind
import com.worxbend.zephyr.domain.CandidateMetadataStatus
import com.worxbend.zephyr.domain.ConnectivityState
import com.worxbend.zephyr.domain.IntegrityCheck
import com.worxbend.zephyr.domain.IntegrityStatus
import com.worxbend.zephyr.domain.SdkmanSelfUpdateStatus
import com.worxbend.zephyr.domain.SdkmanTransaction
import com.worxbend.zephyr.domain.UpdateActivationTarget
import com.worxbend.zephyr.domain.calculateDesiredStateDrift
import com.worxbend.zephyr.domain.displayNameFor
import com.worxbend.zephyr.domain.javaProviderName
import com.worxbend.zephyr.settings.AppSettings
import com.worxbend.zephyr.viewmodel.ZephyrRoute
import com.worxbend.zephyr.viewmodel.ZephyrUiState
import com.worxbend.zephyr.viewmodel.ZephyrViewModel

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
    val browserLauncher = LocalAppServices.current.browserLauncher
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
                    Text("Version 1.2.3", color = MaterialTheme.colorScheme.onSurfaceVariant)
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
internal fun PanelHeading(title: String, detail: String) {
    ZephyrSectionHeading(title = title, detail = detail)
}

@Composable
internal fun KeyValueRow(label: String, value: String) {
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
