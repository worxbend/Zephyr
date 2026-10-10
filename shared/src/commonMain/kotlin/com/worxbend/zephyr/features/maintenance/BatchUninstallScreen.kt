package com.worxbend.zephyr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import com.worxbend.zephyr.domain.SdkmanTransaction
import com.worxbend.zephyr.viewmodel.ZephyrRoute
import com.worxbend.zephyr.viewmodel.ZephyrUiState
import com.worxbend.zephyr.viewmodel.ZephyrViewModel

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
