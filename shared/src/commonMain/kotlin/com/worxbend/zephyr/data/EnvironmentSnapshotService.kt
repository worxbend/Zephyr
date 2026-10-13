package com.worxbend.zephyr.data

import com.worxbend.zephyr.domain.Candidate
import com.worxbend.zephyr.domain.PlannedSdkmanCommand
import com.worxbend.zephyr.domain.SdkmanCommandAction
import com.worxbend.zephyr.domain.isValidSdkmanCandidateName
import com.worxbend.zephyr.domain.isValidSdkmanVersion

data class EnvironmentSnapshot(
    val schemaVersion: Int = CURRENT_SNAPSHOT_SCHEMA,
    val capturedAtEpochMillis: Long,
    val candidates: List<SnapshotCandidate>,
)

data class SnapshotCandidate(
    val candidate: String,
    val defaultVersion: String?,
    val installedVersions: List<String>,
)

data class SnapshotCandidateDiff(
    val candidate: String,
    val previousDefault: String?,
    val currentDefault: String?,
    val addedVersions: List<String>,
    val removedVersions: List<String>,
) {
    val hasChanges: Boolean
        get() = previousDefault != currentDefault || addedVersions.isNotEmpty() || removedVersions.isNotEmpty()
}

data class EnvironmentSnapshotExportResult(
    val fileName: String,
    val candidateCount: Int,
    val versionCount: Int,
)

interface EnvironmentSnapshotService {
    suspend fun chooseAndRead(): EnvironmentSnapshot?

    suspend fun chooseAndWrite(snapshot: EnvironmentSnapshot): EnvironmentSnapshotExportResult?
}

expect fun createEnvironmentSnapshotService(): EnvironmentSnapshotService

fun captureEnvironmentSnapshot(
    candidates: List<Candidate>,
    capturedAtEpochMillis: Long,
): EnvironmentSnapshot =
    EnvironmentSnapshot(
        capturedAtEpochMillis = capturedAtEpochMillis,
        candidates =
            candidates
                .map { candidate ->
                    SnapshotCandidate(
                        candidate = candidate.name,
                        defaultVersion = candidate.defaultVersion,
                        installedVersions =
                            candidate.installedVersions
                                .asSequence()
                                .filter { it.isInstalled }
                                .map { it.version }
                                .distinct()
                                .sorted()
                                .toList(),
                    )
                }.filter { it.defaultVersion != null || it.installedVersions.isNotEmpty() }
                .sortedBy(SnapshotCandidate::candidate),
    )

fun diffEnvironmentSnapshots(
    previous: EnvironmentSnapshot,
    current: EnvironmentSnapshot,
): List<SnapshotCandidateDiff> {
    val previousByName = previous.candidates.associateBy(SnapshotCandidate::candidate)
    val currentByName = current.candidates.associateBy(SnapshotCandidate::candidate)
    return (previousByName.keys + currentByName.keys)
        .sorted()
        .map { candidate ->
            val before = previousByName[candidate]
            val after = currentByName[candidate]
            val beforeVersions = before?.installedVersions.orEmpty().toSet()
            val afterVersions = after?.installedVersions.orEmpty().toSet()
            SnapshotCandidateDiff(
                candidate = candidate,
                previousDefault = before?.defaultVersion,
                currentDefault = after?.defaultVersion,
                addedVersions = (afterVersions - beforeVersions).sorted(),
                removedVersions = (beforeVersions - afterVersions).sorted(),
            )
        }.filter(SnapshotCandidateDiff::hasChanges)
}

fun planSnapshotRestore(
    snapshot: EnvironmentSnapshot,
    currentCandidates: List<Candidate>,
): List<PlannedSdkmanCommand> {
    val currentByName = currentCandidates.associateBy(Candidate::name)
    val installs =
        snapshot.candidates
            .sortedBy(SnapshotCandidate::candidate)
            .flatMap { target ->
                val installed =
                    currentByName[target.candidate]
                        ?.installedVersions
                        .orEmpty()
                        .filter { it.isInstalled }
                        .map { it.version }
                        .toSet()
                target.installedVersions
                    .filterNot { it in installed }
                    .sorted()
                    .map { version ->
                        PlannedSdkmanCommand(SdkmanCommandAction.Install, target.candidate, version)
                    }
            }
    val defaults =
        snapshot.candidates
            .sortedBy(SnapshotCandidate::candidate)
            .mapNotNull { target ->
                target.defaultVersion
                    ?.takeIf { it != currentByName[target.candidate]?.defaultVersion }
                    ?.let { version ->
                        PlannedSdkmanCommand(SdkmanCommandAction.SetDefault, target.candidate, version)
                    }
            }
    return installs + defaults
}

fun renderEnvironmentSnapshot(snapshot: EnvironmentSnapshot): String =
    buildString {
        appendLine("zephyr-environment-snapshot=${snapshot.schemaVersion}")
        appendLine("captured-at-epoch-millis=${snapshot.capturedAtEpochMillis}")
        snapshot.candidates
            .sortedBy(SnapshotCandidate::candidate)
            .forEach { candidate ->
                append("candidate=")
                append(candidate.candidate)
                append('\t')
                append(candidate.defaultVersion.orEmpty())
                append('\t')
                appendLine(
                    candidate.installedVersions
                        .distinct()
                        .sorted()
                        .joinToString(","),
                )
            }
    }

fun parseEnvironmentSnapshot(content: String): EnvironmentSnapshot {
    val lines = content.lineSequence().filter(String::isNotBlank).toList()
    require(lines.firstOrNull() == "zephyr-environment-snapshot=$CURRENT_SNAPSHOT_SCHEMA") {
        "Unsupported or missing Zephyr snapshot schema."
    }
    val capturedAt =
        lines
            .getOrNull(1)
            ?.removePrefix("captured-at-epoch-millis=")
            ?.takeIf { lines[1].startsWith("captured-at-epoch-millis=") }
            ?.toLongOrNull()
            ?: error("Snapshot capture timestamp is missing or invalid.")
    val candidates =
        lines.drop(2).mapIndexed { index, line ->
            require(line.startsWith("candidate=")) { "Line ${index + 3}: expected a candidate entry." }
            val fields = line.removePrefix("candidate=").split('\t')
            require(fields.size == 3) { "Line ${index + 3}: malformed candidate entry." }
            val candidate = fields[0]
            val defaultVersion = fields[1].ifBlank { null }
            val versions = fields[2].split(',').filter(String::isNotBlank)
            require(isValidSdkmanCandidateName(candidate)) { "Line ${index + 3}: invalid candidate key." }
            require(defaultVersion == null || isValidSdkmanVersion(defaultVersion)) {
                "Line ${index + 3}: invalid default version."
            }
            require(versions.all(::isValidSdkmanVersion)) { "Line ${index + 3}: invalid installed version." }
            require(defaultVersion == null || defaultVersion in versions) {
                "Line ${index + 3}: default version is not included in installed versions."
            }
            SnapshotCandidate(candidate, defaultVersion, versions.distinct().sorted())
        }
    require(candidates.distinctBy(SnapshotCandidate::candidate).size == candidates.size) {
        "Snapshot contains duplicate candidate entries."
    }
    return EnvironmentSnapshot(
        capturedAtEpochMillis = capturedAt,
        candidates = candidates.sortedBy(SnapshotCandidate::candidate),
    )
}

const val CURRENT_SNAPSHOT_SCHEMA = 1
