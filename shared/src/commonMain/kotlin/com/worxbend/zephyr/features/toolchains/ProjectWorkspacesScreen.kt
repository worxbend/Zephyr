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
import com.worxbend.zephyr.features.toolchains.ProjectWorkspacesPresenter
import com.worxbend.zephyr.settings.AppSettings
import com.worxbend.zephyr.viewmodel.ZephyrUiState
import kotlinx.coroutines.launch

@Composable
internal fun ProjectWorkspacesScreen(
    state: ZephyrUiState.Ready,
    onReviewMissing: (List<com.worxbend.zephyr.domain.InstallTarget>) -> Unit,
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
) {
    val metrics = LocalZephyrMetrics.current
    val services = LocalAppServices.current
    val scope = rememberCoroutineScope()
    val presenter = remember(services) { ProjectWorkspacesPresenter(services.projectToolchainService, services.terminalLauncher, scope) }
    DisposableEffect(presenter) { onDispose(presenter::close) }
    val workflow by presenter.state.collectAsState()
    val documents = workflow.documents
    val errors = workflow.errors
    val launchMessages = workflow.launchMessages
    val loading = workflow.loading
    val pinning = workflow.pinning
    LaunchedEffect(settings.projectWorkspaces) { presenter.refresh(settings.projectWorkspaces) }
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
                onClick = { presenter.refresh(settings.projectWorkspaces) },
                enabled = !loading && !pinning,
            )
            ZephyrToolbarButton(
                label = if (pinning) "Choosing…" else "Pin .sdkmanrc",
                onClick = { presenter.pin(onSettingsChange) },
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
            ) { presenter.pin(onSettingsChange) }
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
                                            onReviewMissing(missing)
                                        },
                                    )
                                }
                                ZephyrToolbarButton(
                                    "Open scoped terminal",
                                    enabled = missing.isEmpty() &&
                                        workspace.targets.isNotEmpty() &&
                                        !state.sdkmanStatus.home.isNullOrBlank(),
                                    onClick = { presenter.launch(state.sdkmanStatus.home.orEmpty(), workspace) },
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

private const val PIN_WORKSPACE_MESSAGE_KEY = ProjectWorkspacesPresenter.PIN_WORKSPACE_MESSAGE_KEY
