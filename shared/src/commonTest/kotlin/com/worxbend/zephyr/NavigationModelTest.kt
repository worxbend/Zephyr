package com.worxbend.zephyr

import com.worxbend.zephyr.viewmodel.ZephyrRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NavigationModelTest {
    @Test
    fun exposesSevenPrimaryTaskAreas() {
        assertEquals(7, primaryNavigationTasks.size)
        assertEquals(primaryNavigationTasks.distinct(), primaryNavigationTasks)
    }

    @Test
    fun everyDestinationRemainsReachableWithinTwoActivations() {
        val routes =
            listOf(
                ZephyrRoute.Overview,
                ZephyrRoute.InstalledJdk,
                ZephyrRoute.InstalledSdks,
                ZephyrRoute.BrowseJdks,
                ZephyrRoute.BrowseSdks,
                ZephyrRoute.LocalOnly,
                ZephyrRoute.Storage,
                ZephyrRoute.UpdateCenter,
                ZephyrRoute.BatchUninstall,
                ZephyrRoute.Profiles,
                ZephyrRoute.ProjectWorkspaces,
                ZephyrRoute.ProjectImport,
                ZephyrRoute.ProjectExport,
                ZephyrRoute.EnvironmentSnapshot,
                ZephyrRoute.Comparison,
                ZephyrRoute.Diagnostics,
                ZephyrRoute.History,
                ZephyrRoute.Settings,
                ZephyrRoute.About,
                ZephyrRoute.JdkDetail("java"),
                ZephyrRoute.SdkDetail("gradle"),
            )

        assertTrue(routes.all { navigationActivationDepth(it) in 1..2 })
        assertTrue(
            routes.filterNot { it == ZephyrRoute.Settings || it == ZephyrRoute.About }.all {
                navigationTaskFor(it) != null
            },
        )
    }
}
