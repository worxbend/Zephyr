package com.worxbend.zephyr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.worxbend.zephyr.domain.InstallTarget
import com.worxbend.zephyr.features.toolchains.ProjectToolchainPresenter
import com.worxbend.zephyr.viewmodel.ZephyrUiState

@Composable
internal fun ProjectToolchainExportScreen(
    state: ZephyrUiState.Ready,
    onBrowse: () -> Unit,
) {
    val metrics = LocalZephyrMetrics.current
    val services = LocalAppServices.current
    val scope = rememberCoroutineScope()
    val presenter = remember(services) { ProjectToolchainPresenter(services.projectToolchainService, scope) }
    DisposableEffect(presenter) { onDispose(presenter::close) }
    val workflow by presenter.state.collectAsState()
    val defaults = state.candidates.mapNotNull { candidate ->
        candidate.defaultVersion?.let { version ->
            Triple(candidate.name, candidate.displayName, InstallTarget(candidate.name, version))
        }
    }
    val defaultIds = defaults.map { it.first }
    var selected by remember(defaultIds) { mutableStateOf(defaultIds.toSet()) }
    val exporting = workflow.busy
    val message = workflow.message
    val error = workflow.error

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
                onClick = { presenter.export(defaults.filter { it.first in selected }.map { it.third }) },
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
                onBrowse()
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
