package com.worxbend.zephyr.data

import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.prefs.Preferences
import kotlin.io.path.createDirectories
import kotlin.io.path.createFile
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmSdkmanHomeConfigurationServiceTest {
    @OptIn(kotlin.io.path.ExperimentalPathApi::class)
    @Test
    fun validatesRequiredSdkmanStructureBeforePersisting() =
        runBlocking {
            val directory = Files.createTempDirectory("zephyr-sdkman-home")
            val preferences = Preferences.userRoot().node("/com/worxbend/zephyr/tests/home-${System.nanoTime()}")
            try {
                val invalid = JvmSdkmanHomeConfigurationService(preferences) { directory }
                assertFalse(invalid.chooseAndSave()!!.success)
                assertEquals(null, invalid.configuredPath())

                directory.resolve("bin").createDirectories()
                directory.resolve("bin/sdkman-init.sh").createFile()
                directory.resolve("candidates").createDirectories()
                val result = invalid.chooseAndSave()!!

                assertTrue(result.success)
                assertEquals(directory.toAbsolutePath().normalize().toString(), invalid.configuredPath())
                invalid.clear()
                assertEquals(null, invalid.configuredPath())
            } finally {
                preferences.removeNode()
                Preferences.userRoot().flush()
                directory.deleteRecursively()
            }
        }
}
