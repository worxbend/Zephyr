package com.worxbend.zephyr.runtime

import com.worxbend.zephyr.AppServices
import com.worxbend.zephyr.settings.AppSettingsStore
import com.worxbend.zephyr.settings.SettingsCloseResult
import com.worxbend.zephyr.viewmodel.ZephyrViewModel
import kotlinx.coroutines.withTimeoutOrNull

/** The desktop owns this runtime; neither recomposition nor route disposal owns its services. */
class AppRuntime(
    val viewModel: ZephyrViewModel,
    val settings: AppSettingsStore,
    val services: AppServices,
    private val policies: AppPolicyCoordinator,
) {
    private val shutdown = AppShutdownCoordinator(settings) {
        check(policies.closeAndJoin()) { "Application policies have not finished shutting down." }
        check(viewModel.shutdownAndJoin()) { "Task shutdown or receipt persistence was not acknowledged." }
    }
    val isClosed: Boolean get() = shutdown.isClosed

    suspend fun requestClose(
        retry: Boolean = false,
        settingsTimeoutMillis: Long = 5_000L,
    ): SettingsCloseResult {
        if (retry && !isClosed) {
            withTimeoutOrNull(5_000L) { viewModel.retryOperationPersistence() }
            settings.retry()
        }
        return shutdown.requestClose(settingsTimeoutMillis)
    }
}
