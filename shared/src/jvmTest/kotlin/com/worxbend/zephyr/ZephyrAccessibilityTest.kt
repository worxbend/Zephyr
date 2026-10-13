package com.worxbend.zephyr

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import com.worxbend.zephyr.domain.ActivityEvent
import com.worxbend.zephyr.domain.ActivitySeverity
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class ZephyrAccessibilityTest {
    @Test
    fun pageAndSectionTitlesExposeHeadingSemantics() =
        runDesktopComposeUiTest {
            setContent {
                ZephyrTheme(darkTheme = false, reducedMotion = true) {
                    Column {
                        PageTitle("Catalog", "Available SDKs")
                        ZephyrSectionHeading("Installed versions", "Local inventory")
                    }
                }
            }
            val heading = SemanticsMatcher.keyIsDefined(SemanticsProperties.Heading)
            onNodeWithText("Catalog").assert(heading)
            onNodeWithText("Installed versions").assert(heading)
            onNodeWithText("Available SDKs").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.Heading))
        }

    @Test
    fun collectionViewSelectionIsKeyboardOperableAndAnnouncesRadioState() =
        runDesktopComposeUiTest {
            var selected by mutableStateOf("Grid")
            setContent {
                ZephyrTheme(darkTheme = false, reducedMotion = true) {
                    ZephyrSegmentedControl(listOf("Grid", "Table"), selected, { it }, { selected = it })
                }
            }
            val table = onNodeWithText("Table")
            table.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton))
            table.requestFocus().performKeyInput { pressKey(Key.Enter) }
            table.assertIsSelected()
            runOnIdle { assertEquals("Table", selected) }
            val grid = onNodeWithText("Grid")
            grid.requestFocus().performKeyInput { pressKey(Key.Spacebar) }
            grid.assertIsSelected()
            runOnIdle { assertEquals("Grid", selected) }
        }

    @Test
    fun persistentErrorAnnouncesPolitelyAndCanBeDismissedFromKeyboard() =
        runDesktopComposeUiTest {
            var dismissed: Long? = null
            val state = memoryReady().copy(activityEvents = listOf(ActivityEvent(42, 42, ActivitySeverity.Error, "Fixture error")))
            setContent { ZephyrTheme(darkTheme = false, reducedMotion = true) { MessageOverlay(state, { dismissed = it }, {}) } }
            onNode(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite)).assertExists()
            onNodeWithText("Dismiss").requestFocus().performKeyInput { pressKey(Key.Enter) }
            runOnIdle { assertEquals(42L, dismissed) }
        }
}
