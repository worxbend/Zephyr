package com.worxbend.zephyr

import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.worxbend.zephyr.domain.CandidateKind
import com.worxbend.zephyr.domain.ProtectedVersion
import com.worxbend.zephyr.domain.SdkmanTransaction
import com.worxbend.zephyr.domain.StorageCleanupDisposition
import com.worxbend.zephyr.domain.StorageMeasurement
import com.worxbend.zephyr.domain.VersionStorage
import com.worxbend.zephyr.domain.formatByteSize
import com.worxbend.zephyr.features.maintenance.trustedCleanupVersionsByCandidate
import com.worxbend.zephyr.settings.CleanupGracePeriod
import com.worxbend.zephyr.viewmodel.ZephyrRoute
import com.worxbend.zephyr.viewmodel.ZephyrUiState
import com.worxbend.zephyr.viewmodel.ZephyrViewModel

@Composable
internal fun StorageCenterScreen(
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
internal fun LocalOnlyScreen(
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
                cleanupVersionsByCandidate = trustedCleanupVersionsByCandidate(state, items),
                onOpen = { candidate ->
                    onNavigate(if (candidate.kind == CandidateKind.Jdk) ZephyrRoute.JdkDetail(candidate.name) else ZephyrRoute.SdkDetail(candidate.name))
                },
                onClean = onClean,
            )
        }
    }
}
