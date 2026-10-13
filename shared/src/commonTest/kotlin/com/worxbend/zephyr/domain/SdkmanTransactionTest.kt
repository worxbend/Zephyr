package com.worxbend.zephyr.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SdkmanTransactionTest {
    @Test
    fun cleanupExpandsIntoAnExactTypedCommandPerVersion() {
        val transaction =
            SdkmanTransaction.CleanLocalOnly(
                candidate = "java",
                versions = listOf("17.0.1-tem", "19.0.2-tem"),
            )

        assertTrue(transaction.destructive)
        assertEquals(
            listOf(
                PlannedSdkmanCommand(SdkmanCommandAction.Uninstall, "java", "17.0.1-tem"),
                PlannedSdkmanCommand(SdkmanCommandAction.Uninstall, "java", "19.0.2-tem"),
            ),
            transaction.commands,
        )
    }

    @Test
    fun transactionPlanRejectsUnsafeCommandArguments() {
        assertFailsWith<IllegalArgumentException> {
            SdkmanTransaction.Install("../java", "21.0.5-tem")
        }
        assertFailsWith<IllegalArgumentException> {
            SdkmanTransaction.Uninstall("java", "21; rm -rf /")
        }
        assertFailsWith<IllegalArgumentException> {
            SdkmanTransaction.CleanLocalOnly("java", listOf("17-tem", "17-tem"))
        }
    }

    @Test
    fun updateActivationPlansUniqueMissingInstallsBeforeAllDefaultChanges() {
        val transaction =
            SdkmanTransaction.UpdateActivation(
                listOf(
                    UpdateActivationTarget("gradle", "8.14", requiresInstall = true),
                    UpdateActivationTarget("kotlin", "2.2.0", requiresInstall = false),
                ),
            )

        assertEquals(
            listOf(
                PlannedSdkmanCommand(SdkmanCommandAction.Install, "gradle", "8.14"),
                PlannedSdkmanCommand(SdkmanCommandAction.SetDefault, "gradle", "8.14"),
                PlannedSdkmanCommand(SdkmanCommandAction.SetDefault, "kotlin", "2.2.0"),
            ),
            transaction.commands,
        )
        assertFailsWith<IllegalArgumentException> {
            SdkmanTransaction.UpdateActivation(
                listOf(
                    UpdateActivationTarget("gradle", "8.14", true),
                    UpdateActivationTarget("gradle", "8.14", false),
                ),
            )
        }
    }
}
