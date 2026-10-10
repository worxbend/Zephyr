package com.worxbend.zephyr

import com.worxbend.zephyr.data.DesktopNotificationService
import com.worxbend.zephyr.data.SdkmanRepository
import com.worxbend.zephyr.domain.SdkmanStatus
import com.worxbend.zephyr.runtime.AppPolicyCoordinator
import com.worxbend.zephyr.runtime.AppRuntime
import com.worxbend.zephyr.settings.AppSettings
import com.worxbend.zephyr.settings.AppSettingsRepository
import com.worxbend.zephyr.settings.AppSettingsStore
import com.worxbend.zephyr.settings.SettingsCloseResult
import com.worxbend.zephyr.settings.ThemePreference
import com.worxbend.zephyr.viewmodel.ZephyrViewModel
import java.lang.reflect.Proxy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs

@OptIn(ExperimentalCoroutinesApi::class)
class AppRuntimeTest {
    @Test
    fun runtimeRetainsFailedSettingsUntilExplicitRetryAndAcknowledgement() = runTest {
        var fail = true
        var saved = AppSettings()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settings = AppSettingsStore(object : AppSettingsRepository {
            override suspend fun load() = saved
            override suspend fun save(settings: AppSettings) {
                if (fail) error("memory save failure")
                saved = settings
            }
        }, dispatcher)
        val viewModel = ZephyrViewModel(missingRepository(), dispatcher)
        val policies = AppPolicyCoordinator(viewModel.state, settings, silentNotifications, {}, dispatcher)
        val runtime = AppRuntime(viewModel, settings, MemoryRouteServices().services, policies)
        runCurrent()
        settings.update { it.copy(themePreference = ThemePreference.Dark) }
        runCurrent()
        assertIs<SettingsCloseResult.Failed>(runtime.requestClose())
        assertFalse(runtime.isClosed)
        fail = false
        assertIs<SettingsCloseResult.Saved>(runtime.requestClose(retry = true))
        assertEquals(ThemePreference.Dark, saved.themePreference)
        assertEquals(true, runtime.isClosed)
    }

    @Test
    fun runtimeTimeoutIsNotAnExitAcknowledgementAndCanDrainLater() = runTest {
        val gate = CompletableDeferred<Unit>()
        val dispatcher = StandardTestDispatcher(testScheduler)
        val settings = AppSettingsStore(object : AppSettingsRepository {
            override suspend fun load() = AppSettings()
            override suspend fun save(settings: AppSettings) { gate.await() }
        }, dispatcher)
        val viewModel = ZephyrViewModel(missingRepository(), dispatcher)
        val policies = AppPolicyCoordinator(viewModel.state, settings, silentNotifications, {}, dispatcher)
        val runtime = AppRuntime(viewModel, settings, MemoryRouteServices().services, policies)
        runCurrent()
        settings.update { it.copy(themePreference = ThemePreference.Dark) }
        runCurrent()
        val close = async { runtime.requestClose(settingsTimeoutMillis = 100) }
        runCurrent()
        advanceTimeBy(101)
        runCurrent()
        assertIs<SettingsCloseResult.TimedOut>(close.await())
        assertFalse(runtime.isClosed)
        gate.complete(Unit)
        runCurrent()
        assertIs<SettingsCloseResult.Saved>(runtime.requestClose())
    }

    private fun missingRepository() = Proxy.newProxyInstance(
        SdkmanRepository::class.java.classLoader, arrayOf(SdkmanRepository::class.java),
    ) { _, method, _ ->
        if (method.name == "detect") SdkmanStatus(false, null, reason = "Memory-only missing SDKMAN")
        else error("Unexpected repository call: ${method.name}")
    } as SdkmanRepository

    private val silentNotifications = object : DesktopNotificationService {
        override fun show(title: String, message: String) = true
    }
}
