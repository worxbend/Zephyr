package com.worxbend.zephyr.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsWriterLifecycleTest {
    @Test
    fun shutdownDrainsBlockedSaveAndAllPreviouslyAcceptedEdits() = runTest {
        val release = CompletableDeferred<Unit>()
        val saved = mutableListOf<AppSettings>()
        val repository = repository(save = { release.await(); saved += it })
        val store = AppSettingsStore(repository, StandardTestDispatcher(testScheduler))
        runCurrent()
        store.update { it.copy(themePreference = ThemePreference.Dark) }
        runCurrent()
        assertEquals(SettingsSaveStatus.Saving(1, 0), store.saveStatus.value)
        store.update { it.copy(uiDensity = UiDensity.Comfortable) }
        val closing = async { store.flushAndClose() }
        runCurrent()
        assertFalse(closing.isCompleted)
        store.update { it.copy(themePreference = ThemePreference.Light) }
        release.complete(Unit)
        runCurrent()
        assertEquals(SettingsCloseResult.Saved(2), closing.await())
        assertEquals(2, saved.size)
        assertEquals(ThemePreference.Dark, saved.last().themePreference)
        assertEquals(UiDensity.Comfortable, saved.last().uiDensity)
        assertEquals(SettingsSaveStatus.Saved(2), store.saveStatus.value)
        assertEquals(SettingsCloseResult.Saved(2), store.flushAndClose())
    }

    @Test
    fun failedDrainRetainsDirtyRevisionAndCanBeRetriedWithoutChangingValues() = runTest {
        var fail = true
        var attempts = 0
        var durable = AppSettings()
        val store = AppSettingsStore(repository(save = {
            attempts++
            if (fail) error("injected")
            durable = it
        }), StandardTestDispatcher(testScheduler))
        runCurrent()
        store.update { it.copy(themePreference = ThemePreference.Dark) }
        runCurrent()
        val failed = SettingsSaveStatus.Failed(1, 0, SettingsFailureReason.Save)
        assertEquals(failed, store.saveStatus.value)
        assertEquals(SettingsCloseResult.Failed(failed), store.flushAndClose())
        assertEquals(1, attempts)
        fail = false
        store.retry()
        runCurrent()
        assertEquals(2, attempts)
        assertEquals(store.state.value, durable)
        assertEquals(SettingsCloseResult.Saved(1), store.flushAndClose())
    }

    @Test
    fun timeoutReturnsWithoutClaimingDurabilityOrCancellingAnInFlightWrite() = runTest {
        val release = CompletableDeferred<Unit>()
        var persisted = false
        val store = AppSettingsStore(repository(save = { release.await(); persisted = true }),
            StandardTestDispatcher(testScheduler))
        runCurrent()
        store.update { it.copy(themePreference = ThemePreference.Dark) }
        runCurrent()
        assertIs<SettingsCloseResult.TimedOut>(store.flushAndClose(timeoutMillis = 50))
        assertFalse(persisted)
        assertIs<SettingsCloseResult.TimedOut>(store.flushAndClose(timeoutMillis = 50))
        release.complete(Unit)
        runCurrent()
        assertTrue(persisted)
        assertEquals(SettingsCloseResult.Saved(1), store.flushAndClose())
    }

    @Test
    fun loadFailureCannotReplaceUnreadableSettingsAndRetryReloads() = runTest {
        var unreadable = true
        var saves = 0
        val initial = AppSettings(themePreference = ThemePreference.Dark)
        val store = AppSettingsStore(repository(load = {
            if (unreadable) error("corrupt") else initial
        }, save = { saves++ }), StandardTestDispatcher(testScheduler))
        runCurrent()
        store.update { it.copy(themePreference = ThemePreference.Light) }
        runCurrent()
        val failed = SettingsSaveStatus.Failed(0, 0, SettingsFailureReason.Load)
        assertEquals(SettingsCloseResult.Failed(failed), store.flushAndClose())
        assertEquals(0, saves)
        unreadable = false
        store.retry()
        runCurrent()
        assertEquals(initial, store.state.value)
        assertEquals(SettingsCloseResult.Saved(0), store.flushAndClose())
    }

    @Test
    fun closeCompatibilityApiDrainsRatherThanCancelsQueuedStartupUpdates() = runTest {
        val loaded = CompletableDeferred<AppSettings>()
        val saved = mutableListOf<AppSettings>()
        val store = AppSettingsStore(repository(load = { loaded.await() }, save = { saved += it }),
            StandardTestDispatcher(testScheduler))
        store.update { it.copy(themePreference = ThemePreference.Dark) }
        store.update { it.copy(uiDensity = UiDensity.Comfortable) }
        store.close()
        runCurrent()
        loaded.complete(AppSettings(textScale = TextScale.Percent150))
        runCurrent()
        assertEquals(2, saved.size)
        assertEquals(TextScale.Percent150, saved.last().textScale)
        assertEquals(SettingsCloseResult.Saved(2), store.flushAndClose())
    }

    @Test
    fun cancellingFlushCallerDoesNotCancelWriterOrLoseItsAcknowledgement() = runTest {
        val release = CompletableDeferred<Unit>()
        val store = AppSettingsStore(repository(save = { release.await() }), StandardTestDispatcher(testScheduler))
        store.update { it.copy(themePreference = ThemePreference.Dark) }
        runCurrent()
        val close = async { store.flushAndClose() }
        runCurrent()
        close.cancel()
        release.complete(Unit)
        runCurrent()
        assertEquals(SettingsCloseResult.Saved(1), store.flushAndClose())
    }

    @Test
    fun invalidEditCanBeAcknowledgedAfterExplicitRetryWithoutWritingUnchangedSettings() = runTest {
        var saves = 0
        val store = AppSettingsStore(repository(save = { saves++ }), StandardTestDispatcher(testScheduler))
        runCurrent()
        store.update { error("invalid edit") }
        runCurrent()
        assertIs<SettingsSaveStatus.Failed>(store.saveStatus.value)
        store.retry()
        runCurrent()
        assertEquals(SettingsSaveStatus.Saved(0), store.saveStatus.value)
        assertEquals(0, saves)
        assertEquals(SettingsCloseResult.Saved(0), store.flushAndClose())
    }

    private fun repository(
        load: suspend () -> AppSettings = { AppSettings() },
        save: suspend (AppSettings) -> Unit,
    ) = object : AppSettingsRepository {
        override suspend fun load() = load.invoke()
        override suspend fun save(settings: AppSettings) = save.invoke(settings)
    }
}
