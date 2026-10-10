package com.worxbend.zephyr.runtime

import com.worxbend.zephyr.settings.AppSettingsStore
import com.worxbend.zephyr.settings.SettingsCloseResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Exit requires acknowledgement; errors leave a visible retry path, not an implicit discard. */
class AppShutdownCoordinator(
    private val settings: AppSettingsStore,
    private val quiesceOperations: suspend () -> Unit,
) {
    private val mutex = Mutex()
    private var acknowledgement: SettingsCloseResult.Saved? = null
    val isClosed: Boolean get() = acknowledgement != null

    suspend fun requestClose(timeoutMillis: Long = 5_000): SettingsCloseResult = mutex.withLock {
        acknowledgement?.let { return@withLock it }
        quiesceOperations()
        settings.flushAndClose(timeoutMillis).also { result ->
            if (result is SettingsCloseResult.Saved) acknowledgement = result
        }
    }
}
