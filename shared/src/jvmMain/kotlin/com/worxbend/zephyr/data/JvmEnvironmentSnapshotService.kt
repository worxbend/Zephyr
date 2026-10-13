package com.worxbend.zephyr.data

import com.worxbend.zephyr.storage.UserDocumentFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal class JvmEnvironmentSnapshotService(
    private val dialogs: DocumentDialogs = SwingDocumentDialogs,
    private val files: UserDocumentFiles = UserDocumentFiles(),
) : EnvironmentSnapshotService {
    override suspend fun chooseAndRead(): EnvironmentSnapshot? =
        withContext(Dispatchers.IO) {
            val path =
                dialogs.chooseOpen("Choose a Zephyr environment snapshot", "zephyr-snapshot")
                    ?: return@withContext null
            currentCoroutineContext().ensureActive()
            parseEnvironmentSnapshot(files.read(path))
        }

    override suspend fun chooseAndWrite(snapshot: EnvironmentSnapshot): EnvironmentSnapshotExportResult? =
        withContext(Dispatchers.IO) {
            require(snapshot.candidates.isNotEmpty()) { "There are no installed versions or defaults to export." }
            val path =
                dialogs.chooseSave("Export Zephyr environment snapshot", "environment.zephyr-snapshot", "zephyr-snapshot")
                    ?: return@withContext null
            if (!files.write(path, renderEnvironmentSnapshot(snapshot)) { dialogs.confirmOverwrite(path) }) return@withContext null
            EnvironmentSnapshotExportResult(
                fileName = path.fileName.toString(),
                candidateCount = snapshot.candidates.size,
                versionCount = snapshot.candidates.sumOf { it.installedVersions.size },
            )
        }
}

actual fun createEnvironmentSnapshotService(): EnvironmentSnapshotService = JvmEnvironmentSnapshotService()
