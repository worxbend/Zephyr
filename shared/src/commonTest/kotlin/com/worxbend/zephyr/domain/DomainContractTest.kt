package com.worxbend.zephyr.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class DomainContractTest {
    @Test
    fun plannedTargetCommandsRequireValidatedTargets() {
        for (action in listOf(SdkmanCommandAction.Install, SdkmanCommandAction.Uninstall, SdkmanCommandAction.SetDefault)) {
            assertFailsWith<IllegalArgumentException> { PlannedSdkmanCommand(action) }
            assertFailsWith<IllegalArgumentException> { PlannedSdkmanCommand(action, "gradle", null) }
            assertFailsWith<IllegalArgumentException> { PlannedSdkmanCommand(action, "../gradle", "8.0") }
            assertFailsWith<IllegalArgumentException> { PlannedSdkmanCommand(action, "gradle", "8.0;bad") }
        }
    }

    @Test
    fun metadataCommandsCannotCarryUnrelatedTargets() {
        for (action in listOf(SdkmanCommandAction.UpdateMetadata, SdkmanCommandAction.SelfUpdate)) {
            assertEquals(action, PlannedSdkmanCommand(action).action)
            assertFailsWith<IllegalArgumentException> { PlannedSdkmanCommand(action, "gradle", "8.0") }
        }
    }

    @Test
    fun commandSuccessCannotDisagreeWithVerificationStatus() {
        for (status in CommandOutcomeStatus.entries) {
            val succeeded = status in setOf(CommandOutcomeStatus.Applied, CommandOutcomeStatus.AppliedWithWarning, CommandOutcomeStatus.AlreadySatisfied)
            assertEquals(succeeded, CommandOutcome(succeeded, "fixture", status).success)
            assertFailsWith<IllegalArgumentException> { CommandOutcome(!succeeded, "fixture", status) }
        }
    }
}
