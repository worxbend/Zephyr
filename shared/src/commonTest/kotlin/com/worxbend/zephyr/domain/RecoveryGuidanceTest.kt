package com.worxbend.zephyr.domain

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecoveryGuidanceTest {
    @Test
    fun everyTransactionProvidesActionableRecoverySteps() {
        val transactions =
            listOf(
                SdkmanTransaction.Install("java", "21.0.5-tem"),
                SdkmanTransaction.Uninstall("java", "17.0.1-tem"),
                SdkmanTransaction.SetDefault("java", "21.0.5-tem"),
                SdkmanTransaction.CleanLocalOnly("java", listOf("17.0.1-tem")),
                SdkmanTransaction.RefreshMetadata,
                SdkmanTransaction.SelfUpdate,
            )

        transactions.forEach { transaction ->
            val guidance = transaction.recoveryGuidance()
            assertTrue(guidance.title.isNotBlank())
            assertTrue(guidance.steps.size >= 2)
            assertTrue(guidance.actions.isNotEmpty())
            assertContains(guidance.actions, RecoveryAction.OpenDiagnostics)
        }
    }

    @Test
    fun cleanupRecoveryRequiresARescanBeforeRetry() {
        val actions =
            SdkmanTransaction
                .CleanLocalOnly(
                    "java",
                    listOf("17.0.1-tem"),
                ).recoveryGuidance()
                .actions

        assertEquals(RecoveryAction.ScanLocalOnly, actions.first())
        assertContains(actions, RecoveryAction.Retry)
    }
}
