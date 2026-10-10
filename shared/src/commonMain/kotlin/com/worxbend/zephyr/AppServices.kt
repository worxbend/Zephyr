package com.worxbend.zephyr

import com.worxbend.zephyr.data.BrowserLauncher
import com.worxbend.zephyr.data.ClipboardService
import com.worxbend.zephyr.data.EnvironmentSnapshotService
import com.worxbend.zephyr.data.PortablePreferencesService
import com.worxbend.zephyr.data.ProjectToolchainService
import com.worxbend.zephyr.data.ProxyConfigurationService
import com.worxbend.zephyr.data.SdkmanHomeConfigurationService
import com.worxbend.zephyr.data.TerminalLauncher

/** Platform ports supplied by the desktop composition root or memory-only route fixtures. */
data class AppServices(
    val browserLauncher: BrowserLauncher,
    val clipboardService: ClipboardService,
    val terminalLauncher: TerminalLauncher,
    val projectToolchainService: ProjectToolchainService,
    val environmentSnapshotService: EnvironmentSnapshotService,
    val proxyConfigurationService: ProxyConfigurationService,
    val sdkmanHomeConfigurationService: SdkmanHomeConfigurationService,
    val portablePreferencesService: PortablePreferencesService,
)
