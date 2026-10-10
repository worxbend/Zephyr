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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.worxbend.zephyr.domain.displayNameFor
import com.worxbend.zephyr.features.toolchains.ProjectToolchainPresenter
import com.worxbend.zephyr.viewmodel.ZephyrUiState

@Composable
internal fun ProjectToolchainImportScreen(
    state: ZephyrUiState.Ready,
) {
    val metrics = LocalZephyrMetrics.current
    val services = LocalAppServices.current
    val scope = rememberCoroutineScope()
    val presenter = remember(services) { ProjectToolchainPresenter(services.projectToolchainService, scope) }
    DisposableEffect(presenter) { onDispose(presenter::close) }
    val workflow by presenter.state.collectAsState()
    val document = workflow.document
    val loading = workflow.busy
    val error = workflow.error
    val diff = document?.let { compareProjectToolchain(it.targets, state.candidates) }.orEmpty()
    val chooseDocument: () -> Unit = presenter::chooseDocument
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
