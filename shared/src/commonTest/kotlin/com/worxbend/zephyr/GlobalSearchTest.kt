package com.worxbend.zephyr

import com.worxbend.zephyr.domain.Candidate
import com.worxbend.zephyr.domain.CandidateCatalogItem
import com.worxbend.zephyr.domain.CandidateKind
import com.worxbend.zephyr.domain.CandidateVersion
import com.worxbend.zephyr.viewmodel.ZephyrRoute
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class GlobalSearchTest {
    private val java =
        Candidate(
            name = "java",
            displayName = "Java",
            kind = CandidateKind.Jdk,
            installedVersions =
                listOf(
                    CandidateVersion("21.0.5-tem", true, true, true),
                ),
            defaultVersion = "21.0.5-tem",
            hasLocalOnlyVersions = false,
            localOnlyVersionCount = 0,
            localOnlyVersions = emptyList(),
        )
    private val gradle =
        CandidateCatalogItem(
            name = "gradle",
            displayName = "Gradle",
            stableVersion = "8.14",
            description = "Build automation",
            websiteUrl = null,
            kind = CandidateKind.Sdk,
            isInstalled = false,
        )

    @Test
    fun indexesCandidatesVersionsSettingsAndActions() {
        val index = buildGlobalSearchIndex(listOf(java), listOf(gradle))

        assertTrue(index.any { it.kind == GlobalSearchKind.Candidate && it.title == "Java" })
        assertTrue(index.any { it.kind == GlobalSearchKind.Candidate && it.title == "Gradle" })
        assertTrue(index.any { it.kind == GlobalSearchKind.Version && it.title == "21.0.5-tem" })
        assertTrue(index.any { it.kind == GlobalSearchKind.Setting && it.title == "Theme preference" })
        assertTrue(index.any { it.kind == GlobalSearchKind.Action && it.title == "Refresh local state" })
    }

    @Test
    fun ranksExactAndTitleMatchesBeforeDescriptiveMatches() {
        val results = searchGlobalIndex(buildGlobalSearchIndex(listOf(java), listOf(gradle)), "gradle")

        assertEquals("Gradle", results.first().title)
        assertEquals(GlobalSearchKind.Candidate, results.first().kind)
    }

    @Test
    fun versionResultOpensItsCandidateDetail() {
        val result = searchGlobalIndex(buildGlobalSearchIndex(listOf(java), emptyList()), "21.0.5").single()
        val target = assertIs<GlobalSearchTarget.Navigate>(result.target)

        assertEquals(ZephyrRoute.JdkDetail("java"), target.route)
    }

    @Test
    fun settingsTermsAreSearchable() {
        val result = searchGlobalIndex(buildGlobalSearchIndex(emptyList(), emptyList()), "privacy").single()

        assertEquals("SDKMAN path privacy", result.title)
        assertEquals(ZephyrRoute.Settings, assertIs<GlobalSearchTarget.Navigate>(result.target).route)
    }

    @Test
    fun commandPaletteContainsOnlyExecutableOrNavigationCommands() {
        val commands = commandPaletteItems(buildGlobalSearchIndex(listOf(java), listOf(gradle)))

        assertTrue(commands.isNotEmpty())
        assertTrue(commands.all { it.kind == GlobalSearchKind.Action || it.kind == GlobalSearchKind.Page })
        assertTrue(commands.none { it.kind == GlobalSearchKind.Candidate || it.kind == GlobalSearchKind.Version })
        assertEquals(
            "Ctrl/⌘ Shift R",
            commands.single { it.title == "Refresh local state" }.shortcut,
        )
    }
}
