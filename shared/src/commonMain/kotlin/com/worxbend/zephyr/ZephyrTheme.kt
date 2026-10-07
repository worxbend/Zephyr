package com.worxbend.zephyr

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Immutable
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.worxbend.zephyr.settings.TextScale
import com.worxbend.zephyr.settings.UiDensity

@Immutable
internal data class ZephyrMetrics(
    val navigationWidth: Dp,
    val toolbarHeight: Dp,
    val statusBarHeight: Dp,
    val pagePadding: Dp,
    val panelPadding: Dp,
    val controlHeight: Dp,
    val cornerRadius: Dp,
    val spacing: Dp,
)

internal val LocalZephyrMetrics = staticCompositionLocalOf { CompactMetrics }
internal val LocalReducedMotion = staticCompositionLocalOf { false }
private val LocalZephyrTextScale = staticCompositionLocalOf { 1f }

/** Layout breakpoints account for both application and operating-system text scale. */
@Composable
internal fun zephyrContentScale(): Float = LocalZephyrTextScale.current * LocalDensity.current.fontScale

/** Semantic feedback colors. The on-colors are paired with their containers. */
@Immutable
internal data class ZephyrColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
)

internal val LocalZephyrColors = staticCompositionLocalOf { ZephyrLightFeedback }

internal val ZephyrDarkFeedback = ZephyrColors(
    success = Color(0xFF69DFB1), onSuccess = Color(0xFF9DEBCC), successContainer = Color(0xFF16392F),
    warning = Color(0xFFF3C675), onWarning = Color(0xFFFFD99B), warningContainer = Color(0xFF44351D),
    info = Color(0xFF9EBBFF), onInfo = Color(0xFFCDDCFF), infoContainer = Color(0xFF243451),
)

internal val ZephyrLightFeedback = ZephyrColors(
    success = Color(0xFF126847), onSuccess = Color(0xFF115438), successContainer = Color(0xFFD9F2E6),
    warning = Color(0xFF815006), onWarning = Color(0xFF70430B), warningContainer = Color(0xFFFFEAC6),
    info = Color(0xFF2B5299), onInfo = Color(0xFF234782), infoContainer = Color(0xFFE0EAFE),
)

private val ZephyrShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp),
)

@Composable
internal fun ZephyrTheme(
    darkTheme: Boolean,
    density: UiDensity = UiDensity.Compact,
    textScale: TextScale = TextScale.Percent100,
    reducedMotion: Boolean = false,
    content: @Composable () -> Unit,
) {
    val baseMetrics = if (density == UiDensity.Compact) CompactMetrics else ComfortableMetrics
    val metrics = baseMetrics.scaledForText(textScale.factor)
    CompositionLocalProvider(
        LocalZephyrMetrics provides metrics,
        LocalReducedMotion provides reducedMotion,
        LocalZephyrTextScale provides textScale.factor,
        LocalZephyrColors provides if (darkTheme) ZephyrDarkFeedback else ZephyrLightFeedback,
    ) {
        MaterialTheme(
            colorScheme = if (darkTheme) ZephyrDarkColors else ZephyrLightColors,
            typography = typographyFor(textScale),
            shapes = ZephyrShapes,
            content = content,
        )
    }
}

internal fun typographyFor(textScale: TextScale): Typography {
    val factor = textScale.factor
    return Typography(
        displayLarge = ZephyrTypography.displayLarge.scaled(factor),
        displayMedium = ZephyrTypography.displayMedium.scaled(factor),
        displaySmall = ZephyrTypography.displaySmall.scaled(factor),
        headlineLarge = ZephyrTypography.headlineLarge.scaled(factor),
        headlineMedium = ZephyrTypography.headlineMedium.scaled(factor),
        headlineSmall = ZephyrTypography.headlineSmall.scaled(factor),
        titleLarge = ZephyrTypography.titleLarge.scaled(factor),
        titleMedium = ZephyrTypography.titleMedium.scaled(factor),
        titleSmall = ZephyrTypography.titleSmall.scaled(factor),
        bodyLarge = ZephyrTypography.bodyLarge.scaled(factor),
        bodyMedium = ZephyrTypography.bodyMedium.scaled(factor),
        bodySmall = ZephyrTypography.bodySmall.scaled(factor),
        labelLarge = ZephyrTypography.labelLarge.scaled(factor),
        labelMedium = ZephyrTypography.labelMedium.scaled(factor),
        labelSmall = ZephyrTypography.labelSmall.scaled(factor),
    )
}

private fun TextStyle.scaled(factor: Float): TextStyle =
    copy(
        fontSize = fontSize * factor,
        lineHeight = lineHeight * factor,
    )

private fun ZephyrMetrics.scaledForText(factor: Float): ZephyrMetrics {
    val supportingScale = 1f + ((factor - 1f) * 0.35f)
    return copy(
        navigationWidth = navigationWidth * supportingScale,
        toolbarHeight = toolbarHeight * factor,
        statusBarHeight = statusBarHeight * factor,
        panelPadding = panelPadding * supportingScale,
        controlHeight = controlHeight * factor,
        cornerRadius = cornerRadius * supportingScale,
        spacing = spacing * supportingScale,
    )
}

internal val ZephyrLightColors = lightColorScheme(
    primary = Color(0xFFB33B10),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFFCE5DA),
    onPrimaryContainer = Color(0xFF7C290D),
    secondary = Color(0xFF77216F),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF1E4EF),
    onSecondaryContainer = Color(0xFF5E2750),
    tertiary = Color(0xFF126847),
    onTertiary = Color(0xFFFFFFFF),
    tertiaryContainer = Color(0xFFD9F2E6),
    onTertiaryContainer = Color(0xFF115438),
    background = Color(0xFFF6F5F4),
    onBackground = Color(0xFF242424),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF242424),
    surfaceVariant = Color(0xFFEDECEB),
    onSurfaceVariant = Color(0xFF595959),
    outline = Color(0xFF808080),
    outlineVariant = Color(0xFFD9D8D6),
    error = Color(0xFFB12643),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFFE0E5),
    onErrorContainer = Color(0xFF891A33),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFF0EFED),
    surfaceContainerHigh = Color(0xFFE6E5E3),
    surfaceContainerHighest = Color(0xFFDCDAD7),
    inverseSurface = Color(0xFF242424),
    inverseOnSurface = Color(0xFFF6F5F4),
    inversePrimary = Color(0xFFFFAD85),
    surfaceTint = Color(0xFFB33B10),
)

internal val ZephyrDarkColors = darkColorScheme(
    primary = Color(0xFFFFAD85),
    onPrimary = Color(0xFF4D210D),
    primaryContainer = Color(0xFF573322),
    onPrimaryContainer = Color(0xFFFFDCC8),
    secondary = Color(0xFFE5ACDB),
    onSecondary = Color(0xFF3D1838),
    secondaryContainer = Color(0xFF493144),
    onSecondaryContainer = Color(0xFFF7D9EF),
    tertiary = Color(0xFF69DFB1),
    onTertiary = Color(0xFF0B3022),
    tertiaryContainer = Color(0xFF16392F),
    onTertiaryContainer = Color(0xFF9DEBCC),
    background = Color(0xFF242424),
    onBackground = Color(0xFFF6F5F4),
    surface = Color(0xFF303030),
    onSurface = Color(0xFFF6F5F4),
    surfaceVariant = Color(0xFF3D3D3D),
    onSurfaceVariant = Color(0xFFC6C6C6),
    outline = Color(0xFF969696),
    outlineVariant = Color(0xFF484848),
    error = Color(0xFFFF8DA5),
    onError = Color(0xFF4A1023),
    errorContainer = Color(0xFF482334),
    onErrorContainer = Color(0xFFFFC6D3),
    surfaceContainerLowest = Color(0xFF1E1E1E),
    surfaceContainerLow = Color(0xFF303030),
    surfaceContainer = Color(0xFF363636),
    surfaceContainerHigh = Color(0xFF424242),
    surfaceContainerHighest = Color(0xFF4A4A4A),
    inverseSurface = Color(0xFFF6F5F4),
    inverseOnSurface = Color(0xFF242424),
    inversePrimary = Color(0xFFB33B10),
    surfaceTint = Color(0xFFFFAD85),
)

private val ZephyrTypography = Typography(
    displaySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.Bold),
    headlineMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    headlineSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
    titleLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
    titleSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 14.sp, lineHeight = 21.sp),
    bodyMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 19.sp),
    bodySmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 12.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontFamily = FontFamily.SansSerif, fontSize = 11.sp, lineHeight = 15.sp, fontWeight = FontWeight.Medium),
)

private val CompactMetrics = ZephyrMetrics(
    navigationWidth = 238.dp,
    toolbarHeight = 58.dp,
    statusBarHeight = 30.dp,
    pagePadding = 24.dp,
    panelPadding = 18.dp,
    controlHeight = 36.dp,
    cornerRadius = 12.dp,
    spacing = 8.dp,
)

private val ComfortableMetrics = ZephyrMetrics(
    navigationWidth = 264.dp,
    toolbarHeight = 58.dp,
    statusBarHeight = 30.dp,
    pagePadding = 28.dp,
    panelPadding = 18.dp,
    controlHeight = 42.dp,
    cornerRadius = 14.dp,
    spacing = 12.dp,
)
