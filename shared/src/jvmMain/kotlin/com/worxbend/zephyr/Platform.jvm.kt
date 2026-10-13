package com.worxbend.zephyr

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.TimeUnit

actual suspend fun isSystemDarkMode(): Boolean =
    withContext(Dispatchers.IO) {
        detectSystemDarkMode()
    }

actual suspend fun isSystemReducedMotion(): Boolean =
    withContext(Dispatchers.IO) {
        readGsettings("org.gnome.desktop.interface", "enable-animations")
            ?.trim()
            ?.equals("false", ignoreCase = true)
            ?: false
    }

private fun detectSystemDarkMode(): Boolean {
    val gtkTheme = System.getenv("GTK_THEME")
    if (!gtkTheme.isNullOrBlank() && gtkTheme.contains(":dark", ignoreCase = true)) return true
    return readGsettings("org.gnome.desktop.interface", "color-scheme")
        ?.contains("dark", ignoreCase = true)
        ?: false
}

private fun readGsettings(
    schema: String,
    key: String,
): String? {
    val gsettings = File("/usr/bin/gsettings")
    if (!gsettings.canExecute()) return null

    return try {
        val process =
            ProcessBuilder(gsettings.absolutePath, "get", schema, key)
                .redirectErrorStream(true)
                .start()
        if (!process.waitFor(GSETTINGS_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            process.destroyForcibly()
            null
        } else {
            process.inputStream.bufferedReader().use { it.readText() }
        }
    } catch (_: Exception) {
        null
    }
}

private const val GSETTINGS_TIMEOUT_MILLIS = 250L
