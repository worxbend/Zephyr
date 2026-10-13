package com.worxbend.zephyr.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConnectivityTest {
    @Test
    fun routeAwareDiagnosticContainsOnlyBoundedSafeValues() {
        val diagnostic =
            ConnectivityDiagnostic(
                route = ConnectivityRouteKind.Proxy,
                checkedAtEpochMillis = 123,
                latencyMillis = MAX_CONNECTIVITY_LATENCY_MILLIS,
                outcome = ConnectivityOutcome.ProxyAuthentication,
            )

        val status = ConnectivityStatus.from(diagnostic)

        assertEquals(ConnectivityState.Offline, status.state)
        assertEquals("Proxy authentication required", status.detail)
        assertFailsWith<IllegalArgumentException> {
            diagnostic.copy(latencyMillis = MAX_CONNECTIVITY_LATENCY_MILLIS + 1)
        }
        assertEquals(0, boundedConnectivityLatencyMillis(-1))
        assertEquals(MAX_CONNECTIVITY_LATENCY_MILLIS, boundedConnectivityLatencyMillis(Long.MAX_VALUE))
    }

    @Test
    fun classifiesNetworkRequirementsByActualSdkmanBehavior() {
        assertTrue(SdkmanTransaction.Install("java", "21.0.5-tem").requiresNetwork)
        assertTrue(SdkmanTransaction.RefreshMetadata.requiresNetwork)
        assertTrue(SdkmanTransaction.SelfUpdate.requiresNetwork)
        assertTrue(SdkmanTransaction.CleanLocalOnly("java", listOf("17.0.1-tem")).requiresNetwork)
        assertTrue(
            SdkmanTransaction
                .ToolchainActivation(
                    "Backend",
                    listOf(PlannedSdkmanCommand(SdkmanCommandAction.Install, "java", "21-tem")),
                ).requiresNetwork,
        )
        assertFalse(
            SdkmanTransaction
                .ToolchainActivation(
                    "Backend",
                    listOf(PlannedSdkmanCommand(SdkmanCommandAction.SetDefault, "java", "21-tem")),
                ).requiresNetwork,
        )
        assertTrue(
            SdkmanTransaction
                .UpdateActivation(
                    listOf(UpdateActivationTarget("java", "21-tem", requiresInstall = true)),
                ).requiresNetwork,
        )
        assertFalse(
            SdkmanTransaction
                .UpdateActivation(
                    listOf(UpdateActivationTarget("java", "21-tem", requiresInstall = false)),
                ).requiresNetwork,
        )

        assertFalse(SdkmanTransaction.Uninstall("java", "17.0.1-tem").requiresNetwork)
        assertFalse(SdkmanTransaction.SetDefault("java", "21.0.5-tem").requiresNetwork)
    }
}
