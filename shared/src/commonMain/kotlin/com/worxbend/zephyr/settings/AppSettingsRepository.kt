package com.worxbend.zephyr.settings

interface AppSettingsRepository {
    /** Missing settings return defaults; unreadable, corrupt, or unsupported data must throw. */
    suspend fun load(): AppSettings
    /** Return only after a complete snapshot commit is acknowledged; failure must not publish partial data. */
    suspend fun save(settings: AppSettings)
}

expect fun createAppSettingsRepository(): AppSettingsRepository
