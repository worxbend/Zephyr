package com.worxbend.zephyr

import com.worxbend.zephyr.domain.Candidate
import com.worxbend.zephyr.domain.CandidateCatalogItem
import com.worxbend.zephyr.domain.CandidateKind
import com.worxbend.zephyr.domain.CandidateVersion
import com.worxbend.zephyr.settings.UpdateNotificationPolicy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdateCenterTest {
    @Test
    fun findsCatalogStableVersionsThatAreNotInstalled() {
        val installed = candidate("gradle", "8.10")
        val catalog = catalog("gradle", "8.14")

        val update = availableCandidateUpdates(listOf(installed), listOf(catalog)).single()

        assertEquals("8.10", update.currentVersion)
        assertEquals("8.14", update.targetVersion)
        assertEquals(StableTargetState.Missing, update.state)
    }

    @Test
    fun classifiesInstalledStableVersionThatIsNotDefaultAsInactiveAndSelectable() {
        val installed =
            candidate(
                name = "gradle",
                defaultVersion = "8.10",
                installedVersions = listOf("8.10", "8.14"),
            )
        val catalog = catalog("gradle", "8.14")

        assertEquals(
            StableTargetState.InstalledInactive,
            availableCandidateUpdates(listOf(installed), listOf(catalog)).single().state,
        )
    }

    @Test
    fun classifiesActiveStableVersionAndExcludesItFromActions() {
        val installed = candidate("gradle", "8.14")
        val catalog = catalog("gradle", "8.14")

        assertEquals(
            StableTargetState.Active,
            stableCandidateTargets(listOf(installed), listOf(catalog)).single().state,
        )
        assertTrue(availableCandidateUpdates(listOf(installed), listOf(catalog)).isEmpty())
    }

    @Test
    fun updateOnlyPolicyNotifiesWithoutOpeningBrowse() {
        val notification =
            updateNotification(
                policy = UpdateNotificationPolicy.UpdatesOnly,
                candidates = listOf(candidate("gradle", "8.10")),
                catalog = listOf(catalog("gradle", "8.14")),
            )

        assertEquals("1 toolchain update available", notification?.title)
        assertEquals("Gradle 8.14 (install and activate)", notification?.message)
        assertEquals("gradle:8.14:Missing", notification?.signature)
    }

    @Test
    fun notificationDistinguishesInstalledActivationFromMissingDownload() {
        val notification =
            updateNotification(
                policy = UpdateNotificationPolicy.UpdatesOnly,
                candidates =
                    listOf(
                        candidate("gradle", "8.10", listOf("8.10", "8.14")),
                    ),
                catalog = listOf(catalog("gradle", "8.14")),
            )

        assertEquals("1 stable update ready to activate", notification?.title)
        assertEquals("Gradle 8.14 (activate installed)", notification?.message)
        assertEquals("gradle:8.14:InstalledInactive", notification?.signature)
    }

    @Test
    fun notificationPolicyControlsCurrentAndDisabledChecks() {
        val candidates = listOf(candidate("gradle", "8.14"))
        val catalog = listOf(catalog("gradle", "8.14"))

        assertEquals(
            null,
            updateNotification(UpdateNotificationPolicy.Off, candidates, catalog),
        )
        assertEquals(
            null,
            updateNotification(UpdateNotificationPolicy.UpdatesOnly, candidates, catalog),
        )
        assertEquals(
            "Your loaded SDKMAN toolchain is current.",
            updateNotification(UpdateNotificationPolicy.AllChecks, candidates, catalog)?.message,
        )
    }

    private fun candidate(
        name: String,
        defaultVersion: String,
        installedVersions: List<String> = listOf(defaultVersion),
    ) = Candidate(
        name = name,
        displayName = name.replaceFirstChar { it.titlecase() },
        kind = CandidateKind.Sdk,
        installedVersions =
            installedVersions.map {
                CandidateVersion(it, true, it == defaultVersion, true)
            },
        defaultVersion = defaultVersion,
        hasLocalOnlyVersions = false,
        localOnlyVersionCount = 0,
        localOnlyVersions = emptyList(),
    )

    private fun catalog(
        name: String,
        stableVersion: String,
    ) = CandidateCatalogItem(
        name = name,
        displayName = name.replaceFirstChar { it.titlecase() },
        stableVersion = stableVersion,
        description = null,
        websiteUrl = null,
        kind = CandidateKind.Sdk,
        isInstalled = true,
    )
}
