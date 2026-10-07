package com.worxbend.zephyr

import androidx.compose.foundation.LocalScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Show where long desktop pages continue, without drawing over their controls. */
@Composable
internal fun ZephyrScrollPane(
    modifier: Modifier = Modifier,
    spacing: Dp = 24.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scroll = rememberScrollState()
    Box(modifier) {
        Column(
            Modifier.fillMaxSize().padding(end = 14.dp).verticalScroll(scroll),
            verticalArrangement = Arrangement.spacedBy(spacing),
            content = content,
        )
        VerticalScrollbar(
            adapter = rememberScrollbarAdapter(scroll),
            modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
            style = LocalScrollbarStyle.current.copy(
                unhoverColor = MaterialTheme.colorScheme.onSurfaceVariant,
                hoverColor = MaterialTheme.colorScheme.onSurface,
            ),
        )
    }
}
