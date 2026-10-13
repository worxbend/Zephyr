package com.worxbend.zephyr

import com.worxbend.zephyr.viewmodel.ZephyrRoute

internal enum class NavigationTask(
    val label: String,
) {
    Overview("Overview"),
    Installed("Installed"),
    Discover("Discover"),
    Projects("Projects"),
    Updates("Updates"),
    Storage("Storage"),
    Activity("Activity"),
}

internal val primaryNavigationTasks: List<NavigationTask> = NavigationTask.entries

internal fun navigationTaskFor(route: ZephyrRoute): NavigationTask? =
    when (route) {
        ZephyrRoute.Overview -> NavigationTask.Overview

        ZephyrRoute.InstalledJdk,
        ZephyrRoute.InstalledSdks,
        -> NavigationTask.Installed

        ZephyrRoute.BrowseJdks,
        ZephyrRoute.BrowseSdks,
        ZephyrRoute.Comparison,
        is ZephyrRoute.JdkDetail,
        is ZephyrRoute.SdkDetail,
        -> NavigationTask.Discover

        ZephyrRoute.Profiles,
        ZephyrRoute.ProjectWorkspaces,
        ZephyrRoute.ProjectImport,
        ZephyrRoute.ProjectExport,
        ZephyrRoute.EnvironmentSnapshot,
        -> NavigationTask.Projects

        ZephyrRoute.UpdateCenter -> NavigationTask.Updates

        ZephyrRoute.LocalOnly,
        ZephyrRoute.Storage,
        ZephyrRoute.BatchUninstall,
        -> NavigationTask.Storage

        ZephyrRoute.Diagnostics,
        ZephyrRoute.History,
        -> NavigationTask.Activity

        ZephyrRoute.Settings,
        ZephyrRoute.About,
        -> null
    }

internal fun navigationActivationDepth(route: ZephyrRoute): Int =
    when (route) {
        ZephyrRoute.Overview,
        ZephyrRoute.UpdateCenter,
        ZephyrRoute.Settings,
        ZephyrRoute.About,
        is ZephyrRoute.JdkDetail,
        is ZephyrRoute.SdkDetail,
        -> 1

        else -> 2
    }
