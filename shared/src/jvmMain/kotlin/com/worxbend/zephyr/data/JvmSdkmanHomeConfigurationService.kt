package com.worxbend.zephyr.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicReference
import java.util.prefs.Preferences
import javax.swing.JFileChooser
import javax.swing.SwingUtilities

internal class JvmSdkmanHomeConfigurationService(
    private val preferences: Preferences = Preferences.userNodeForPackage(JvmSdkmanHomeConfigurationService::class.java),
    private val chooseDirectory: () -> Path? = ::chooseSdkmanDirectory,
) : SdkmanHomeConfigurationService {
    override suspend fun configuredPath(): String? =
        withContext(Dispatchers.IO) {
            configuredHome()?.toString()
        }

    override suspend fun chooseAndSave(): SdkmanHomeSelectionResult? =
        withContext(Dispatchers.IO) {
            val selected = chooseDirectory()?.toAbsolutePath()?.normalize() ?: return@withContext null
            validateSdkmanHome(selected)?.let {
                return@withContext SdkmanHomeSelectionResult(false, message = it)
            }
            preferences.put(HOME_KEY, selected.toString())
            preferences.flush()
            SdkmanHomeSelectionResult(true, selected.toString(), "Custom SDKMAN home saved. Restart Zephyr to apply it.")
        }

    override suspend fun clear(): SdkmanHomeSelectionResult =
        withContext(Dispatchers.IO) {
            preferences.remove(HOME_KEY)
            preferences.flush()
            SdkmanHomeSelectionResult(true, message = "Custom SDKMAN home cleared. Restart Zephyr to use automatic discovery.")
        }

    internal fun resolveHome(): Path =
        configuredHome()
            ?: System.getenv("SDKMAN_DIR")?.takeIf(String::isNotBlank)?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".sdkman")

    private fun configuredHome(): Path? =
        preferences
            .get(HOME_KEY, "")
            .takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?.toAbsolutePath()
            ?.normalize()

    private companion object {
        const val HOME_KEY = "custom-sdkman-home"
    }
}

internal fun validateSdkmanHome(path: Path): String? =
    when {
        !Files.isDirectory(path) -> {
            "The selected path is not a directory."
        }

        !Files.isRegularFile(path.resolve("bin").resolve("sdkman-init.sh")) -> {
            "The selected directory does not contain bin/sdkman-init.sh."
        }

        !Files.isDirectory(path.resolve("candidates")) -> {
            "The selected directory does not contain a candidates directory."
        }

        else -> {
            null
        }
    }

private fun chooseSdkmanDirectory(): Path? =
    sdkmanHomeOnEdt {
        val chooser =
            JFileChooser().apply {
                dialogTitle = "Choose SDKMAN home"
                fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
                isAcceptAllFileFilterUsed = false
            }
        if (chooser.showOpenDialog(null) == JFileChooser.APPROVE_OPTION) chooser.selectedFile.toPath() else null
    }

private fun <T> sdkmanHomeOnEdt(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) return block()
    val result = AtomicReference<T>()
    SwingUtilities.invokeAndWait { result.set(block()) }
    return result.get()
}

actual fun createSdkmanHomeConfigurationService(): SdkmanHomeConfigurationService = JvmSdkmanHomeConfigurationService()
