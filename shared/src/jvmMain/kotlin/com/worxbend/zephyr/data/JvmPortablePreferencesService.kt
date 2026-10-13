package com.worxbend.zephyr.data

import com.worxbend.zephyr.settings.PortablePreferences
import com.worxbend.zephyr.settings.parsePortablePreferences
import com.worxbend.zephyr.settings.renderPortablePreferences
import com.worxbend.zephyr.storage.UserDocumentFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class JvmPortablePreferencesService(
    private val dialogs: DocumentDialogs = SwingDocumentDialogs,
    private val files: UserDocumentFiles = UserDocumentFiles(),
) : PortablePreferencesService {
    override suspend fun chooseAndRead(): PortablePreferences? =
        withContext(Dispatchers.IO) {
            val path = dialogs.chooseOpen("Import portable Zephyr preferences", "zephyr-prefs") ?: return@withContext null
            currentCoroutineContext().ensureActive()
            parsePortablePreferences(files.read(path))
        }

    override suspend fun chooseAndWrite(preferences: PortablePreferences): String? =
        withContext(Dispatchers.IO) {
            val path =
                dialogs.chooseSave("Export portable Zephyr preferences", "zephyr.zephyr-prefs", "zephyr-prefs")
                    ?: return@withContext null
            if (!files.write(path, renderPortablePreferences(preferences)) { dialogs.confirmOverwrite(path) }) return@withContext null
            path.fileName.toString()
        }
}

actual fun createPortablePreferencesService(): PortablePreferencesService = JvmPortablePreferencesService()
