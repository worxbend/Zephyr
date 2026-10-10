package com.worxbend.zephyr.sdkman

import okio.FileSystem
import okio.Path

/** The configured home (including home aliases) is canonicalized once by the
 * repository. No symlink below that trust boundary is an installation ancestor.
 * Revalidate at each read/admission/postcondition; SDKMAN's external shell is not
 * a descriptor-relative API, so adversarial concurrent renames remain out of scope.
 */
internal class SdkmanFilesystemInspector(private val fileSystem: FileSystem, private val home: Path) {
    fun safeRoot(): Boolean = runCatching {
        fileSystem.canonicalize(home) == home && ordinaryDirectory(home / "candidates")
    }.getOrDefault(false)

    fun safeCandidate(candidate: String): Boolean = runCatching {
        if (!safeRoot()) return false
        val metadata = fileSystem.metadataOrNull(home / "candidates" / candidate)
        metadata == null || (metadata.isDirectory && metadata.symlinkTarget == null)
    }.getOrDefault(false)

    fun versionState(path: Path): PathPostcondition = runCatching {
        val candidatePath = path.parent ?: return PathPostcondition.Indeterminate
        if (candidatePath.parent != home / "candidates" || !safeCandidate(candidatePath.name)) {
            return PathPostcondition.Indeterminate
        }
        val metadata = fileSystem.metadataOrNull(path) ?: return PathPostcondition.Unsatisfied
        if (metadata.isDirectory && metadata.symlinkTarget == null) PathPostcondition.Satisfied
        else PathPostcondition.Indeterminate
    }.getOrDefault(PathPostcondition.Indeterminate)

    private fun ordinaryDirectory(path: Path): Boolean = fileSystem.metadataOrNull(path)?.let {
        it.isDirectory && it.symlinkTarget == null
    } == true
}

internal enum class PathPostcondition { Satisfied, Unsatisfied, Indeterminate }
