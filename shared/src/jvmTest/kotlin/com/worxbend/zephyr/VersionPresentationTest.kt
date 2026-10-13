package com.worxbend.zephyr

import com.worxbend.zephyr.domain.Candidate
import com.worxbend.zephyr.domain.CandidateCatalogItem
import com.worxbend.zephyr.domain.CandidateKind
import com.worxbend.zephyr.domain.CandidateVersion
import com.worxbend.zephyr.domain.RemoteAvailability
import com.worxbend.zephyr.domain.withInstalledCandidates
import kotlin.test.Test
import kotlin.test.assertEquals

class VersionPresentationTest {
    @Test
    fun refreshesCatalogInstallationFlagsFromTheLocalCandidateSnapshot() {
        val catalog = listOf(catalogItem("gradle", isInstalled = false), catalogItem("kotlin", isInstalled = true))
        val localCandidates = listOf(candidate("gradle"))

        assertEquals(
            listOf("gradle"),
            catalog.withInstalledCandidates(localCandidates).filter { it.isInstalled }.map { it.name },
        )
    }

    @Test
    fun describesDefaultLocalOnlyVersionsForSearch() {
        val version =
            CandidateVersion(
                version = "21.0.5-tem",
                isInstalled = true,
                isDefault = true,
                remoteAvailability = RemoteAvailability.LocalOnly,
            )

        assertEquals("Default - Installed - Local only", statusText(version))
    }

    @Test
    fun offersOnlyRemoteVersionsThatAreNotInstalledAsUpdateTargets() {
        val versions =
            listOf(
                CandidateVersion("21.0.5-tem", isInstalled = true, isDefault = true, remoteAvailability = RemoteAvailability.Available),
                CandidateVersion("22.0.1-tem", isInstalled = false, isDefault = false, remoteAvailability = RemoteAvailability.Available),
                CandidateVersion("17.0.1-tem", isInstalled = true, isDefault = false, remoteAvailability = RemoteAvailability.LocalOnly),
            )

        assertEquals(listOf("22.0.1-tem"), versions.updateTargets().map { it.version })
    }

    private fun catalogItem(
        name: String,
        isInstalled: Boolean,
    ) = CandidateCatalogItem(
        name = name,
        displayName = name,
        stableVersion = null,
        description = null,
        websiteUrl = null,
        kind = CandidateKind.Sdk,
        isInstalled = isInstalled,
    )

    private fun candidate(name: String) =
        Candidate(
            name = name,
            displayName = name,
            kind = CandidateKind.Sdk,
            installedVersions = emptyList(),
            defaultVersion = null,
            hasLocalOnlyVersions = false,
            localOnlyVersionCount = 0,
            localOnlyVersions = emptyList(),
        )
}
