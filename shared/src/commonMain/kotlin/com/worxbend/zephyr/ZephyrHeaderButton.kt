package com.worxbend.zephyr

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** Flat symbolic header action with a tooltip and explicit keyboard focus. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ZephyrHeaderButton(
    label: String,
    icon: ZephyrIcon,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    badge: String? = null,
) {
    val interactions = remember { MutableInteractionSource() }
    val hovered by interactions.collectIsHoveredAsState()
    val pressed by interactions.collectIsPressedAsState()
    var focused by remember { mutableStateOf(false) }
    val shape = MaterialTheme.shapes.small
    val fill = zephyrAnimatedColor(
        when {
            enabled && pressed -> MaterialTheme.colorScheme.surfaceContainerHighest
            enabled && hovered -> MaterialTheme.colorScheme.surfaceContainerHigh
            else -> Color.Transparent
        },
    )
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Below),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
    ) {
        Box(
            modifier = modifier
                .size(LocalZephyrMetrics.current.controlHeight.coerceAtLeast(40.dp))
                .alpha(if (enabled) 1f else 0.45f)
                .clip(shape)
                .background(fill)
                .border(2.dp, if (focused) MaterialTheme.colorScheme.primary else Color.Transparent, shape)
                .hoverable(interactions, enabled)
                .onFocusChanged { focused = it.isFocused }
                .semantics { contentDescription = if (badge == null) label else "$label, $badge unread" }
                .clickable(interactions, indication = null, enabled = enabled, role = Role.Button, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            ZephyrSymbol(icon)
            if (badge != null) {
                Box(
                    Modifier.align(Alignment.TopEnd).padding(6.dp).size(6.dp)
                        .background(MaterialTheme.colorScheme.primary, MaterialTheme.shapes.extraLarge),
                )
            }
        }
    }
}
