package com.worxbend.zephyr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.DpOffset
import com.worxbend.zephyr.domain.CandidateKind
import com.worxbend.zephyr.domain.BatchItemStatus
import com.worxbend.zephyr.domain.OperationStatus
import com.worxbend.zephyr.domain.ActivityAction
import com.worxbend.zephyr.domain.ActivitySeverity
import com.worxbend.zephyr.data.createClipboardService
import com.worxbend.zephyr.viewmodel.ZephyrUiState
import org.jetbrains.compose.resources.painterResource
import zephyr.shared.generated.resources.Res
import zephyr.shared.generated.resources.ic_jdk
import zephyr.shared.generated.resources.ic_sdk

@Composable
internal fun PageTitle(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(bottom = 8.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
        Text(subtitle, modifier = Modifier.widthIn(max = 760.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun ZephyrSectionHeading(title: String, detail: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
}

@Composable
internal fun ZephyrKeycap(label: String, modifier: Modifier = Modifier) {
    Text(
        label,
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.shapes.extraSmall)
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.extraSmall)
            .padding(horizontal = 7.dp, vertical = 3.dp),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
internal fun ZephyrTableHeading(columns: List<String>, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
            columns.forEach { label ->
                Text(
                    label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
internal fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val metrics = LocalZephyrMetrics.current
    var focused by remember { mutableStateOf(false) }
    val borderColor = zephyrAnimatedColor(if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
        modifier = modifier
            .height(metrics.controlHeight)
            .clip(RoundedCornerShape(metrics.cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .border(if (focused) 2.dp else 1.dp, borderColor, RoundedCornerShape(metrics.cornerRadius))
            .onFocusChanged { focused = it.isFocused },
        decorationBox = { innerTextField ->
            Row(
                modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ZephyrSymbol(ZephyrIcon.Search, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) {
                        Text(
                            placeholder,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    innerTextField()
                }
                if (value.isNotEmpty()) {
                    Text(
                        text = "×",
                        modifier = Modifier
                            .semantics { contentDescription = "Clear search" }
                            .clickable(role = Role.Button) { onValueChange("") }
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        },
    )
}

@Composable
internal fun CopyTextButton(
    text: String,
    label: String = "Copy",
    modifier: Modifier = Modifier,
) {
    val clipboard = remember { createClipboardService() }
    var result by remember(text) { mutableStateOf<Boolean?>(null) }
    TextButton(
        onClick = {
            result = clipboard.copy(text)
        },
        modifier = modifier,
    ) {
        Text(
            when (result) {
                true -> "Copied"
                false -> "Copy failed"
                null -> label
            },
        )
    }
}

@Composable
internal fun LinkText(
    text: String,
    modifier: Modifier = Modifier,
    maxLines: Int = Int.MAX_VALUE,
) {
    val linkColor = MaterialTheme.colorScheme.primary
    val annotated = remember(text, linkColor) { text.withLinks(linkColor) }
    Text(
        text = annotated,
        modifier = modifier,
        style = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
    )
}

internal data class ContextAction(
    val label: String,
    val enabled: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
@OptIn(ExperimentalComposeUiApi::class)
internal fun ContextActionArea(
    actions: List<ContextAction>,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    var offset by remember { mutableStateOf(DpOffset.Zero) }
    val density = LocalDensity.current
    Box(
        modifier = modifier.onPointerEvent(
            eventType = PointerEventType.Press,
            pass = PointerEventPass.Initial,
        ) { event ->
            if (event.buttons.isSecondaryPressed) {
                event.changes.firstOrNull()?.position?.let { position ->
                    offset = with(density) {
                        DpOffset(position.x.toDp(), position.y.toDp())
                    }
                }
                expanded = true
                event.changes.forEach { it.consume() }
            }
        },
    ) {
        content()
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = offset,
        ) {
            actions.forEach { action ->
                DropdownMenuItem(
                    text = { Text(action.label) },
                    onClick = {
                        expanded = false
                        action.onClick()
                    },
                    enabled = action.enabled,
                )
            }
        }
    }
}

private fun String.withLinks(linkColor: Color): AnnotatedString {
    val urlPattern = Regex("""https?://[^\s)]+""")
    return buildAnnotatedString {
        append(this@withLinks)
        urlPattern.findAll(this@withLinks).forEach { match ->
            addLink(
                LinkAnnotation.Url(
                    url = match.value.trimEnd('.', ','),
                    styles = TextLinkStyles(style = SpanStyle(color = linkColor, textDecoration = TextDecoration.Underline)),
                ),
                match.range.first,
                match.range.last + 1,
            )
        }
    }
}

@Composable
internal fun AccordionHeader(
    title: String,
    count: Int,
    collapsed: Boolean,
    onClick: () -> Unit,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val metrics = LocalZephyrMetrics.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(metrics.cornerRadius))
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(metrics.cornerRadius))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(if (collapsed) "›" else "⌄", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(title, modifier = Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
        Badge("$count")
        if (actionLabel != null && onAction != null) {
            Text(
                actionLabel,
                modifier = Modifier
                    .clip(RoundedCornerShape(5.dp))
                    .clickable(role = Role.Button, onClick = onAction)
                    .padding(horizontal = 7.dp, vertical = 3.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
internal fun EmptyState(title: String, text: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.widthIn(max = 560.dp).padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("○", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            Text(
                text,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (action != null && onAction != null) Button(onClick = onAction, shape = MaterialTheme.shapes.small) { Text(action) }
        }
    }
}

@Composable
internal fun CandidateIcon(kind: CandidateKind) {
    val size = if (LocalZephyrMetrics.current.controlHeight <= 32.dp) 42.dp else 48.dp
    Box(
        Modifier.size(size).clip(MaterialTheme.shapes.medium).background(MaterialTheme.colorScheme.primaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(if (kind == CandidateKind.Jdk) Res.drawable.ic_jdk else Res.drawable.ic_sdk),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.size(24.dp),
        )
    }
}

internal enum class BadgeTone {
    Neutral,
    Primary,
    Success,
    Warning,
    Error,
}

internal fun badgeSymbol(tone: BadgeTone): String? =
    when (tone) {
        BadgeTone.Neutral -> null
        BadgeTone.Primary -> "◆"
        BadgeTone.Success -> "✓"
        BadgeTone.Warning -> "!"
        BadgeTone.Error -> "×"
    }

internal fun badgeToneLabel(tone: BadgeTone): String? =
    when (tone) {
        BadgeTone.Neutral -> null
        BadgeTone.Primary -> "Highlighted"
        BadgeTone.Success -> "Success"
        BadgeTone.Warning -> "Attention"
        BadgeTone.Error -> "Error"
    }

@Composable
internal fun Badge(text: String, tone: BadgeTone = BadgeTone.Neutral) {
    val background = when (tone) {
        BadgeTone.Neutral -> MaterialTheme.colorScheme.secondaryContainer
        BadgeTone.Primary -> MaterialTheme.colorScheme.primaryContainer
        BadgeTone.Success -> LocalZephyrColors.current.successContainer
        BadgeTone.Warning -> LocalZephyrColors.current.warningContainer
        BadgeTone.Error -> MaterialTheme.colorScheme.errorContainer
    }
    val foreground = when (tone) {
        BadgeTone.Neutral -> MaterialTheme.colorScheme.onSecondaryContainer
        BadgeTone.Primary -> MaterialTheme.colorScheme.onPrimaryContainer
        BadgeTone.Success -> LocalZephyrColors.current.onSuccess
        BadgeTone.Warning -> LocalZephyrColors.current.onWarning
        BadgeTone.Error -> MaterialTheme.colorScheme.onErrorContainer
    }
    val toneLabel = badgeToneLabel(tone)
    Row(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(background)
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .semantics {
                contentDescription = toneLabel?.let { "$it: $text" } ?: text
            },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        badgeSymbol(tone)?.let { symbol ->
            Text(
                symbol,
                color = foreground,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold,
            )
        }
        Text(text, color = foreground, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
internal fun CodeBlock(text: String) {
    SelectionContainer {
        Text(
            text,
            modifier = Modifier.fillMaxWidth()
                .clip(MaterialTheme.shapes.small)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.small)
                .padding(16.dp),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
        )
    }
}

@Composable
internal fun MessageOverlay(
    state: ZephyrUiState.Ready,
    onDismiss: (Long) -> Unit,
    onAction: (ActivityAction) -> Unit,
) {
    val event = state.activityEvents.firstOrNull { !it.acknowledged }
    val legacyMessage = state.errorMessage ?: state.lastOutcome
    val message = event?.message ?: legacyMessage ?: return
    val eventId = event?.id ?: LEGACY_MESSAGE_EVENT_ID
    val severity = event?.severity ?: if (state.errorMessage != null) ActivitySeverity.Error else ActivitySeverity.Info
    LaunchedEffect(eventId, severity) {
        if (severity != ActivitySeverity.Error) {
            kotlinx.coroutines.delay(10_000)
            onDismiss(eventId)
        }
    }
    val tone = when (severity) {
        ActivitySeverity.Error -> MaterialTheme.colorScheme.error
        ActivitySeverity.Warning -> LocalZephyrColors.current.warning
        ActivitySeverity.Info -> LocalZephyrColors.current.info
        ActivitySeverity.Success -> LocalZephyrColors.current.success
    }
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.BottomEnd) {
        Column(
            Modifier
                .widthIn(max = 520.dp)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh)
                .border(1.dp, tone, MaterialTheme.shapes.large)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(severity.label, color = tone, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
            Text(message, color = MaterialTheme.colorScheme.onSurface, maxLines = 5, overflow = TextOverflow.Ellipsis)
            FlowRow(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                event?.action?.let { action ->
                    TextButton(onClick = { onAction(action) }) { Text(action.label) }
                }
                TextButton(onClick = { onDismiss(eventId) }) { Text("Dismiss") }
            }
        }
    }
}

internal const val LEGACY_MESSAGE_EVENT_ID = Long.MIN_VALUE

@Composable
internal fun BusyOverlay(state: ZephyrUiState.Ready) {
    val label = when {
        state.batchUninstallProgress.any { it.status == BatchItemStatus.Running } -> {
            val completed = state.batchUninstallProgress.count {
                it.status == BatchItemStatus.Succeeded || it.status == BatchItemStatus.Failed
            }
            "Uninstalling version ${completed + 1} of ${state.batchUninstallProgress.size}"
        }
        state.batchInstallProgress.any { it.status == BatchItemStatus.Running } -> {
            val completed = state.batchInstallProgress.count {
                it.status == BatchItemStatus.Succeeded || it.status == BatchItemStatus.Failed
            }
            "Installing toolchain item ${completed + 1} of ${state.batchInstallProgress.size}"
        }
        state.localOnlyScanInProgress -> state.localOnlyScanProgress?.let {
            "Scanning local-only versions: ${it.completed} of ${it.total} completed" +
                if (it.activeCandidates.isEmpty()) "" else "; active ${it.activeCandidates.joinToString()}"
        } ?: "Scanning local-only versions"
        state.storageScanInProgress -> "Measuring installed payloads"
        state.isCatalogLoading -> "Loading SDKMAN catalog"
        state.detailLoadingCandidate != null -> "Loading package details"
        state.transactionPreviewLoading -> "Calculating disk impact"
        state.diagnosticsExportInProgress -> "Exporting support bundle"
        state.isRefreshing -> "Refreshing"
        else -> return
    }
    Box(
        Modifier.fillMaxSize().padding(top = LocalZephyrMetrics.current.toolbarHeight + 12.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        Row(
            Modifier
                .widthIn(max = 560.dp)
                .clip(MaterialTheme.shapes.large)
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.outlineVariant, MaterialTheme.shapes.large)
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ZephyrProgressIndicator(compact = true)
            Text(label, style = MaterialTheme.typography.labelMedium)
        }
    }
}
