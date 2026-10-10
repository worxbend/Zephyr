package com.worxbend.zephyr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.worxbend.zephyr.data.diffEnvironmentSnapshots
import com.worxbend.zephyr.data.planSnapshotRestore
import com.worxbend.zephyr.domain.BatchItemStatus
import com.worxbend.zephyr.domain.SdkmanCommandAction
import com.worxbend.zephyr.domain.displayNameFor
import com.worxbend.zephyr.features.toolchains.EnvironmentSnapshotPresenter
import com.worxbend.zephyr.settings.AppSettings
import com.worxbend.zephyr.viewmodel.ZephyrUiState

@Composable
internal fun EnvironmentSnapshotScreen(
    state: ZephyrUiState.Ready,
    onReviewRestore: (List<com.worxbend.zephyr.domain.PlannedSdkmanCommand>) -> Unit,
    onBrowse: () -> Unit,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
) {
    val metrics = LocalZephyrMetrics.current
    val services = LocalAppServices.current
    val scope = rememberCoroutineScope()
    val presenter = remember(services) { EnvironmentSnapshotPresenter(services.environmentSnapshotService, scope) }
    DisposableEffect(presenter) { onDispose(presenter::close) }
    LaunchedEffect(state.candidates) { presenter.updateCandidates(state.candidates) }
    val workflow by presenter.state.collectAsState()
    val current = workflow.current
    val baseline = workflow.baseline
    val restoreSnapshot = workflow.restoreSnapshot
    val choosingSnapshot = workflow.choosing
    val exporting = workflow.exporting
    val message = workflow.message
    val error = workflow.error
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
                onClick = { presenter.importRestore() },
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
                    onReviewRestore(restorePlan)
                },
                enabled = restorePlan.isNotEmpty() && !state.isRefreshing,
            )
            ZephyrToolbarButton(
                label = "Capture baseline",
                onClick = { presenter.captureBaseline() },
                enabled = current.candidates.isNotEmpty(),
            )
            ZephyrToolbarButton(
                label = if (exporting) "Exporting…" else "Export snapshot",
                onClick = { presenter.export() },
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
                                it.copy(desiredToolchainState = desiredStateFromSnapshot(restoreSnapshot))
                            }
                            presenter.message("Imported snapshot is now the desired state.")
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
                onBrowse()
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
                        presenter.message("Current environment is now the desired state.")
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
