package com.worxbend.zephyr.features.settings

import com.worxbend.zephyr.data.PortablePreferencesService
import com.worxbend.zephyr.data.ProxyConfiguration
import com.worxbend.zephyr.data.ProxyConfigurationService
import com.worxbend.zephyr.data.SdkmanHomeConfigurationService
import com.worxbend.zephyr.settings.AppSettings
import com.worxbend.zephyr.settings.applyPortablePreferences
import com.worxbend.zephyr.settings.portablePreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SettingsState(
    val proxy: ProxyConfiguration = ProxyConfiguration(),
    val port: String = "8080",
    val proxyMessage: String? = null,
    val customSdkmanHome: String? = null,
    val sdkmanHomeMessage: String? = null,
    val portablePreferencesMessage: String? = null,
    val busy: Boolean = false,
    val loaded: Boolean = false,
)

/** Owns settings IO; draft password is passed once, never retained in observable state. */
class SettingsPresenter(
    private val proxyService: ProxyConfigurationService,
    private val homeService: SdkmanHomeConfigurationService,
    private val preferencesService: PortablePreferencesService,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + SupervisorJob(parentScope.coroutineContext[Job]))
    private val mutableState = MutableStateFlow(SettingsState())
    val state = mutableState.asStateFlow()

    init { load() }

    fun editProxy(transform: (ProxyConfiguration) -> ProxyConfiguration) {
        if (!state.value.busy && state.value.loaded) mutableState.update { it.copy(proxy = transform(it.proxy)) }
    }
    fun editPort(value: String) {
        if (!state.value.busy && state.value.loaded) mutableState.update { it.copy(port = value.filter(Char::isDigit).take(5)) }
    }
    fun load() = work("load") {
        val configuration = proxyService.load()
        val home = homeService.configuredPath()
        mutableState.update { it.copy(proxy = configuration, port = configuration.port.toString(), customSdkmanHome = home, loaded = true) }
    }
    fun saveProxy(password: String?) {
        if (!state.value.loaded) return
        val draft = state.value
        work("proxy") {
            val result = proxyService.save(draft.proxy.copy(port = draft.port.toIntOrNull() ?: 0), password)
            mutableState.update { it.copy(proxyMessage = result.message) }
            if (result.success || result.settingsSaved || result.recoveryRequired) reloadProxy()
        }
    }
    fun clearPassword() = work("proxy") {
        val result = proxyService.clearPassword()
        mutableState.update { it.copy(proxyMessage = result.message) }
        if (result.success || result.settingsSaved || result.recoveryRequired) reloadProxy()
    }
    private suspend fun reloadProxy() {
        val configuration = proxyService.load()
        mutableState.update { it.copy(proxy = configuration, port = configuration.port.toString()) }
    }
    fun chooseHome() = work("home") {
        homeService.chooseAndSave()?.let { result ->
            mutableState.update { it.copy(sdkmanHomeMessage = result.message, customSdkmanHome = if (result.success) result.path else it.customSdkmanHome) }
        }
    }
    fun clearHome() = work("home") {
        val result = homeService.clear()
        mutableState.update { it.copy(sdkmanHomeMessage = result.message, customSdkmanHome = if (result.success) null else it.customSdkmanHome) }
    }
    fun exportPreferences(settings: AppSettings) = work("preferences") {
        preferencesService.chooseAndWrite(settings.portablePreferences())?.let { file ->
            mutableState.update { it.copy(portablePreferencesMessage = "Exported portable preferences to $file.") }
        }
    }
    fun importPreferences(onSettingsChange: ((AppSettings) -> AppSettings) -> Unit) = work("preferences") {
        preferencesService.chooseAndRead()?.let { portable ->
            onSettingsChange { it.applyPortablePreferences(portable) }
            mutableState.update { it.copy(portablePreferencesMessage = "Imported portable preferences.") }
        }
    }
    private fun work(section: String, action: suspend () -> Unit) {
        if (state.value.busy) return
        mutableState.update { it.copy(busy = true) }
        scope.launch {
            try { action() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                val message = failure.message ?: "Settings operation failed."
                mutableState.update {
                    when (section) {
                        "home" -> it.copy(sdkmanHomeMessage = message)
                        "preferences" -> it.copy(portablePreferencesMessage = message)
                        else -> it.copy(proxyMessage = message)
                    }
                }
            } finally { mutableState.update { it.copy(busy = false) } }
        }
    }
    fun close() = scope.cancel()
}
