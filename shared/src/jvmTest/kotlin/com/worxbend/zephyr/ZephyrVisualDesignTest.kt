package com.worxbend.zephyr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import androidx.compose.ui.unit.height
import com.worxbend.zephyr.settings.MotionPreference
import com.worxbend.zephyr.settings.TextScale
import com.worxbend.zephyr.settings.ThemePreference
import com.worxbend.zephyr.settings.UiDensity
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Hermetic component regressions, not an SDKMAN integration test or a golden-image comparison.
 * SettingsScreen is deliberately not composed: it loads proxy and SDKMAN-home preferences.
 * Instead these fixtures exercise its real shared settings rows and controls with memory-only state.
 * PNGs are real Compose captures, labelled TEST, for review under shared/build/reports/visual-design.
 * Reproducibility assumes the same JDK, Compose/Skia version and installed fonts.
 */
@OptIn(ExperimentalTestApi::class)
class ZephyrVisualDesignTest {
    @Test
    fun semanticFeedbackTextMeetsAaContrastInBothThemes() {
        listOf(
            Triple("light", ZephyrLightColors, ZephyrLightFeedback),
            Triple("dark", ZephyrDarkColors, ZephyrDarkFeedback),
        ).forEach { (theme, material, feedback) ->
            listOf(
                Triple("success container", feedback.onSuccess, feedback.successContainer),
                Triple("warning container", feedback.onWarning, feedback.warningContainer),
                Triple("info container", feedback.onInfo, feedback.infoContainer),
                Triple("error container", material.onErrorContainer, material.errorContainer),
                Triple("error button", material.onError, material.error),
            ).forEach { (name, foreground, background) ->
                assertContrast("$theme $name", foreground, background)
            }
            listOf(
                "success" to feedback.success,
                "warning" to feedback.warning,
                "info" to feedback.info,
                "error" to material.error,
            ).forEach { (name, foreground) ->
                assertContrast("$theme $name on surface", foreground, material.surface)
                assertContrast("$theme $name on card", foreground, material.surfaceContainerLow)
            }
        }
    }

    @Test
    fun narrowSettingsAtTwoHundredPercentRemainVisibleAndSelectableInLightTheme() =
        checkLargeTextSettings(dark = false)

    @Test
    fun narrowSettingsAtTwoHundredPercentRemainVisibleAndSelectableInDarkTheme() =
        checkLargeTextSettings(dark = true)

    private fun checkLargeTextSettings(dark: Boolean) = runDesktopComposeUiTest(width = 360, height = 900) {
        setContent {
            ZephyrTheme(darkTheme = dark, textScale = TextScale.Percent200, reducedMotion = true) {
                FixtureSurface {
                    TestAppearanceSettings()
                }
            }
        }
        val groups = listOf(
            "test-theme" to ThemePreference.entries.map { it.label },
            "test-density" to UiDensity.entries.map { it.label },
            "test-scale" to TextScale.entries.map { it.label },
            "test-motion" to MotionPreference.entries.map { it.label },
        )
        groups.forEach { (tag, options) ->
            options.forEach { label ->
                val option = onNode(hasText(label) and hasAnyAncestor(hasTestTag(tag)))
                option.performScrollTo().assertIsDisplayed().assertHasClickAction()
                    .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
                assertFullyVisible(option)
                // A node can be in the viewport while its glyphs are ellipsized or clipped.
                val layouts = mutableListOf<TextLayoutResult>()
                onNode(
                    hasText(label) and hasAnyAncestor(hasTestTag(tag)),
                    useUnmergedTree = true,
                ).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                    assertTrue(action(layouts))
                }
                assertTrue(layouts.isNotEmpty(), "Missing text layout for $tag/$label")
                layouts.forEach { layout ->
                    assertFalse(layout.didOverflowHeight, "$tag/$label clips vertically at 200%")
                    // Desktop semantics can retain the full paragraph constraint width even
                    // when the Text node wraps its content. Check painted line extents instead.
                    repeat(layout.lineCount) { line ->
                        assertFalse(layout.isLineEllipsized(line), "$tag/$label is ellipsized")
                        assertTrue(
                            layout.getLineLeft(line) >= -1f && layout.getLineRight(line) <= layout.size.width + 1f,
                            "$tag/$label clips horizontally at 200%",
                        )
                    }
                }
                option.performClick().assertIsSelected()
                options.filterNot { it == label }.forEach { other ->
                    onNode(hasText(other) and hasAnyAncestor(hasTestTag(tag))).assertIsNotSelected()
                }
            }
        }
        onNodeWithText("TEST appearance").performScrollTo()
        waitForIdle()
        savePng(onNodeWithTag("test-surface").captureToImage(), "settings-${themeName(dark)}-360-font200")
    }

    @Test
    fun disabledToolbarCannotActivateAndKeyboardFocusSkipsIt() = runDesktopComposeUiTest {
        var firstClicks = 0
        var disabledClicks = 0
        var lastClicks = 0
        setContent {
            ZephyrTheme(darkTheme = false, reducedMotion = true) {
                Column {
                    ZephyrToolbarButton("TEST first", { firstClicks++ }, Modifier.testTag("test-first"))
                    ZephyrToolbarButton(
                        "TEST disabled",
                        { disabledClicks++ },
                        Modifier.testTag("test-disabled"),
                        enabled = false,
                    )
                    ZephyrToolbarButton("TEST last", { lastClicks++ }, Modifier.testTag("test-last"))
                }
            }
        }
        val first = onNodeWithTag("test-first")
        val disabled = onNodeWithTag("test-disabled")
        val last = onNodeWithTag("test-last")
        disabled.assertIsNotEnabled()
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
            .performClick()
        runOnIdle { assertEquals(0, disabledClicks, "A disabled pointer click invoked its callback") }
        first.assertIsEnabled().assertHasClickAction().requestFocus().assertIsFocused()
        first.performKeyInput { pressKey(Key.Tab) }
        disabled.assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Focused))
        last.assertIsFocused().performKeyInput { pressKey(Key.Enter) }
        runOnIdle { assertEquals(1, lastClicks, "Enter must activate the keyboard-focused toolbar button") }
        last.performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } }
        first.assertIsFocused().performKeyInput { pressKey(Key.Spacebar) }
        runOnIdle {
            assertEquals(1, firstClicks, "Space must activate the keyboard-focused toolbar button")
            assertEquals(0, disabledClicks)
        }
    }

    @Test
    fun lightNarrowControlsCardAndSettingsScreenshot() = captureFixture(dark = false, width = 360)

    @Test
    fun lightWideControlsCardAndSettingsScreenshot() = captureFixture(dark = false, width = 1200)

    @Test
    fun darkNarrowControlsCardAndSettingsScreenshot() = captureFixture(dark = true, width = 360)

    @Test
    fun darkWideControlsCardAndSettingsScreenshot() = captureFixture(dark = true, width = 1200)

    private fun captureFixture(dark: Boolean, width: Int) = runDesktopComposeUiTest(width = width, height = 1200) {
        setContent {
            ZephyrTheme(darkTheme = dark, reducedMotion = true) {
                FixtureSurface {
                    Text("TEST — shared controls", style = MaterialTheme.typography.titleLarge)
                    Text("TEST fixtures only · no SDKMAN or user preferences", style = MaterialTheme.typography.bodySmall)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ZephyrToolbarButton("TEST refresh", {})
                        ZephyrToolbarButton("TEST disabled", {}, enabled = false)
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Badge("TEST ready", BadgeTone.Success)
                        Badge("TEST review", BadgeTone.Warning)
                        Badge("TEST failed", BadgeTone.Error)
                    }
                    ZephyrMetricTile(
                        label = "TEST card",
                        value = "3",
                        detail = "TEST installed tools — fixed fixture count",
                        tone = StatusTone.Success,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    TestAppearanceSettings()
                }
            }
        }
        onNodeWithText("TEST card").assertIsDisplayed()
        onNodeWithText("TEST motion").assertIsDisplayed()
        onNodeWithText("Reduced").assertIsDisplayed()
        waitForIdle()
        val root = onNodeWithTag("test-surface")
        val image = root.captureToImage()
        assertEquals(width, image.width)
        assertEquals(1200, image.height)
        val pixels = image.pixels()
        assertTrue(pixels.any { it != pixels.first() }, "Capture contains only a flat background")
        // The fixture has no clock, cursor, asynchronous loading or running animation.
        mainClock.advanceTimeBy(320)
        waitForIdle()
        assertContentEquals(pixels, root.captureToImage().pixels(), "Static fixture changed between frames")
        savePng(image, "controls-card-settings-${themeName(dark)}-$width")
    }
}

@Composable
private fun FixtureSurface(content: @Composable () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxSize().testTag("test-surface"),
        color = MaterialTheme.colorScheme.background,
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            content()
        }
    }
}

/** Uses the same shared components and enum labels as SettingsScreen, without its service factories. */
@Composable
private fun TestAppearanceSettings() {
    var theme by remember { mutableStateOf(ThemePreference.System) }
    var density by remember { mutableStateOf(UiDensity.Compact) }
    var scale by remember { mutableStateOf(TextScale.Percent100) }
    var motion by remember { mutableStateOf(MotionPreference.Reduced) }
    ZephyrPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("TEST appearance", style = MaterialTheme.typography.titleMedium)
            ZephyrSettingsRow("TEST theme", "TEST choose an appearance.") {
                ZephyrSegmentedControl(ThemePreference.entries, theme, ThemePreference::label, { theme = it }, Modifier.testTag("test-theme"))
            }
            ZephyrSettingsRow("TEST density", "TEST spacing preference.") {
                ZephyrSegmentedControl(UiDensity.entries, density, UiDensity::label, { density = it }, Modifier.testTag("test-density"))
            }
            ZephyrSettingsRow("TEST text scale", "TEST text size preference.") {
                // Selection is memory-only; the test's outer theme deliberately stays at its test scale.
                ZephyrSegmentedControl(TextScale.entries, scale, TextScale::label, { scale = it }, Modifier.testTag("test-scale"))
            }
            ZephyrSettingsRow("TEST motion", "TEST animation preference.") {
                ZephyrSegmentedControl(MotionPreference.entries, motion, MotionPreference::label, { motion = it }, Modifier.testTag("test-motion"))
            }
        }
    }
}

private fun assertContrast(name: String, foreground: Color, background: Color) {
    assertEquals(1f, foreground.alpha, "$name foreground must be opaque for this contrast assertion")
    assertEquals(1f, background.alpha, "$name background must be opaque for this contrast assertion")
    val lighter = maxOf(foreground.luminance(), background.luminance())
    val darker = minOf(foreground.luminance(), background.luminance())
    val ratio = (lighter + 0.05f) / (darker + 0.05f)
    assertTrue(ratio >= 4.5f, "$name contrast $ratio is below WCAG AA 4.5:1")
}

private fun assertFullyVisible(node: SemanticsNodeInteraction) {
    val visible = node.getBoundsInRoot()
    val full = node.getUnclippedBoundsInRoot()
    val tolerance = 1.dp
    assertTrue(visible.width > 0.dp && visible.height > 0.dp, "Control has no visible area")
    assertTrue(visible.width + tolerance >= full.width, "Control is horizontally clipped: $full vs $visible")
    assertTrue(visible.height + tolerance >= full.height, "Control is vertically clipped: $full vs $visible")
}

private fun themeName(dark: Boolean): String = if (dark) "dark" else "light"

private fun ImageBitmap.pixels(): IntArray = IntArray(width * height).also { readPixels(it) }

private fun savePng(image: ImageBitmap, name: String) {
    // Gradle's test working directory is normally shared/, but also support invocation from repo root.
    val repo = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .firstOrNull { Files.isDirectory(it.resolve("shared/src")) }
    val root = assertNotNull(repo, "Cannot locate Zephyr's shared/ directory for test reports")
    val directory = root.resolve("shared/build/reports/visual-design")
    Files.createDirectories(directory)
    val output = directory.resolve("$name.png").toFile()
    val buffered = BufferedImage(image.width, image.height, BufferedImage.TYPE_INT_ARGB)
    buffered.setRGB(0, 0, image.width, image.height, image.pixels(), 0, image.width)
    assertTrue(ImageIO.write(buffered, "png", output), "No PNG writer available")
    val decoded = assertNotNull(ImageIO.read(output), "Generated screenshot could not be decoded")
    assertEquals(image.width, decoded.width)
    assertEquals(image.height, decoded.height)
}
