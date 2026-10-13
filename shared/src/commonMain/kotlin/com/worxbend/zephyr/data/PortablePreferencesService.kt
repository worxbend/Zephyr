package com.worxbend.zephyr.data

import com.worxbend.zephyr.settings.PortablePreferences

interface PortablePreferencesService {
    suspend fun chooseAndRead(): PortablePreferences?

    suspend fun chooseAndWrite(preferences: PortablePreferences): String?
}

expect fun createPortablePreferencesService(): PortablePreferencesService
