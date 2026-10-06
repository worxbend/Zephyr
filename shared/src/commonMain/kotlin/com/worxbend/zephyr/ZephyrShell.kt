package com.worxbend.zephyr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import com.worxbend.zephyr.domain.CandidateKind
import com.worxbend.zephyr.domain.ActivityAction
import com.worxbend.zephyr.domain.ActivitySeverity
import com.worxbend.zephyr.domain.BatchItemStatus
import com.worxbend.zephyr.domain.ConnectivityState
import com.worxbend.zephyr.domain.DiskImpactEstimate
import com.worxbend.zephyr.domain.DiskImpactKind
import com.worxbend.zephyr.domain.PlannedSdkmanCommand
import com.worxbend.zephyr.domain.SdkmanTransaction
import com.worxbend.zephyr.domain.formatByteSize
import com.worxbend.zephyr.domain.copyableCommand
import com.worxbend.zephyr.domain.requiresNetwork
import com.worxbend.zephyr.actions.ZephyrActionHandler
import com.worxbend.zephyr.actions.ZephyrActionIds
import com.worxbend.zephyr.actions.validationError
import com.worxbend.zephyr.data.currentEpochMillis
import com.worxbend.zephyr.data.formatLocalTimestamp
import com.worxbend.zephyr.settings.AppSettings
import com.worxbend.zephyr.settings.MetadataRefreshSchedule
import com.worxbend.zephyr.settings.recordRecentCandidate
import com.worxbend.zephyr.settings.MAX_NAVIGATION_WIDTH_DP
import com.worxbend.zephyr.settings.MIN_NAVIGATION_WIDTH_DP
import kotlin.math.roundToInt
import com.worxbend.zephyr.viewmodel.ZephyrRoute
import com.worxbend.zephyr.viewmodel.ZephyrUiState
import com.worxbend.zephyr.viewmodel.ZephyrViewModel
import org.jetbrains.compose.resources.painterResource
import zephyr.shared.generated.resources.Res
import zephyr.shared.generated.resources.ic_arrow_left

private fun zephyrActionHandler(viewModel: ZephyrViewModel): ZephyrActionHandler =
    ZephyrActionHandler { request ->
        if (request.validationError() != null) {
            false
        } else {
            when (request.id) {
                ZephyrActionIds.RefreshInstalled -> viewModel.refreshInstalled()
                ZephyrActionIds.ScanLocalOnly -> viewModel.scanLocalOnly()
                ZephyrActionIds.RefreshConnectivity -> viewModel.refreshConnectivity()
                ZephyrActionIds.RunDiagnostics -> viewModel.refreshConnectivity()
                ZephyrActionIds.RefreshMetadata ->
                    viewModel.requestTransaction(SdkmanTransaction.RefreshMetadata)
                ZephyrActionIds.CheckSdkmanUpdates ->
                    viewModel.requestTransaction(SdkmanTransaction.SelfUpdate)
            }
            true
        }
    }

@Composable
internal fun ZephyrScreen(
    state: ZephyrUiState.Ready,
    viewModel: ZephyrViewModel,
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    darkTheme: Boolean,
    onToggleTheme: () -> Unit,
) {
    val metrics = LocalZephyrMetrics.current
    val defaultNavigationWidth = metrics.navigationWidth.value
    var navigationWidth by remember(settings.navigationWidthDp, defaultNavigationWidth) {
        mutableFloatStateOf(
            settings.navigationWidthDp.takeIf { it > 0 }?.toFloat() ?: defaultNavigationWidth,
        )
    }
    val displayDensity = LocalDensity.current.density
    var globalSearchOpen by remember { mutableStateOf(false) }
    var commandPaletteOpen by remember { mutableStateOf(false) }
    var navigationOverlayOpen by remember { mutableStateOf(false) }
    val activateSearchTarget: (GlobalSearchTarget) -> Unit = { target ->
        globalSearchOpen = false
        commandPaletteOpen = false
        when (target) {
            is GlobalSearchTarget.Navigate -> viewModel.navigate(target.route)
            is GlobalSearchTarget.Execute -> zephyrActionHandler(viewModel).handle(target.request)
        }
    }

    LaunchedEffect(state.route) {
        val candidate = when (val route = state.route) {
            is ZephyrRoute.JdkDetail -> route.candidate
            is ZephyrRoute.SdkDetail -> route.candidate
            else -> null
        }
        if (candidate != null) {
            onSettingsChange { it.recordRecentCandidate(candidate) }
        }
    }

    state.pendingTransaction?.let { transaction ->
        TransactionPreviewDialog(
            transaction = transaction,
            diskImpact = state.pendingTransactionDiskImpact,
            onConfirm = viewModel::confirmTransaction,
            onDismiss = viewModel::dismissTransaction,
        )
    }

    if (globalSearchOpen) {
        GlobalSearchDialog(
            state = state,
            onDismiss = { globalSearchOpen = false },
            onSelect = activateSearchTarget,
        )
    }
    if (commandPaletteOpen) {
        GlobalSearchDialog(
            state = state,
            mode = SearchOverlayMode.CommandPalette,
            onDismiss = { commandPaletteOpen = false },
            onSelect = activateSearchTarget,
        )
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .onPreviewKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && event.key == Key.Escape && navigationOverlayOpen) {
                    navigationOverlayOpen = false
                    true
                } else if (event.type != KeyEventType.KeyDown || (!event.isCtrlPressed && !event.isMetaPressed)) {
                    false
                } else {
                    val workspaceRoute = event.key.toWorkspaceShortcutKey()?.let { key ->
                        resolveWorkspaceShortcut(
                            key = key,
                            primaryPressed = true,
                            shiftPressed = event.isShiftPressed,
                        )
                    }
                    when {
                        event.key == Key.K && !event.isShiftPressed -> {
                            commandPaletteOpen = false
                            globalSearchOpen = true
                            true
                        }
                        event.key == Key.P && event.isShiftPressed -> {
                            globalSearchOpen = false
                            commandPaletteOpen = true
                            true
                        }
                        workspaceRoute != null -> {
                            activateSearchTarget(GlobalSearchTarget.Navigate(workspaceRoute))
                            true
                        }
                        event.key == Key.R && event.isShiftPressed -> {
                            activateSearchTarget(
                                GlobalSearchTarget.Execute(com.worxbend.zephyr.actions.ZephyrActionRequest(ZephyrActionIds.RefreshInstalled)),
                            )
                            true
                        }
                        event.key == Key.L && event.isShiftPressed -> {
                            activateSearchTarget(
                                GlobalSearchTarget.Execute(com.worxbend.zephyr.actions.ZephyrActionRequest(ZephyrActionIds.ScanLocalOnly)),
                            )
                            true
                        }
                        else -> false
                    }
                }
            },
    ) {
        val shellLayout = shellLayoutForWidth(maxWidth.value / zephyrContentScale())
        LaunchedEffect(shellLayout) {
            if (shellLayout.hasPersistentNavigation) navigationOverlayOpen = false
        }
        Column(Modifier.fillMaxSize()) {
            WorkbenchToolbar(
                state = state,
                layout = shellLayout,
                darkTheme = darkTheme,
                showSdkmanHome = settings.showSdkmanHome,
                onBack = viewModel::goBack,
                onToggleNavigation = { navigationOverlayOpen = !navigationOverlayOpen },
                onOpenSearch = { globalSearchOpen = true },
                onToggleActivity = viewModel::toggleActivityCenter,
                onToggleTheme = onToggleTheme,
                onRefresh = viewModel::refreshInstalled,
                onRefreshConnectivity = viewModel::refreshConnectivity,
                onRefreshMetadata = { viewModel.requestTransaction(SdkmanTransaction.RefreshMetadata) },
                onScan = viewModel::scanLocalOnly,
                onCheckUpdates = { viewModel.requestTransaction(SdkmanTransaction.SelfUpdate) },
            )
            Box(Modifier.weight(1f).fillMaxWidth()) {
                Row(Modifier.fillMaxSize()) {
                    if (shellLayout.hasPersistentNavigation) {
                        WorkbenchSidebar(
                            state = state,
                            width = navigationWidth.dp,
                            onNavigate = viewModel::navigate,
                        )
                        NavigationResizeHandle(
                            onDrag = { pixels ->
                                navigationWidth = (navigationWidth + pixels / displayDensity)
                                    .coerceIn(MIN_NAVIGATION_WIDTH_DP.toFloat(), MAX_NAVIGATION_WIDTH_DP.toFloat())
                            },
                            onDragFinished = {
                                onSettingsChange { it.copy(navigationWidthDp = navigationWidth.roundToInt()) }
                            },
                        )
                    }
                    Content(
                        state = state,
                        viewModel = viewModel,
                        settings = settings,
                        onSettingsChange = onSettingsChange,
                        onClean = { candidate, versions ->
                            viewModel.requestTransaction(SdkmanTransaction.CleanLocalOnly(candidate, versions))
                        },
                        onUninstall = { candidate, version ->
                            viewModel.requestTransaction(SdkmanTransaction.Uninstall(candidate, version))
                        },
                    )
                }
                if (shellLayout == ShellLayout.Narrow && navigationOverlayOpen) {
                    Box(
                        Modifier.fillMaxSize()
                            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.24f))
                            .semantics { contentDescription = "Close navigation" }
                            .clickable { navigationOverlayOpen = false },
                    )
                    Surface(
                        modifier = Modifier.fillMaxHeight().widthIn(max = 320.dp).fillMaxWidth().padding(end = 8.dp),
                        shape = MaterialTheme.shapes.large,
                        color = MaterialTheme.colorScheme.surface,
                        shadowElevation = 12.dp,
                    ) {
                        WorkbenchSidebar(
                            state = state,
                            width = 320.dp,
                            onNavigate = { route ->
                                navigationOverlayOpen = false
                                viewModel.navigate(route)
                            },
                        )
                    }
                }
            }
            WorkbenchStatusBar(
                state = state,
                compact = shellLayout != ShellLayout.Wide,
                showSdkmanHome = settings.showSdkmanHome,
                metadataRefreshSchedule = settings.metadataRefreshSchedule,
                onRetryFailedLocalOnlyReads = viewModel::retryFailedLocalOnlyReads,
            )
        }
    }

    if (state.activityCenterOpen) {
        ActivityCenterPanel(
            state = state,
            onDismissEvent = viewModel::dismissActivity,
            onAction = viewModel::handleActivityAction,
            onClose = viewModel::toggleActivityCenter,
        )
    }
    BusyOverlay(state)
    MessageOverlay(
        state = state,
        onDismiss = { eventId ->
            if (eventId == LEGACY_MESSAGE_EVENT_ID) viewModel.clearMessages() else viewModel.dismissActivity(eventId)
        },
        onAction = viewModel::handleActivityAction,
    )
}

@Composable
internal fun TransactionPreviewDialog(
    transaction: SdkmanTransaction,
    diskImpact: DiskImpactEstimate? = null,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        shape = MaterialTheme.shapes.large,
        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        modifier = Modifier.border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large),
        title = { Text(transaction.title, style = MaterialTheme.typography.titleLarge) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 440.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(LocalZephyrMetrics.current.panelPadding),
            ) {
                Text(transaction.description)
                Badge(
                    if (transaction.requiresNetwork) "Network required" else "Works offline",
                    if (transaction.requiresNetwork) BadgeTone.Warning else BadgeTone.Success,
                )
                diskImpact?.let { DiskImpactSummary(it) }
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        "Typed command plan",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    ZephyrPanel(Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .padding(LocalZephyrMetrics.current.panelPadding),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            transaction.commands.forEachIndexed { index, command ->
                                PlannedCommandRow(index + 1, command)
                            }
                        }
                    }
                    Text(
                        "Arguments are validated and passed through Zephyr's typed SDKMAN boundary.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            if (transaction.destructive) {
                ZephyrDestructiveButton(transaction.confirmationLabel, onConfirm)
            } else {
                Button(onClick = onConfirm) { Text(transaction.confirmationLabel) }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun DiskImpactSummary(estimate: DiskImpactEstimate) {
    val label = when (estimate.kind) {
        DiskImpactKind.Required -> "Required disk space"
        DiskImpactKind.Reclaimable -> "Reclaimable disk space"
        DiskImpactKind.None -> "Disk impact"
        DiskImpactKind.Unknown -> "Disk impact unavailable"
    }
    val value = when (estimate.kind) {
        DiskImpactKind.None -> "No material change"
        else -> estimate.bytes?.let(::formatByteSize) ?: "Not measurable"
    }
    ZephyrPanel(Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(LocalZephyrMetrics.current.panelPadding),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(label, fontWeight = FontWeight.SemiBold)
                Text(value, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    estimate.confidence.label,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                estimate.availableBytes?.let {
                    Text(
                        "${formatByteSize(it)} currently available",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Text(
                estimate.explanation,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun Key.toWorkspaceShortcutKey(): WorkspaceShortcutKey? =
    when (this) {
        Key.O -> WorkspaceShortcutKey.O
        Key.J -> WorkspaceShortcutKey.J
        Key.S -> WorkspaceShortcutKey.S
        Key.U -> WorkspaceShortcutKey.U
        Key.D -> WorkspaceShortcutKey.D
        Key.H -> WorkspaceShortcutKey.H
        else -> null
    }

@Composable
private fun PlannedCommandRow(
    step: Int,
    command: PlannedSdkmanCommand,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(7.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ZephyrKeycap(step.toString())
            Text(command.action.label, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            CopyTextButton(command.copyableCommand(), "Copy command")
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            command.candidate?.let { Badge(it, BadgeTone.Primary) }
            command.version?.let { Badge(it) }
        }
    }
}

@Composable
private fun WorkbenchToolbar(
    state: ZephyrUiState.Ready,
    layout: ShellLayout,
    darkTheme: Boolean,
    showSdkmanHome: Boolean,
    onBack: () -> Unit,
    onToggleNavigation: () -> Unit,
    onOpenSearch: () -> Unit,
    onToggleActivity: () -> Unit,
    onToggleTheme: () -> Unit,
    onRefresh: () -> Unit,
    onRefreshConnectivity: () -> Unit,
    onRefreshMetadata: () -> Unit,
    onScan: () -> Unit,
    onCheckUpdates: () -> Unit,
) {
    val metrics = LocalZephyrMetrics.current
    val busy = state.busyLabel() != null
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compactHeader = maxWidth < 600.dp * zephyrContentScale()
        Surface(
            modifier = Modifier.fillMaxWidth().height(metrics.toolbarHeight),
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shadowElevation = 0.dp,
        ) {
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = if (compactHeader) 6.dp else metrics.panelPadding),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (compactHeader) 4.dp else metrics.spacing),
            ) {
                if (layout == ShellLayout.Narrow) {
                    ZephyrToolbarButton("Menu", onClick = onToggleNavigation)
                }
                if (state.previousRoute != null) {
                    IconButton(onClick = onBack, modifier = Modifier.size(metrics.controlHeight)) {
                        Icon(
                            painter = painterResource(Res.drawable.ic_arrow_left),
                            contentDescription = "Back",
                            modifier = Modifier.size(18.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        headerTitle(state),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!compactHeader) {
                        Text(
                            if (showSdkmanHome) state.sdkmanStatus.home.orEmpty() else sdkmanVersionLabel(state),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.MiddleEllipsis,
                        )
                    }
                }
                if (layout == ShellLayout.Wide) {
                    GlobalSearchButton(onClick = onOpenSearch)
                } else {
                    ZephyrToolbarButton("Find", onClick = onOpenSearch)
                }
                val unreadActivity = state.activityEvents.count { !it.acknowledged }
                if (!compactHeader) {
                    ZephyrToolbarButton(
                        label = "Activity",
                        detail = unreadActivity.takeIf { it > 0 }?.toString(),
                        onClick = onToggleActivity,
                    )
                }
                ToolbarOverflow(
                    showActivity = compactHeader,
                    onToggleActivity = onToggleActivity,
                    darkTheme = darkTheme,
                    networkLabel = state.connectivityStatus.state.label,
                    metadataLabel = metadataShortLabel(state.sdkmanStatus.metadataStatus),
                    updateLabel = selfUpdateShortLabel(state.sdkmanStatus.selfUpdateStatus),
                    busy = busy,
                    connectivityChecking = state.connectivityStatus.state == ConnectivityState.Checking,
                    onToggleTheme = onToggleTheme,
                    onRefresh = onRefresh,
                    onRefreshConnectivity = onRefreshConnectivity,
                    onRefreshMetadata = onRefreshMetadata,
                    onScan = onScan,
                    onCheckUpdates = onCheckUpdates,
                )
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
}

@Composable
private fun ToolbarOverflow(
    showActivity: Boolean,
    onToggleActivity: () -> Unit,
    darkTheme: Boolean,
    networkLabel: String,
    metadataLabel: String,
    updateLabel: String,
    busy: Boolean,
    connectivityChecking: Boolean,
    onToggleTheme: () -> Unit,
    onRefresh: () -> Unit,
    onRefreshConnectivity: () -> Unit,
    onRefreshMetadata: () -> Unit,
    onScan: () -> Unit,
    onCheckUpdates: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    val runAndClose: (() -> Unit) -> Unit = { action ->
        expanded = false
        action()
    }
    Box {
        ZephyrToolbarButton("More", onClick = { expanded = true })
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            if (showActivity) {
                DropdownMenuItem(
                    text = { Text("Activity") },
                    onClick = { runAndClose(onToggleActivity) },
                )
            }
            DropdownMenuItem(
                text = { Text("Network · ${networkLabel.lowercase()}") },
                enabled = !connectivityChecking,
                onClick = { runAndClose(onRefreshConnectivity) },
            )
            DropdownMenuItem(
                text = { Text("Refresh installed") },
                enabled = !busy,
                onClick = { runAndClose(onRefresh) },
            )
            DropdownMenuItem(
                text = { Text("Refresh metadata · $metadataLabel") },
                enabled = !busy,
                onClick = { runAndClose(onRefreshMetadata) },
            )
            DropdownMenuItem(
                text = { Text("Scan local-only versions") },
                enabled = !busy,
                onClick = { runAndClose(onScan) },
            )
            DropdownMenuItem(
                text = { Text("Check SDKMAN update · $updateLabel") },
                enabled = !busy,
                onClick = { runAndClose(onCheckUpdates) },
            )
            DropdownMenuItem(
                text = { Text(if (darkTheme) "Use light theme" else "Use dark theme") },
                onClick = { runAndClose(onToggleTheme) },
            )
        }
    }
}

@Composable
private fun ActivityCenterPanel(
    state: ZephyrUiState.Ready,
    onDismissEvent: (Long) -> Unit,
    onAction: (ActivityAction) -> Unit,
    onClose: () -> Unit,
) {
    val metrics = LocalZephyrMetrics.current
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .padding(top = metrics.toolbarHeight + 8.dp, start = 12.dp, end = 12.dp, bottom = metrics.statusBarHeight + 8.dp),
        color = androidx.compose.ui.graphics.Color.Transparent,
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
            Surface(
                modifier = Modifier.widthIn(max = 480.dp).fillMaxWidth().heightIn(max = 560.dp),
                color = MaterialTheme.colorScheme.surfaceContainerLow,
                shape = MaterialTheme.shapes.large,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                shadowElevation = 10.dp,
            ) {
                Column(
                    modifier = Modifier.padding(metrics.panelPadding),
                    verticalArrangement = Arrangement.spacedBy(metrics.spacing),
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            "Activity",
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Badge(state.activityEvents.size.toString(), BadgeTone.Primary)
                        TextButton(onClick = onClose) { Text("Close") }
                    }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                    if (state.activityEvents.isEmpty()) {
                        Text(
                            "No recent activity.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                            verticalArrangement = Arrangement.spacedBy(metrics.spacing),
                        ) {
                            items(state.activityEvents, key = { it.id }) { event ->
                                val semanticColors = LocalZephyrColors.current
                                val accent = when (event.severity) {
                                    ActivitySeverity.Success -> semanticColors.success
                                    ActivitySeverity.Warning -> semanticColors.warning
                                    ActivitySeverity.Error -> MaterialTheme.colorScheme.error
                                    ActivitySeverity.Info -> semanticColors.info
                                }
                                Surface(
                                    color = MaterialTheme.colorScheme.surface,
                                    shape = MaterialTheme.shapes.small,
                                    border = BorderStroke(1.dp, if (event.acknowledged) MaterialTheme.colorScheme.outlineVariant else accent.copy(alpha = 0.6f)),
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                                        verticalArrangement = Arrangement.spacedBy(7.dp),
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                                        ) {
                                            Badge(
                                                event.severity.label,
                                                when (event.severity) {
                                                    ActivitySeverity.Success -> BadgeTone.Success
                                                    ActivitySeverity.Warning -> BadgeTone.Warning
                                                    ActivitySeverity.Error -> BadgeTone.Error
                                                    ActivitySeverity.Info -> BadgeTone.Neutral
                                                },
                                            )
                                            Text(
                                                formatLocalTimestamp(event.timestampEpochMillis),
                                                modifier = Modifier.weight(1f),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            if (!event.acknowledged) {
                                                TextButton(onClick = { onDismissEvent(event.id) }) { Text("Dismiss") }
                                            }
                                        }
                                        Text(event.message, style = MaterialTheme.typography.bodyMedium)
                                        event.action?.let { action ->
                                            TextButton(onClick = { onAction(action) }) { Text(action.label) }
                                        }
                                    }
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
private fun GlobalSearchButton(onClick: () -> Unit) {
    val metrics = LocalZephyrMetrics.current
    Surface(
        onClick = onClick,
        modifier = Modifier.width(250.dp).height(metrics.controlHeight),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(
                "Search / commands",
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            ZephyrKeycap("Ctrl/⌘ K")
        }
    }
}

@Composable
private fun WorkbenchSidebar(
    state: ZephyrUiState.Ready,
    width: Dp,
    onNavigate: (ZephyrRoute) -> Unit,
) {
    val metrics = LocalZephyrMetrics.current
    val installedSdks = state.candidates.count { it.kind == CandidateKind.Sdk }
    val localOnly = state.candidates.sumOf { it.localOnlyVersionCount }
    val updates = availableCandidateUpdates(state.candidates, state.catalog).size
    val activeTask = navigationTaskFor(state.route)
    var expandedTasks by remember { mutableStateOf(setOfNotNull(activeTask)) }
    LaunchedEffect(activeTask) {
        if (activeTask != null) expandedTasks = expandedTasks + activeTask
    }
    Column(
        modifier = Modifier
            .width(width)
            .fillMaxHeight()
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = metrics.spacing, vertical = metrics.panelPadding),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        ZephyrSectionLabel("Tasks")
        ZephyrNavigationItem(
            "O",
            "Overview",
            state.route is ZephyrRoute.Overview,
            { onNavigate(ZephyrRoute.Overview) },
        )
        NavigationTaskGroup(
            task = NavigationTask.Installed,
            glyph = "I",
            expanded = NavigationTask.Installed in expandedTasks,
            active = activeTask == NavigationTask.Installed,
            badge = (installedSdks + state.candidates.count { it.kind == CandidateKind.Jdk }).toString(),
            onToggle = {
                expandedTasks = expandedTasks.toggled(NavigationTask.Installed)
            },
        ) {
            ZephyrNavigationItem(
                "J",
                "Installed JDK",
                state.route is ZephyrRoute.InstalledJdk,
                { onNavigate(ZephyrRoute.InstalledJdk) },
                Modifier.padding(start = 14.dp),
                state.candidates.firstOrNull { it.kind == CandidateKind.Jdk }
                    ?.installedVersions
                    ?.count { it.isInstalled }
                    ?.toString(),
            )
            ZephyrNavigationItem(
                "S",
                "Installed SDKs",
                state.route is ZephyrRoute.InstalledSdks,
                { onNavigate(ZephyrRoute.InstalledSdks) },
                Modifier.padding(start = 14.dp),
                installedSdks.toString(),
            )
        }
        NavigationTaskGroup(
            task = NavigationTask.Discover,
            glyph = "⌕",
            expanded = NavigationTask.Discover in expandedTasks,
            active = activeTask == NavigationTask.Discover,
            onToggle = { expandedTasks = expandedTasks.toggled(NavigationTask.Discover) },
        ) {
            NavigationChild("+J", "Browse JDKs", state.route is ZephyrRoute.BrowseJdks, ZephyrRoute.BrowseJdks, onNavigate)
            NavigationChild("+S", "Browse SDKs", state.route is ZephyrRoute.BrowseSdks, ZephyrRoute.BrowseSdks, onNavigate)
            NavigationChild("≡", "Compare versions", state.route is ZephyrRoute.Comparison, ZephyrRoute.Comparison, onNavigate)
        }
        NavigationTaskGroup(
            task = NavigationTask.Projects,
            glyph = "P",
            expanded = NavigationTask.Projects in expandedTasks,
            active = activeTask == NavigationTask.Projects,
            onToggle = { expandedTasks = expandedTasks.toggled(NavigationTask.Projects) },
        ) {
            NavigationChild("W", "Workspaces", state.route is ZephyrRoute.ProjectWorkspaces, ZephyrRoute.ProjectWorkspaces, onNavigate)
            NavigationChild("P", "Profiles", state.route is ZephyrRoute.Profiles, ZephyrRoute.Profiles, onNavigate)
            NavigationChild("↥", "Import .sdkmanrc", state.route is ZephyrRoute.ProjectImport, ZephyrRoute.ProjectImport, onNavigate)
            NavigationChild("↧", "Export .sdkmanrc", state.route is ZephyrRoute.ProjectExport, ZephyrRoute.ProjectExport, onNavigate)
            NavigationChild("◎", "Snapshots", state.route is ZephyrRoute.EnvironmentSnapshot, ZephyrRoute.EnvironmentSnapshot, onNavigate)
        }
        ZephyrNavigationItem(
            "↑",
            "Updates",
            state.route is ZephyrRoute.UpdateCenter,
            { onNavigate(ZephyrRoute.UpdateCenter) },
            badge = updates.takeIf { it > 0 }?.toString(),
        )
        NavigationTaskGroup(
            task = NavigationTask.Storage,
            glyph = "▣",
            expanded = NavigationTask.Storage in expandedTasks,
            active = activeTask == NavigationTask.Storage,
            badge = localOnly.takeIf { it > 0 }?.toString(),
            onToggle = { expandedTasks = expandedTasks.toggled(NavigationTask.Storage) },
        ) {
            NavigationChild("▣", "Storage overview", state.route is ZephyrRoute.Storage, ZephyrRoute.Storage, onNavigate)
            NavigationChild("!", "Local-only", state.route is ZephyrRoute.LocalOnly, ZephyrRoute.LocalOnly, onNavigate)
            NavigationChild("−", "Batch uninstall", state.route is ZephyrRoute.BatchUninstall, ZephyrRoute.BatchUninstall, onNavigate)
        }
        NavigationTaskGroup(
            task = NavigationTask.Activity,
            glyph = "A",
            expanded = NavigationTask.Activity in expandedTasks,
            active = activeTask == NavigationTask.Activity,
            badge = state.operationJournal.size.takeIf { it > 0 }?.toString(),
            onToggle = { expandedTasks = expandedTasks.toggled(NavigationTask.Activity) },
        ) {
            NavigationChild("D", "Diagnostics", state.route is ZephyrRoute.Diagnostics, ZephyrRoute.Diagnostics, onNavigate)
            NavigationChild("T", "Task Center", state.route is ZephyrRoute.History, ZephyrRoute.History, onNavigate)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
        ZephyrNavigationItem("⚙", "Settings", state.route is ZephyrRoute.Settings, { onNavigate(ZephyrRoute.Settings) })
        ZephyrNavigationItem("i", "About", state.route is ZephyrRoute.About, { onNavigate(ZephyrRoute.About) })
    }
}

@Composable
private fun NavigationTaskGroup(
    task: NavigationTask,
    glyph: String,
    expanded: Boolean,
    active: Boolean,
    badge: String? = null,
    onToggle: () -> Unit,
    children: @Composable () -> Unit,
) {
    ZephyrNavigationItem(
        glyph = if (expanded) "⌄$glyph" else "›$glyph",
        label = task.label,
        selected = active,
        onClick = onToggle,
        badge = badge,
    )
    if (expanded) {
        Column(Modifier.fillMaxWidth().padding(start = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            children()
        }
    }
}

@Composable
private fun NavigationChild(
    glyph: String,
    label: String,
    selected: Boolean,
    route: ZephyrRoute,
    onNavigate: (ZephyrRoute) -> Unit,
) {
    ZephyrNavigationItem(
        glyph = glyph,
        label = label,
        selected = selected,
        onClick = { onNavigate(route) },
        modifier = Modifier.padding(start = 14.dp),
    )
}

private fun Set<NavigationTask>.toggled(task: NavigationTask): Set<NavigationTask> =
    if (task in this) this - task else this + task

@Composable
private fun NavigationResizeHandle(
    onDrag: (Float) -> Unit,
    onDragFinished: () -> Unit,
) {
    Box(
        modifier = Modifier
            .width(7.dp)
            .fillMaxHeight()
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragEnd = onDragFinished,
                    onDragCancel = onDragFinished,
                ) { change, dragAmount ->
                    change.consume()
                    onDrag(dragAmount)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(1.dp)
                .fillMaxHeight()
                .background(MaterialTheme.colorScheme.outlineVariant),
        )
    }
}

@Composable
private fun WorkbenchStatusBar(
    state: ZephyrUiState.Ready,
    compact: Boolean,
    showSdkmanHome: Boolean,
    metadataRefreshSchedule: MetadataRefreshSchedule,
    onRetryFailedLocalOnlyReads: () -> Unit,
) {
    val metrics = LocalZephyrMetrics.current
    val busyLabel = state.busyLabel()
    Surface(
        modifier = Modifier.fillMaxWidth().height(metrics.statusBarHeight),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier.fillMaxSize().horizontalScroll(rememberScrollState()).padding(horizontal = metrics.spacing),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Surface(
                color = if (busyLabel == null) LocalZephyrColors.current.successContainer else MaterialTheme.colorScheme.primaryContainer,
                contentColor = if (busyLabel == null) LocalZephyrColors.current.onSuccess else MaterialTheme.colorScheme.onPrimaryContainer,
                shape = MaterialTheme.shapes.extraSmall,
            ) {
                Row(
                    Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(if (busyLabel == null) "✓" else "↻", style = MaterialTheme.typography.labelSmall)
                    Text(busyLabel ?: "Ready", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                }
            }
            state.localOnlyScanProgress?.let { progress ->
                val summary = "${progress.completed}/${progress.total} audited, " +
                    "${progress.trustedFindings.sumOf { it.localOnlyVersionCount }} findings, " +
                    "${progress.failures.size} failures"
                Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    summary,
                    modifier = Modifier.semantics {
                        contentDescription = buildString {
                            append("Local-only audit $summary.")
                            if (progress.activeCandidates.isNotEmpty()) {
                                append(" Active candidates: ${progress.activeCandidates.joinToString()}.")
                            }
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = if (progress.failures.isEmpty()) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                    maxLines = 1,
                )
                if (progress.failures.isNotEmpty() && !progress.running) {
                    TextButton(onClick = onRetryFailedLocalOnlyReads) {
                        Text("Retry failed")
                    }
                }
            }
            Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
            StatusDot(
                when (state.connectivityStatus.state) {
                    ConnectivityState.Online -> StatusTone.Success
                    ConnectivityState.Offline -> StatusTone.Error
                    ConnectivityState.Checking -> StatusTone.Accent
                    ConnectivityState.Unknown -> StatusTone.Neutral
                },
            )
            Text(
                state.connectivityStatus.state.label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!compact) {
                Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${state.candidates.size} candidates",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    jdkSubtitle(state),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (metadataRefreshSchedule != MetadataRefreshSchedule.Off) {
                    Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Auto refresh: ${metadataRefreshSchedule.label}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                state.catalogCachedAtEpochMillis?.takeIf { state.catalogIsCached }?.let { cachedAt ->
                    Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        "Cached catalog: ${candidateCacheAgeLabel(cachedAt, currentEpochMillis())}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
            }
            state.readRetryStatus?.let { retry ->
                Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Retrying ${retry.operation.label} (${retry.nextAttempt}/${retry.maximumAttempts})",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    maxLines = 1,
                )
            }
            Spacer(Modifier.width(metrics.spacing))
            if (showSdkmanHome && !compact) {
                Text(
                    state.sdkmanStatus.home.orEmpty(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.MiddleEllipsis,
                )
                Text("•", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!compact) {
                Text(
                    sdkmanVersionLabel(state),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}


private fun ZephyrUiState.Ready.busyLabel(): String? =
    when {
        batchUninstallProgress.any { it.status == BatchItemStatus.Running } -> {
            val completed = batchUninstallProgress.count {
                it.status == BatchItemStatus.Succeeded || it.status == BatchItemStatus.Failed
            }
            "Uninstalling ${completed + 1} of ${batchUninstallProgress.size}"
        }
        batchInstallProgress.any { it.status == BatchItemStatus.Running } -> {
            val completed = batchInstallProgress.count {
                it.status == BatchItemStatus.Succeeded || it.status == BatchItemStatus.Failed
            }
            "Installing ${completed + 1} of ${batchInstallProgress.size}"
        }
        localOnlyScanInProgress -> localOnlyScanProgress?.let {
            "Scanning local-only ${it.completed}/${it.total}" +
                if (it.activeCandidates.isEmpty()) "" else " (${it.activeCandidates.joinToString()})"
        } ?: "Scanning local-only versions"
        storageScanInProgress -> "Measuring installed payloads"
        isCatalogLoading -> "Loading SDKMAN catalog"
        detailLoadingCandidate != null -> "Loading package details"
        journalExportInProgress -> "Exporting operation journal"
        diagnosticsExportInProgress -> "Exporting support bundle"
        transactionPreviewLoading -> "Calculating disk impact"
        isRefreshing -> "Refreshing SDKMAN state"
        else -> null
    }
