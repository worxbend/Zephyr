package com.worxbend.zephyr.settings

import java.util.prefs.Preferences
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class JvmAppSettingsRepository(
    private val persistence: SettingsSnapshotPersistence,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AppSettingsRepository {
    constructor(
        preferences: Preferences = Preferences.userNodeForPackage(JvmAppSettingsRepository::class.java),
    ) : this(PreferencesSettingsPersistence(preferences))

    // Serializes this repository's load/commit boundary without blocking the UI dispatcher.
    private val lock = Any()

    override suspend fun load(): AppSettings = withContext(dispatcher) {
        synchronized(lock) { readValidated() }
    }

    override suspend fun save(settings: AppSettings) = withContext(dispatcher) {
        val snapshot = AppSettingsCodec.encode(settings)
        synchronized(lock) {
            // Do not turn a failed/unsupported read into defaults and overwrite its evidence.
            readValidated()
            persistence.commit(snapshot)
        }
    }

    private fun readValidated(): AppSettings = persistence.readSnapshot()?.let(AppSettingsCodec::decode)
        ?: LegacySettingsCodec.decode(persistence.readLegacyValues())
}

actual fun createAppSettingsRepository(): AppSettingsRepository = JvmAppSettingsRepository()
