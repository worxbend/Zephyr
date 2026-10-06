package com.worxbend.zephyr

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.worxbend.zephyr.viewmodel.ZephyrUiState

internal enum class SearchOverlayMode {
    GlobalSearch,
    CommandPalette,
}

@Composable
internal fun GlobalSearchDialog(
    state: ZephyrUiState.Ready,
    mode: SearchOverlayMode = SearchOverlayMode.GlobalSearch,
    onDismiss: () -> Unit,
    onSelect: (GlobalSearchTarget) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val index = remember(state.candidates, state.catalog, mode) {
        val allItems = buildGlobalSearchIndex(state.candidates, state.catalog)
        if (mode == SearchOverlayMode.CommandPalette) commandPaletteItems(allItems) else allItems
    }
    val results = remember(index, query) { searchGlobalIndex(index, query) }
    var selectedIndex by remember(query) { mutableIntStateOf(0) }
    val focusRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val metrics = LocalZephyrMetrics.current

    LaunchedEffect(results, selectedIndex) {
        selectedIndex = selectedIndex.coerceIn(0, (results.size - 1).coerceAtLeast(0))
        if (results.isNotEmpty()) {
            val visible = listState.layoutInfo.visibleItemsInfo
            val selectedItem = visible.firstOrNull { it.index == selectedIndex }
            // Scroll instantly: keyboard navigation stays deterministic and respects reduced motion.
            if (selectedItem == null || selectedItem.offset < listState.layoutInfo.viewportStartOffset ||
                selectedItem.offset + selectedItem.size > listState.layoutInfo.viewportEndOffset
            ) {
                listState.scrollToItem(selectedIndex)
            }
        }
    }
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.padding(16.dp).widthIn(max = 760.dp).fillMaxWidth().heightIn(max = 560.dp)
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) {
                        false
                    } else {
                        when (event.key) {
                            Key.DirectionDown -> {
                                if (results.isNotEmpty()) {
                                    selectedIndex = (selectedIndex + 1).coerceAtMost(results.lastIndex)
                                }
                                true
                            }
                            Key.DirectionUp -> {
                                selectedIndex = (selectedIndex - 1).coerceAtLeast(0)
                                true
                            }
                            Key.Enter -> {
                                results.getOrNull(selectedIndex)?.let { onSelect(it.target) }
                                true
                            }
                            Key.Escape -> {
                                onDismiss()
                                true
                            }
                            else -> false
                        }
                    }
                },
            shape = MaterialTheme.shapes.large,
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            shadowElevation = 12.dp,
        ) {
            Column(
                modifier = Modifier.padding(metrics.panelPadding),
                verticalArrangement = Arrangement.spacedBy(metrics.spacing),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (mode == SearchOverlayMode.CommandPalette) "Command palette" else "Search Zephyr",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    ZephyrKeycap("Esc")
                }
                SearchField(
                    value = query,
                    onValueChange = { query = it },
                    placeholder = if (mode == SearchOverlayMode.CommandPalette) {
                        "Search commands and destinations"
                    } else {
                        "Candidates, versions, settings, and actions"
                    },
                    modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
                )
                Text(
                    if (query.isBlank()) {
                        if (mode == SearchOverlayMode.CommandPalette) "Available commands" else "Quick access"
                    } else {
                        "${results.size} result(s)"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (results.isEmpty()) {
                        item {
                            Column(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                Text("No matching destination", fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (mode == SearchOverlayMode.CommandPalette) {
                                        "Try a workspace destination or maintenance action."
                                    } else {
                                        "Try a candidate key, version, setting, or maintenance action."
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                    itemsIndexed(results, key = { _, item -> item.id }) { indexInList, item ->
                        GlobalSearchResultRow(
                            item = item,
                            selected = indexInList == selectedIndex,
                            onFocus = { selectedIndex = indexInList },
                            onClick = {
                                selectedIndex = indexInList
                                onSelect(item.target)
                            },
                        )
                    }
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Text(
                    if (mode == SearchOverlayMode.CommandPalette) {
                        "↑↓ Move  •  Enter Run  •  Esc Close"
                    } else {
                        "↑↓ Move  •  Enter Open  •  Ctrl/⌘ Shift P Commands  •  Esc Close"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun GlobalSearchResultRow(
    item: GlobalSearchItem,
    selected: Boolean,
    onFocus: () -> Unit,
    onClick: () -> Unit,
) {
    val background = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.medium)
            .background(background)
            .border(
                1.dp,
                if (selected) MaterialTheme.colorScheme.primary else background,
                MaterialTheme.shapes.medium,
            )
            .onFocusChanged { if (it.isFocused) onFocus() }
            .semantics { this.selected = selected }
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                item.kind.label,
                style = MaterialTheme.typography.labelSmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                item.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            )
            Text(
                item.subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (item.shortcut != null) {
            ZephyrKeycap(item.shortcut)
        } else if (selected) {
            ZephyrKeycap("Enter")
        }
    }
}
