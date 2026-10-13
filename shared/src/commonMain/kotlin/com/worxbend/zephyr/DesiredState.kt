package com.worxbend.zephyr

import com.worxbend.zephyr.data.EnvironmentSnapshot
import com.worxbend.zephyr.domain.DesiredCandidateState
import com.worxbend.zephyr.domain.DesiredStateSourceKind
import com.worxbend.zephyr.domain.DesiredToolchainState
import com.worxbend.zephyr.settings.ToolchainProfile

internal fun desiredStateFromProfile(profile: ToolchainProfile): DesiredToolchainState =
    DesiredToolchainState(
        sourceKind = DesiredStateSourceKind.Profile,
        sourceLabel = profile.name.normalizedDesiredSourceLabel(),
        candidates =
            profile.targets
                .distinctBy { it.candidate }
                .sortedBy { it.candidate }
                .map {
                    DesiredCandidateState(
                        candidate = it.candidate,
                        defaultVersion = it.version,
                        installedVersions = listOf(it.version),
                    )
                },
    )

internal fun desiredStateFromSnapshot(snapshot: EnvironmentSnapshot): DesiredToolchainState =
    DesiredToolchainState(
        sourceKind = DesiredStateSourceKind.Snapshot,
        sourceLabel = "Snapshot ${snapshot.capturedAtEpochMillis}",
        candidates =
            snapshot.candidates
                .filter { it.installedVersions.isNotEmpty() }
                .sortedBy { it.candidate }
                .map {
                    DesiredCandidateState(
                        candidate = it.candidate,
                        defaultVersion = it.defaultVersion,
                        installedVersions = it.installedVersions.distinct().sorted(),
                    )
                },
    )

private const val MAX_DESIRED_SOURCE_LABEL_LENGTH = 80

private fun String.normalizedDesiredSourceLabel(): String =
    trim()
        .map { if (it.code < 32 || it.code == 127) ' ' else it }
        .joinToString("")
        .trim()
        .ifBlank { "Saved profile" }
        .take(MAX_DESIRED_SOURCE_LABEL_LENGTH)
