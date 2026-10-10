package com.worxbend.zephyr

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.luminance
import com.worxbend.zephyr.settings.AppSettings
import com.worxbend.zephyr.settings.CleanupGracePeriod
import com.worxbend.zephyr.settings.MetadataRefreshSchedule
import com.worxbend.zephyr.settings.MotionPreference
import com.worxbend.zephyr.settings.OperationNotificationPolicy
import com.worxbend.zephyr.settings.ThemePreference
import com.worxbend.zephyr.settings.TextScale
import com.worxbend.zephyr.settings.UiDensity
import com.worxbend.zephyr.settings.UpdateNotificationPolicy
import com.worxbend.zephyr.domain.SdkmanTransaction
import com.worxbend.zephyr.domain.DiskImpactEstimate
import com.worxbend.zephyr.domain.DiskImpactKind
import com.worxbend.zephyr.domain.EstimateConfidence
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

class ZephyrUiPrimitivesTest {
    @OptIn(ExperimentalTestApi::class)
    @Test
    fun rightClickOpensAndExecutesContextAction() = runComposeUiTest {
        setContent {
            ZephyrTheme(darkTheme = false) {
                var result by remember { mutableStateOf("waiting") }
                Box {
                    ContextActionArea(
                        actions = listOf(ContextAction("Inspect") { result = "inspected" }),
                        modifier = Modifier.testTag("context-target"),
                    ) {
                        Text("Candidate", Modifier.padding(24.dp))
                    }
                    Text(result, Modifier.testTag("context-result"))
                }
            }
        }

        onNodeWithTag("context-target").performMouseInput { rightClick() }
        onNodeWithText("Inspect").performClick()
        onNodeWithTag("context-result").assertTextEquals("inspected")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun emptyStateOffersAnInteractiveNextAction() = runComposeUiTest {
        setContent {
            ZephyrTheme(darkTheme = false) {
                var acted by remember { mutableStateOf(false) }
                Box {
                    EmptyState(
                        title = "Nothing here",
                        text = "Take the next useful step.",
                        action = "Continue",
                        onAction = { acted = true },
                    )
                    Text(if (acted) "acted" else "waiting", Modifier.testTag("empty-action-state"))
                }
            }
        }

        onNodeWithText("Continue").performClick()
        onNodeWithTag("empty-action-state").assertTextEquals("acted")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun reducedMotionUsesStaticProgressSemantics() = runComposeUiTest {
        setContent {
            ZephyrTheme(darkTheme = false, reducedMotion = true) {
                ZephyrProgressIndicator()
            }
        }

        onNodeWithContentDescription("In progress; reduced motion").assertExists()
    }

    @Test
    fun typographyScalesThroughTwoHundredPercent() {
        assertEquals(13.sp, typographyFor(TextScale.Percent100).bodyMedium.fontSize)
        assertEquals(19.5.sp, typographyFor(TextScale.Percent150).bodyMedium.fontSize)
        assertEquals(26.sp, typographyFor(TextScale.Percent200).bodyMedium.fontSize)
        assertEquals(38.sp, typographyFor(TextScale.Percent200).bodyMedium.lineHeight)
    }

    @Test
    fun statusTonesHaveDistinctNonColorSignals() {
        assertEquals("•", statusSymbol(StatusTone.Neutral))
        assertEquals("↻", statusSymbol(StatusTone.Accent))
        assertEquals("✓", statusSymbol(StatusTone.Success))
        assertEquals("!", statusSymbol(StatusTone.Warning))
        assertEquals("×", statusSymbol(StatusTone.Error))
        assertEquals("Healthy", statusLabel(StatusTone.Success))
        assertEquals("Error", statusLabel(StatusTone.Error))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun sharedControlsExposeInteractiveAndSelectionSemantics() = runComposeUiTest {
        setContent {
            ZephyrTheme(darkTheme = false) {
                Column {
                    ZephyrNavigationItem("O", "Overview", true, {}, Modifier.testTag("audit-navigation"))
                    ZephyrToolbarButton("Refresh", {}, Modifier.testTag("audit-button"))
                    ZephyrSegmentedControl(
                        options = listOf("Compact", "Comfortable"),
                        selected = "Compact",
                        label = { it },
                        onSelected = {},
                    )
                    StatusDot(StatusTone.Warning)
                }
            }
        }

        onNodeWithTag("audit-navigation").assertHasClickAction().assertIsSelected()
        onNodeWithTag("audit-button").assertHasClickAction()
        onNodeWithText("Compact").assertHasClickAction().assertIsSelected()
        onNodeWithContentDescription("Attention status").assertExists()
    }

    @Test
    fun coreTextColorPairsMeetWcagAaContrast() {
        listOf(
            Triple("light primary", ZephyrLightColors.onPrimary, ZephyrLightColors.primary),
            Triple("light primary container", ZephyrLightColors.onPrimaryContainer, ZephyrLightColors.primaryContainer),
            Triple("light surface", ZephyrLightColors.onSurface, ZephyrLightColors.surface),
            Triple("light surface variant", ZephyrLightColors.onSurfaceVariant, ZephyrLightColors.surfaceVariant),
            Triple("dark primary", ZephyrDarkColors.onPrimary, ZephyrDarkColors.primary),
            Triple("dark primary container", ZephyrDarkColors.onPrimaryContainer, ZephyrDarkColors.primaryContainer),
            Triple("dark surface", ZephyrDarkColors.onSurface, ZephyrDarkColors.surface),
            Triple("dark surface variant", ZephyrDarkColors.onSurfaceVariant, ZephyrDarkColors.surfaceVariant),
        ).forEach { (name, foreground, background) ->
            val lighter = maxOf(foreground.luminance(), background.luminance())
            val darker = minOf(foreground.luminance(), background.luminance())
            assertTrue((lighter + 0.05f) / (darker + 0.05f) >= 4.5f, "$name contrast is below 4.5:1")
        }
    }

    @Test
    fun emphasizedBadgesPairSymbolsWithLabels() {
        assertEquals(null, badgeSymbol(BadgeTone.Neutral))
        assertEquals("◆", badgeSymbol(BadgeTone.Primary))
        assertEquals("✓", badgeSymbol(BadgeTone.Success))
        assertEquals("!", badgeSymbol(BadgeTone.Warning))
        assertEquals("×", badgeSymbol(BadgeTone.Error))
        assertEquals("Success", badgeToneLabel(BadgeTone.Success))
        assertEquals("Error", badgeToneLabel(BadgeTone.Error))
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun searchFieldPublishesUserInput() = runComposeUiTest {
        setContent {
            ZephyrTheme(darkTheme = false) {
                var query by remember { mutableStateOf("") }
                Column {
                    SearchField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = "Search installed candidates",
                        modifier = Modifier.testTag("candidate-search"),
                    )
                    Text(query, modifier = Modifier.testTag("search-query"))
                }
            }
        }

        onNodeWithTag("candidate-search").performClick()
        onNodeWithTag("candidate-search").performTextInput("gradle")

        onNodeWithTag("search-query").assertTextEquals("gradle")
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun settingsScreenPublishesAppearanceChanges() = runComposeUiTest {
        val services = MemoryRouteServices().services
        setContent {
            CompositionLocalProvider(LocalAppServices provides services) {
                ZephyrTheme(darkTheme = false) {
                    var settings by remember { mutableStateOf(AppSettings()) }
                    Box {
                        SettingsScreen(
                            settings = settings,
                            onSettingsChange = { transform -> settings = transform(settings) },
                        )
                        Text(
                            "${settings.themePreference}:${settings.uiDensity}:${settings.textScale}:${settings.motionPreference}:${settings.metadataRefreshSchedule}:${settings.updateNotificationPolicy}:${settings.operationNotificationPolicy}:${settings.cleanupGracePeriod}",
                            modifier = Modifier.testTag("appearance-settings"),
                        )
                    }
                }
            }
        }

        onNodeWithText("Dark").performClick()
        onNodeWithText("Comfortable").performClick()
        onNodeWithText("150%").performClick()
        onNodeWithText("Reduced").performClick()
        onNodeWithText("Every 6 hours").performScrollTo().performClick()
        onNodeWithText("Updates only").performScrollTo().performClick()
        onNodeWithText("Long operations").performScrollTo().performClick()
        onNodeWithText("30 days").performScrollTo().performClick()

        onNodeWithTag("appearance-settings").assertTextEquals(
            "${ThemePreference.Dark}:${UiDensity.Comfortable}:${TextScale.Percent150}:${MotionPreference.Reduced}:${MetadataRefreshSchedule.EverySixHours}:${UpdateNotificationPolicy.UpdatesOnly}:${OperationNotificationPolicy.LongRunning}:${CleanupGracePeriod.ThirtyDays}",
        )
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun transactionPreviewShowsStructuredCommandsBeforeConfirmation() = runComposeUiTest {
        val services = MemoryRouteServices().services
        setContent {
            CompositionLocalProvider(LocalAppServices provides services) {
                ZephyrTheme(darkTheme = false) {
                    var visible by remember { mutableStateOf(true) }
                    if (visible) {
                        TransactionPreviewDialog(
                            transaction = SdkmanTransaction.CleanLocalOnly(
                                candidate = "java",
                                versions = listOf("17.0.1-tem", "19.0.2-tem"),
                            ),
                            diskImpact = DiskImpactEstimate(
                                kind = DiskImpactKind.Reclaimable,
                                bytes = 4_096,
                                availableBytes = 8_192,
                                confidence = EstimateConfidence.Exact,
                                explanation = "Calculated from two installed version directories.",
                            ),
                            onConfirm = { visible = false },
                            onDismiss = { visible = false },
                        )
                    }
                    Text(if (visible) "pending" else "dismissed", Modifier.testTag("transaction-state"))
                }
            }
        }

        onNodeWithText("Typed command plan").assertTextEquals("Typed command plan")
        onNodeWithText("Network required").assertTextEquals("Network required")
        onNodeWithText("17.0.1-tem").assertTextEquals("17.0.1-tem")
        onNodeWithText("19.0.2-tem").assertTextEquals("19.0.2-tem")
        onNodeWithText("Reclaimable disk space").assertTextEquals("Reclaimable disk space")
        onNodeWithText("4.0 KiB").assertTextEquals("4.0 KiB")
        onAllNodesWithText("sdk uninstall java 17.0.1-tem").assertCountEquals(0)
        onNodeWithText("Cancel").performClick()
        onNodeWithTag("transaction-state").assertTextEquals("dismissed")
    }
}
