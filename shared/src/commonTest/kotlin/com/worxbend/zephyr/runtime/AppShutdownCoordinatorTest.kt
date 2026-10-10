package com.worxbend.zephyr.runtime

import com.worxbend.zephyr.settings.AppSettings
import com.worxbend.zephyr.settings.AppSettingsRepository
import com.worxbend.zephyr.settings.AppSettingsStore
import com.worxbend.zephyr.settings.SettingsCloseResult
import com.worxbend.zephyr.settings.ThemePreference
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class AppShutdownCoordinatorTest {
    @Test
    fun failedSettingsSaveNeverAcknowledgesExitAndCanBeRetried() = runTest {
        var fail = true
        val store = AppSettingsStore(object : AppSettingsRepository {
            override suspend fun load() = AppSettings()
            override suspend fun save(settings: AppSettings) { if (fail) error("injected save failure") }
        }, StandardTestDispatcher(testScheduler))
        runCurrent()
        store.update { it.copy(themePreference = ThemePreference.Dark) }
        runCurrent()
        var quiesced = 0
        val shutdown = AppShutdownCoordinator(store, { quiesced++ })
        assertIs<SettingsCloseResult.Failed>(shutdown.requestClose())
        assertFalse(shutdown.isClosed)
        fail = false
        store.retry()
        runCurrent()
        assertIs<SettingsCloseResult.Saved>(shutdown.requestClose())
        assertEquals(2, quiesced)
        assertEquals(true, shutdown.isClosed)
    }

    @Test
    fun operationShutdownFailureDoesNotCloseTheSettingsWriter() = runTest {
        val store = AppSettingsStore(object : AppSettingsRepository {
            override suspend fun load() = AppSettings()
            override suspend fun save(settings: AppSettings) = Unit
        }, StandardTestDispatcher(testScheduler))
        runCurrent()
        val shutdown = AppShutdownCoordinator(store, { error("operation shutdown failed") })
        kotlin.test.assertFailsWith<IllegalStateException> { shutdown.requestClose() }
        store.update { it.copy(themePreference = ThemePreference.Dark) }
        runCurrent()
        assertEquals(ThemePreference.Dark, store.state.value.themePreference)
        assertFalse(shutdown.isClosed)
        store.close()
        runCurrent()
    }
}
