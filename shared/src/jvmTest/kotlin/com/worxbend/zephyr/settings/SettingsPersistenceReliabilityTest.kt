package com.worxbend.zephyr.settings

import java.util.prefs.AbstractPreferences
import java.util.prefs.Preferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SettingsPersistenceReliabilityTest {
    @Test
    fun oversizedSnapshotNeverChangesPreviouslySavedSettings() = runBlocking {
        val preferences = MemorySettingsPreferences()
        val repository = JvmAppSettingsRepository(preferences)
        val previous = AppSettings(themePreference = ThemePreference.Dark)
        repository.save(previous)
        val oversized = previous.copy(
            themePreference = ThemePreference.Light,
            favoriteCandidates = setOf("x".repeat(Preferences.MAX_VALUE_LENGTH + 1)),
        )
        assertFailsWith<IllegalArgumentException> { repository.save(oversized) }
        assertEquals(previous, repository.load())
    }

    @Test
    fun unchangedUpdateRetriesFailedSave() = runBlocking {
        var attempts = 0
        var durable = AppSettings()
        val repository = object : AppSettingsRepository {
            override suspend fun load() = durable
            override suspend fun save(settings: AppSettings) {
                attempts++
                if (attempts == 1) error("injected failure")
                durable = settings
            }
        }
        val store = AppSettingsStore(repository, Dispatchers.Unconfined)
        store.update { it.copy(themePreference = ThemePreference.Dark) }
        store.update { it.copy(themePreference = ThemePreference.Dark) }
        assertEquals(2, attempts)
        assertEquals(store.state.value, durable)
        store.close()
    }
}

internal class MemorySettingsPreferences(
    parent: AbstractPreferences? = null,
    name: String = "",
    private val failure: (() -> Unit)? = null,
) : AbstractPreferences(parent, name) {
    val values = linkedMapOf<String, String>()
    private val children = linkedMapOf<String, MemorySettingsPreferences>()
    override fun putSpi(key: String, value: String) { failure?.invoke(); values[key] = value }
    override fun getSpi(key: String): String? = values[key]
    override fun removeSpi(key: String) { values.remove(key) }
    override fun removeNodeSpi() { values.clear() }
    override fun keysSpi(): Array<String> = values.keys.toTypedArray()
    override fun childrenNamesSpi(): Array<String> = children.keys.toTypedArray()
    override fun childSpi(name: String): AbstractPreferences =
        children.getOrPut(name) { MemorySettingsPreferences(this, name, failure) }
    override fun syncSpi() = Unit
    override fun flushSpi() { failure?.invoke() }
}
