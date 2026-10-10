package com.worxbend.zephyr

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import androidx.compose.ui.unit.dp
import java.awt.Dimension
import com.worxbend.zephyr.data.createActivityStore
import com.worxbend.zephyr.data.createBrowserLauncher
import com.worxbend.zephyr.data.createClipboardService
import com.worxbend.zephyr.data.createDesktopNotificationService
import com.worxbend.zephyr.data.createDiagnosticsExporter
import com.worxbend.zephyr.data.createEnvironmentSnapshotService
import com.worxbend.zephyr.data.createOperationJournalExporter
import com.worxbend.zephyr.data.createOperationStore
import com.worxbend.zephyr.data.createPortablePreferencesService
import com.worxbend.zephyr.data.createProjectToolchainService
import com.worxbend.zephyr.data.createProxyConfigurationService
import com.worxbend.zephyr.data.createSdkmanHomeConfigurationService
import com.worxbend.zephyr.data.createSdkmanRepository
import com.worxbend.zephyr.data.createTerminalLauncher
import com.worxbend.zephyr.runtime.AppPolicyCoordinator
import com.worxbend.zephyr.runtime.AppRuntime
import com.worxbend.zephyr.settings.AppSettingsStore
import com.worxbend.zephyr.settings.SettingsCloseResult
import com.worxbend.zephyr.settings.createAppSettingsRepository
import com.worxbend.zephyr.viewmodel.ZephyrViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

fun main() {
    val runtime = createRuntime()
    application {
        val scope = rememberCoroutineScope()
        var closing by remember { mutableStateOf(false) }
        var closeInProgress by remember { mutableStateOf(false) }
        var closeFailure by remember { mutableStateOf<String?>(null) }
        fun requestClose(retry: Boolean = false) {
            if (closeInProgress) return
            closing = true
            closeInProgress = true
            closeFailure = null
            scope.launch {
                try {
                    when (runtime.requestClose(retry)) {
                        is SettingsCloseResult.Saved -> exitApplication()
                        is SettingsCloseResult.Failed -> closeFailure = "Settings have not been durably saved."
                        is SettingsCloseResult.TimedOut -> closeFailure = "Settings shutdown timed out; saving may still be in progress."
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    closeFailure = "Task shutdown or receipt persistence was not acknowledged."
                } finally {
                    closeInProgress = false
                }
            }
        }
        Window(
            onCloseRequest = { requestClose(retry = closing) },
            state = remember { WindowState(width = 1280.dp, height = 820.dp) },
            title = "Zephyr",
        ) {
            LaunchedEffect(window) { window.minimumSize = Dimension(800, 600) }
            if (closing) {
                AppShutdownScreen(closeFailure, { requestClose(retry = true) }, ::exitApplication)
            } else {
                CompositionLocalProvider(LocalAppServices provides runtime.services) {
                    App(runtime.viewModel, runtime.settings)
                }
            }
        }
    }
}

/** The only production assembly point; common code receives ports, never adapter factories. */
private fun createRuntime(): AppRuntime {
    val settings = AppSettingsStore(createAppSettingsRepository())
    val viewModel = ZephyrViewModel(
        repository = createSdkmanRepository(),
        journalExporter = createOperationJournalExporter(),
        diagnosticsExporter = createDiagnosticsExporter(),
        operationStore = createOperationStore(),
        activityStore = createActivityStore(),
    )
    val services = AppServices(
        browserLauncher = createBrowserLauncher(),
        clipboardService = createClipboardService(),
        terminalLauncher = createTerminalLauncher(),
        projectToolchainService = createProjectToolchainService(),
        environmentSnapshotService = createEnvironmentSnapshotService(),
        proxyConfigurationService = createProxyConfigurationService(),
        sdkmanHomeConfigurationService = createSdkmanHomeConfigurationService(),
        portablePreferencesService = createPortablePreferencesService(),
    )
    val policies = AppPolicyCoordinator(viewModel.state, settings, createDesktopNotificationService(), viewModel::refreshMetadataIfIdle)
    return AppRuntime(viewModel, settings, services, policies)
}
