package com.worxbend.zephyr.data

import com.worxbend.zephyr.settings.ProjectWorkspaceReference
import com.worxbend.zephyr.storage.UserDocumentFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal class JvmProjectToolchainService(
    private val dialogs: DocumentDialogs = SwingDocumentDialogs,
    private val files: UserDocumentFiles = UserDocumentFiles(),
) : ProjectToolchainService {
    override suspend fun chooseAndRead(): SdkmanRcDocument? =
        withContext(Dispatchers.IO) {
            val path = chooseSdkmanRcPath() ?: return@withContext null
            readWorkspace(referenceFor(path)).sdkmanRc
        }

    override suspend fun chooseWorkspace(): ProjectWorkspaceDocument? =
        withContext(Dispatchers.IO) {
            val path = chooseSdkmanRcPath() ?: return@withContext null
            readWorkspace(referenceFor(path))
        }

    override suspend fun readWorkspace(reference: ProjectWorkspaceReference): ProjectWorkspaceDocument =
        withContext(Dispatchers.IO) {
            val path = validateWorkspacePath(Path.of(reference.sdkmanRcPath))
            ProjectWorkspaceDocument(
                reference = referenceFor(path),
                projectDirectory = requireNotNull(path.parent).toString(),
                sdkmanRc = parseSdkmanRc(path.fileName.toString(), files.read(path)),
            )
        }

    override suspend fun chooseAndWrite(targets: List<com.worxbend.zephyr.domain.InstallTarget>): SdkmanRcExportResult? =
        withContext(Dispatchers.IO) {
            require(targets.isNotEmpty()) { "Select at least one default to export." }
            val path =
                dialogs.chooseSave("Export SDKMAN project file", ".sdkmanrc", "sdkmanrc")
                    ?: return@withContext null
            if (!files.write(path, renderSdkmanRc(targets)) { dialogs.confirmOverwrite(path) }) return@withContext null
            SdkmanRcExportResult(path.fileName.toString(), targets.size)
        }

    private fun chooseSdkmanRcPath(): Path? {
        val selected = dialogs.chooseOpen("Choose a project .sdkmanrc", "sdkmanrc") ?: return null
        return validateWorkspacePath(selected)
    }
}

actual fun createProjectToolchainService(): ProjectToolchainService = JvmProjectToolchainService()

private fun referenceFor(path: Path): ProjectWorkspaceReference {
    val validated = validateWorkspacePath(path)
    val projectName =
        validated.parent
            ?.fileName
            ?.toString()
            ?.takeIf(String::isNotBlank)
            ?: "SDKMAN project"
    return ProjectWorkspaceReference(
        sdkmanRcPath = validated.toString(),
        displayName = projectName,
    )
}

internal fun validateWorkspacePath(input: Path): Path {
    val normalized = input.toAbsolutePath().normalize()
    require(normalized.fileName?.toString() == ".sdkmanrc") {
        "Choose a file named .sdkmanrc."
    }
    var current = requireNotNull(normalized.root)
    normalized.forEach { segment ->
        current = current.resolve(segment)
        require(!Files.isSymbolicLink(current)) {
            "Project workspace paths cannot contain symbolic links."
        }
    }
    require(Files.isRegularFile(normalized, LinkOption.NOFOLLOW_LINKS)) {
        "The selected .sdkmanrc is not a regular file."
    }
    return normalized.toRealPath(LinkOption.NOFOLLOW_LINKS)
}
