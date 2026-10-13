package com.worxbend.zephyr.actions

import kotlin.test.Test
import kotlin.test.assertEquals

class ZephyrActionContractTest {
    @Test
    fun versionOnePublicConstantNamesRemainSourceCompatible() {
        assertEquals(ZephyrActionIds.REFRESH_INSTALLED, ZephyrActionIds.RefreshInstalled)
        assertEquals(ZephyrActionIds.SCAN_LOCAL_ONLY, ZephyrActionIds.ScanLocalOnly)
        assertEquals(ZephyrActionIds.REFRESH_CONNECTIVITY, ZephyrActionIds.RefreshConnectivity)
        assertEquals(ZephyrActionIds.RUN_DIAGNOSTICS, ZephyrActionIds.RunDiagnostics)
        assertEquals(ZephyrActionIds.REFRESH_METADATA, ZephyrActionIds.RefreshMetadata)
        assertEquals(ZephyrActionIds.CHECK_SDKMAN_UPDATES, ZephyrActionIds.CheckSdkmanUpdates)
    }

    @Test
    fun stableActionIdsAreUniqueAndRequestsRejectUnknownSurfaceArea() {
        assertEquals(ZEPHYR_ACTIONS.size, ZEPHYR_ACTIONS.distinctBy { it.id }.size)
        assertEquals(null, ZephyrActionRequest(ZephyrActionIds.REFRESH_INSTALLED).validationError())
        assertEquals(
            null,
            ZephyrActionRequest(ZephyrActionIds.RUN_DIAGNOSTICS).validationError(),
        )
        assertEquals("Unknown action ID.", ZephyrActionRequest("plugin.unknown").validationError())
        assertEquals(
            "Action contains unsupported parameters.",
            ZephyrActionRequest(ZephyrActionIds.REFRESH_INSTALLED, parameters = mapOf("path" to "/tmp")).validationError(),
        )
    }
}
