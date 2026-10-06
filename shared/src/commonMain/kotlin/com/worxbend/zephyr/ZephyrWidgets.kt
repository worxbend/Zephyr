package com.worxbend.zephyr

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

internal enum class StatusTone {
    Neutral,
    Accent,
    Success,
    Warning,
    Error,
}

internal fun statusSymbol(tone: StatusTone): String =
    when (tone) {
        StatusTone.Neutral -> "•"
        StatusTone.Accent -> "↻"
        StatusTone.Success -> "✓"
        StatusTone.Warning -> "!"
        StatusTone.Error -> "×"
    }

internal fun statusLabel(tone: StatusTone): String =
    when (tone) {
        StatusTone.Neutral -> "Unknown"
        StatusTone.Accent -> "In progress"
        StatusTone.Success -> "Healthy"
        StatusTone.Warning -> "Attention"
        StatusTone.Error -> "Error"
    }

@Composable
internal fun ZephyrPanel(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val metrics = LocalZephyrMetrics.current
    Surface(
        modifier = modifier,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(metrics.cornerRadius),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        tonalElevation = 0.dp,
        shadowElevation = 1.dp,
        content = content,
    )
}

@Composable
internal fun ZephyrClickablePanel(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val metrics = LocalZephyrMetrics.current
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val focused by interactions.collectIsFocusedAsState()
    val pressed by interactions.collectIsPressedAsState()
    val fill = zephyrAnimatedColor(
        when {
            pressed -> MaterialTheme.colorScheme.primaryContainer
            hovered || focused -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> MaterialTheme.colorScheme.surfaceContainerLow
        },
    )
    Surface(
        modifier = modifier.hoverable(interactions),
        onClick = onClick,
        interactionSource = interactions,
        border = BorderStroke(
            if (focused) 2.dp else 1.dp,
            if (focused || hovered) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
        ),
        shape = RoundedCornerShape(metrics.cornerRadius),
        color = fill,
        tonalElevation = 0.dp,
        content = content,
    )
}

@Composable
internal fun ZephyrSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 10.dp, vertical = 6.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
    )
}

@Composable
internal fun ZephyrNavigationItem(
    glyph: String,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    val metrics = LocalZephyrMetrics.current
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    var focused by remember { mutableStateOf(false) }
    val background = zephyrAnimatedColor(
        when {
            selected -> MaterialTheme.colorScheme.primaryContainer
            hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> Color.Transparent
        },
    )
    val contentColor = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(metrics.controlHeight)
            .background(background, RoundedCornerShape(metrics.cornerRadius))
            .border(
                1.dp,
                if (focused) MaterialTheme.colorScheme.primary else Color.Transparent,
                RoundedCornerShape(metrics.cornerRadius),
            )
            .onFocusChanged { focused = it.isFocused }
            .semantics { this.selected = selected }
            .clip(RoundedCornerShape(metrics.cornerRadius))
            .hoverable(interactions)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        Box(
            modifier = Modifier
                .size(22.dp)
                .background(
                    if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f) else MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(5.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Text(glyph, color = contentColor, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
        }
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            color = contentColor,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        badge?.let {
            Text(
                text = it,
                color = contentColor.copy(alpha = 0.78f),
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}

@Composable
internal fun ZephyrToolbarButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    detail: String? = null,
    enabled: Boolean = true,
) {
    val metrics = LocalZephyrMetrics.current
    val interactionSource = remember { MutableInteractionSource() }
    val hovered by interactionSource.collectIsHoveredAsState()
    val pressed by interactionSource.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val fill = zephyrAnimatedColor(
        when {
            enabled && pressed -> MaterialTheme.colorScheme.primaryContainer
            enabled && hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> MaterialTheme.colorScheme.surfaceContainer
        },
    )
    Surface(
        modifier = modifier
            .height(metrics.controlHeight)
            .alpha(if (enabled) 1f else 0.5f)
            .clip(RoundedCornerShape(metrics.cornerRadius))
            .hoverable(interactionSource, enabled)
            .onFocusChanged { focused = it.isFocused }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            ),
        color = fill,
        shape = RoundedCornerShape(metrics.cornerRadius),
        border = BorderStroke(
            1.dp,
            if (focused || (enabled && hovered)) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline,
        ),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Text(label, style = MaterialTheme.typography.labelMedium, maxLines = 1)
            detail?.let {
                StatusDot(tone = if (it == "failed" || it == "offline") StatusTone.Error else StatusTone.Accent)
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
internal fun ZephyrMetricTile(
    label: String,
    value: String,
    detail: String,
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val metrics = LocalZephyrMetrics.current
    ZephyrPanel(modifier) {
        Column(
            modifier = Modifier.padding(metrics.panelPadding),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                StatusDot(tone)
                Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(value, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold, color = statusColor(tone))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
internal fun ZephyrSettingsRow(
    title: String,
    description: String,
    modifier: Modifier = Modifier,
    control: @Composable RowScope.() -> Unit,
) {
    val metrics = LocalZephyrMetrics.current
    BoxWithConstraints(modifier.fillMaxWidth()) {
        if (maxWidth < 580.dp * zephyrContentScale()) {
            Column(
                Modifier.fillMaxWidth().padding(vertical = metrics.spacing),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = control)
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = metrics.spacing),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                control()
            }
        }
    }
}

@Composable
internal fun <T> ZephyrSegmentedControl(
    options: List<T>,
    selected: T,
    label: (T) -> String,
    onSelected: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    val metrics = LocalZephyrMetrics.current
    FlowRow(
        modifier = modifier
            .selectableGroup()
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(metrics.cornerRadius))
            .padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        options.forEach { option ->
            val active = option == selected
            var focused by remember { mutableStateOf(false) }
            val shape = RoundedCornerShape((metrics.cornerRadius - 4.dp).coerceAtLeast(4.dp))
            val fill = zephyrAnimatedColor(
                if (active) MaterialTheme.colorScheme.surface else Color.Transparent,
            )
            Box(
                modifier = Modifier
                    .heightIn(min = metrics.controlHeight - 4.dp)
                    .clip(shape)
                    .background(fill)
                    .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
                    .onFocusChanged { focused = it.isFocused }
                    .selectable(selected = active, role = Role.RadioButton) { onSelected(option) }
                    .padding(horizontal = 14.dp, vertical = 7.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = label(option),
                    color = if (active) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
                )
            }
        }
    }
}

@Composable
internal fun ZephyrToggle(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Switch(checked = checked, onCheckedChange = onCheckedChange)
}

@Composable
internal fun ZephyrDestructiveButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ),
    ) {
        Text(label)
    }
}

@Composable
internal fun StatusDot(
    tone: StatusTone,
    modifier: Modifier = Modifier,
) {
    val color = statusColor(tone)
    Box(
        modifier = modifier
            .size(14.dp * (LocalZephyrMetrics.current.controlHeight.value / 36f))
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(99.dp))
            .semantics { contentDescription = "${statusLabel(tone)} status" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = statusSymbol(tone),
            color = color,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
internal fun ZephyrProgressIndicator(
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val size = if (compact) 18.dp else 40.dp
    if (LocalReducedMotion.current) {
        Box(
            modifier = modifier
                .size(size)
                .semantics { contentDescription = "In progress; reduced motion" },
            contentAlignment = Alignment.Center,
        ) {
            StatusDot(StatusTone.Accent)
        }
    } else if (compact) {
        CircularProgressIndicator(modifier = modifier.size(size), strokeWidth = 2.dp)
    } else {
        LinearProgressIndicator(
            modifier = modifier.width(120.dp).height(5.dp).clip(MaterialTheme.shapes.small),
            color = MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
        )
    }
}

@Composable
internal fun statusColor(tone: StatusTone): Color = when (tone) {
    StatusTone.Neutral -> MaterialTheme.colorScheme.onSurfaceVariant
    StatusTone.Accent -> MaterialTheme.colorScheme.primary
    StatusTone.Success -> LocalZephyrColors.current.success
    StatusTone.Warning -> LocalZephyrColors.current.warning
    StatusTone.Error -> MaterialTheme.colorScheme.error
}

/** Only user-driven color transitions animate; reduced motion switches immediately. */
@Composable
internal fun zephyrAnimatedColor(target: Color): Color {
    val color by animateColorAsState(
        targetValue = target,
        animationSpec = tween(if (LocalReducedMotion.current) 0 else 140),
        label = "Control feedback",
    )
    return color
}
