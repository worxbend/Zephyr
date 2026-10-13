package com.worxbend.zephyr

import com.worxbend.zephyr.domain.Candidate
import com.worxbend.zephyr.domain.ProtectedVersion
import com.worxbend.zephyr.domain.javaProviderCode
import com.worxbend.zephyr.domain.javaProviderName

internal data class VersionComparisonRow(
    val version: String,
    val vendor: String,
    val installed: Boolean,
    val default: Boolean,
    val available: Boolean,
    val localOnly: Boolean,
    val protected: Boolean,
)

internal fun Candidate.comparisonRows(
    selectedVersions: Set<String>,
    protectedVersions: Set<ProtectedVersion>,
): List<VersionComparisonRow> =
    installedVersions
        .filter { it.version in selectedVersions }
        .map { version ->
            VersionComparisonRow(
                version = version.version,
                vendor =
                    if (name == "java") {
                        javaProviderName(javaProviderCode(version.version)) ?: "Unknown"
                    } else {
                        "—"
                    },
                installed = version.isInstalled,
                default = version.isDefault || defaultVersion == version.version,
                available = version.isRemoteAvailable,
                localOnly = version.isConfirmedLocalOnly,
                protected = ProtectedVersion(name, version.version) in protectedVersions,
            )
        }
