package com.worxbend.zephyr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.worxbend.zephyr.data.formatLocalTimestamp
import com.worxbend.zephyr.domain.OperationJournalEntry
import com.worxbend.zephyr.domain.OperationStatus
import com.worxbend.zephyr.domain.RecoveryAction
import com.worxbend.zephyr.domain.hasIndeterminateSteps
import com.worxbend.zephyr.domain.recoveryGuidance
import com.worxbend.zephyr.domain.resumableCommands
import com.worxbend.zephyr.domain.searchOperationJournal
import com.worxbend.zephyr.viewmodel.ZephyrUiState

@Composable
internal fun OperationHistoryScreen(
    state: ZephyrUiState.Ready,
    onExport: () -> Unit,
    onOpenUpdateCenter: () -> Unit,
    onRecovery: (OperationJournalEntry, RecoveryAction) -> Unit,
    onResume: (Long) -> Unit,
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
                onClick = onExport,
                enabled = state.operationJournal.isNotEmpty() && !state.journalExportInProgress,
            )
        }
        when {
            state.operationJournal.isEmpty() -> EmptyState(
                title = "No operations yet",
                text = "Confirmed installs, default changes, removals, and maintenance actions will appear here.",
                action = "Open Update Center",
                onAction = { onOpenUpdateCenter() },
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
                        onRecoveryAction = { action -> onRecovery(entry, action) },
                        onResume = { onResume(entry.id) },
                        isLive = entry.id in state.liveOperationIds,
                    )
                }
            }
        }
    }
}

@Composable
internal fun OperationJournalCard(
    entry: OperationJournalEntry,
    onRecoveryAction: (RecoveryAction) -> Unit,
    onResume: () -> Unit,
    isLive: Boolean = false,
) {
    val metrics = LocalZephyrMetrics.current
    val canReview = !isLive && entry.status != OperationStatus.Running
    val resumableCommands = entry.resumableCommands()
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
                            ZephyrToolbarButton(action.label, onClick = { onRecoveryAction(action) }, enabled = canReview)
                        }
                    }
                }
            }
            if (entry.status == OperationStatus.Indeterminate || entry.hasIndeterminateSteps()) {
                ZephyrToolbarButton(
                    label = "Verify outcome",
                    onClick = onResume,
                    enabled = canReview,
                )
            }
            if (resumableCommands.isNotEmpty()) {
                ZephyrToolbarButton(
                    label = "Review remaining ${resumableCommands.size}",
                    onClick = onResume,
                    enabled = canReview,
                )
            }
        }
    }
}
