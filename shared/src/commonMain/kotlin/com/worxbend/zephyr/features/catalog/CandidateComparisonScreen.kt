package com.worxbend.zephyr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.worxbend.zephyr.domain.BatchItemStatus
import com.worxbend.zephyr.viewmodel.ZephyrRoute
import com.worxbend.zephyr.viewmodel.ZephyrUiState
import com.worxbend.zephyr.viewmodel.ZephyrViewModel

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

internal fun batchStatusTone(status: BatchItemStatus): BadgeTone =
    when (status) {
        BatchItemStatus.Succeeded -> BadgeTone.Success
        BatchItemStatus.Failed -> BadgeTone.Error
        BatchItemStatus.Skipped -> BadgeTone.Warning
        BatchItemStatus.Running -> BadgeTone.Primary
        BatchItemStatus.Pending -> BadgeTone.Neutral
    }
