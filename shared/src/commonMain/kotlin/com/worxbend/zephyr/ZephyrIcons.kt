package com.worxbend.zephyr

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.unit.dp

/** Small, original symbolic drawings; labels belong to the surrounding control. */
internal enum class ZephyrIcon(val pathData: String) {
    Search("M10.5,3.5 A7,7 0 1,0 10.5,17.5 A7,7 0 1,0 10.5,3.5 M16,16 L21,21"),
    Menu("M4,6 H20 M4,12 H20 M4,18 H20"),
    More("M11,5 H13 M11,12 H13 M11,19 H13"),
    Back("M14,5 L7,12 L14,19 M7,12 H21"),
    Grid("M4,4 H10 V10 H4 Z M14,4 H20 V10 H14 Z M4,14 H10 V20 H4 Z M14,14 H20 V20 H14 Z"),
    Package("M3,7 L12,3 L21,7 V17 L12,21 L3,17 Z M3,7 L12,11 L21,7 M12,11 V21 M8,5 L17,9"),
    Folder("M3,7 V5 H9 L11,8 H21 V19 H3 Z"),
    Update("M12,17 V3 M7,8 L12,3 L17,8 M4,15 V21 H20 V15"),
    Storage("M4,4 H20 V20 H4 Z M4,12 H20 M7,8 H9 M7,16 H9 M17,8 H17.1 M17,16 H17.1"),
    Activity("M3,12 H7 L10,5 L14,19 L17,12 H21"),
    Settings("M4,6 H20 M4,12 H20 M4,18 H20 M8,3 V9 M16,9 V15 M10,15 V21"),
    Info("M12,3 A9,9 0 1,0 12,21 A9,9 0 1,0 12,3 M12,10 V17 M12,7 H12.1"),
    ChevronRight("M9,6 L15,12 L9,18"),
    ChevronDown("M6,9 L12,15 L18,9"),
    Check("M4,12 L9,17 L20,6"),
    Refresh("M20,9 A8,8 0 1,0 20,16 M20,3 V9 H14"),
}

@Composable
internal fun ZephyrSymbol(
    icon: ZephyrIcon,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.onSurface,
) {
    val path = remember(icon) { PathParser().parsePathString(icon.pathData).toPath() }
    Canvas(modifier.size(20.dp)) {
        scale(size.width / 24f, size.height / 24f, pivot = Offset.Zero) {
            drawPath(path, tint, style = Stroke(width = 1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

internal fun navigationSymbol(glyph: String): ZephyrIcon = when (glyph.removePrefix("›").removePrefix("⌄")) {
    "O" -> ZephyrIcon.Grid
    "I", "J", "S", "+J", "+S" -> ZephyrIcon.Package
    "⌕" -> ZephyrIcon.Search
    "P", "W", "◎" -> ZephyrIcon.Folder
    "↑", "↥", "↧" -> ZephyrIcon.Update
    "▣", "−" -> ZephyrIcon.Storage
    "A", "T", "D", "≡" -> ZephyrIcon.Activity
    "⚙" -> ZephyrIcon.Settings
    else -> ZephyrIcon.Info
}
