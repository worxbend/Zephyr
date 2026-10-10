package com.worxbend.zephyr

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AppShutdownScreenTest {
    @Test
    fun busyShutdownCannotBeMistakenForAcknowledgedExit() = runDesktopComposeUiTest {
        setContent { AppShutdownScreen(null, {}, {}) }
        onNodeWithText("Closing Zephyr…").assertExists()
        onNodeWithText("Retry shutdown").assertDoesNotExist()
        onNodeWithText("Exit without confirmation").assertDoesNotExist()
    }

    @Test
    fun failedShutdownRequiresExplicitRetryOrUnconfirmedExit() = runDesktopComposeUiTest {
        var retries = 0
        var exits = 0
        setContent { AppShutdownScreen("Settings have not been durably saved.", { retries++ }, { exits++ }) }
        onNodeWithText("Settings have not been durably saved.").assertExists()
        onNodeWithText("Retry shutdown").performClick()
        runOnIdle { assertEquals(1, retries); assertEquals(0, exits) }
        onNodeWithText("Exit without confirmation").performClick()
        runOnIdle { assertEquals(1, exits) }
    }
}
