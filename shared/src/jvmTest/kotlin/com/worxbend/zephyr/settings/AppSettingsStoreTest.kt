package com.worxbend.zephyr.settings

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import com.worxbend.zephyr.domain.InstallTarget
import com.worxbend.zephyr.domain.ProtectedVersion
import com.worxbend.zephyr.domain.DesiredCandidateState
import com.worxbend.zephyr.domain.DesiredStateSourceKind
import com.worxbend.zephyr.domain.DesiredToolchainState
import kotlin.test.Test
import kotlin.test.assertEquals

class AppSettingsStoreTest {
    @Test
    fun loadsAndPersistsSettingsUpdates() = runBlocking {
        val initial = AppSettings(
            themePreference = ThemePreference.Dark,
            uiDensity = UiDensity.Comfortable,
            textScale = TextScale.Percent150,
            motionPreference = MotionPreference.Reduced,
            metadataRefreshSchedule = MetadataRefreshSchedule.EverySixHours,
            updateNotificationPolicy = UpdateNotificationPolicy.UpdatesOnly,
            operationNotificationPolicy = OperationNotificationPolicy.LongRunning,
            cleanupGracePeriod = CleanupGracePeriod.ThirtyDays,
            localOnlyObservations = listOf(
                LocalOnlyObservation("java", "17.0.1-tem", 1_000L),
            ),
            showSdkmanHome = false,
            favoriteCandidates = setOf("gradle", "kotlin"),
            favoriteJdkVendors = setOf("tem", "zulu"),
            recentCandidates = listOf("kotlin", "gradle"),
            toolchainProfiles = listOf(
                ToolchainProfile("Backend", listOf(InstallTarget("java", "21.0.5-tem"))),
            ),
            projectWorkspaces = listOf(
                ProjectWorkspaceReference("/srv/backend/.sdkmanrc", "Backend"),
            ),
            desiredToolchainState = DesiredToolchainState(
                sourceKind = DesiredStateSourceKind.Profile,
                sourceLabel = "Backend",
                candidates = listOf(DesiredCandidateState("java", "21.0.5-tem", listOf("21.0.5-tem"))),
            ),
            navigationWidthDp = 286,
            installedViewMode = CollectionViewMode.Table,
            catalogViewMode = CollectionViewMode.Table,
            savedJdkFilters = listOf(
                SavedJdkFilter("Temurin", "", "Available", "tem", "Version"),
            ),
        )
        val saved = CompletableDeferred<AppSettings>()
        val repository = FakeAppSettingsRepository(initial) { saved.complete(it) }
        val store = AppSettingsStore(repository, Dispatchers.Unconfined)

        assertEquals(initial, withTimeout(1_000) { store.state.first { it == initial } })

        val updated = initial.copy(themePreference = ThemePreference.Light)
        store.update { it.copy(themePreference = ThemePreference.Light) }

        assertEquals(updated, withTimeout(1_000) { saved.await() })
        assertEquals(updated, store.state.value)
        store.close()
    }

    @Test
    fun jvmRepositoryPersistsFavoriteSets() = runBlocking {
        val preferences = MemorySettingsPreferences().node("fixture")
        try {
            val repository = JvmAppSettingsRepository(preferences)
            val expected = AppSettings(
                favoriteCandidates = setOf("kotlin", "gradle"),
                favoriteJdkVendors = setOf("zulu", "tem"),
                textScale = TextScale.Percent200,
                motionPreference = MotionPreference.Full,
                metadataRefreshSchedule = MetadataRefreshSchedule.Daily,
                updateNotificationPolicy = UpdateNotificationPolicy.AllChecks,
                operationNotificationPolicy = OperationNotificationPolicy.AllCompletions,
                cleanupGracePeriod = CleanupGracePeriod.NinetyDays,
                localOnlyObservations = listOf(
                    LocalOnlyObservation("gradle", "7.6", 234_567L),
                    LocalOnlyObservation("java", "17.0.1-tem", 123_456L),
                ),
                recentCandidates = listOf("kotlin", "gradle"),
                toolchainProfiles = listOf(
                    ToolchainProfile(
                        "Backend | JVM",
                        listOf(
                            InstallTarget("java", "21.0.5-tem"),
                            InstallTarget("gradle", "8.14"),
                        ),
                    ),
                ),
                projectWorkspaces = listOf(
                    ProjectWorkspaceReference("/work/backend/.sdkmanrc", "Backend"),
                    ProjectWorkspaceReference("/work/mobile/.sdkmanrc", "Mobile"),
                ),
                desiredToolchainState = DesiredToolchainState(
                    sourceKind = DesiredStateSourceKind.Snapshot,
                    sourceLabel = "Snapshot 1234",
                    candidates = listOf(
                        DesiredCandidateState(
                            "java",
                            "21.0.5-tem",
                            listOf("17.0.1-tem", "21.0.5-tem"),
                        ),
                        DesiredCandidateState("gradle", "8.14", listOf("8.14")),
                    ),
                ),
                navigationWidthDp = 320,
                installedViewMode = CollectionViewMode.Table,
                catalogViewMode = CollectionViewMode.Table,
                savedJdkFilters = listOf(
                    SavedJdkFilter("Local Zulu", "17", "LocalOnly", "zulu", "Vendor"),
                ),
            )

            repository.save(expected)

            assertEquals(expected, repository.load())
        } finally {
            preferences.removeNode()
            preferences.flush()
        }
    }

    @Test
    fun recentCandidatesAreDeduplicatedAndBounded() {
        val settings = AppSettings(recentCandidates = listOf("gradle", "kotlin", "maven"))

        val updated = settings.recordRecentCandidate("kotlin", limit = 3)

        assertEquals(listOf("kotlin", "gradle", "maven"), updated.recentCandidates)
    }

    @Test
    fun navigationWidthIsClampedToSafeBounds() {
        assertEquals(MIN_NAVIGATION_WIDTH_DP, 100.normalizedNavigationWidth())
        assertEquals(MAX_NAVIGATION_WIDTH_DP, 500.normalizedNavigationWidth())
        assertEquals(0, 0.normalizedNavigationWidth())
    }

    @Test
    fun motionPreferenceHonorsSystemAndExplicitOverrides() {
        assertEquals(true, MotionPreference.System.reducesMotion(systemReducedMotion = true))
        assertEquals(false, MotionPreference.System.reducesMotion(systemReducedMotion = false))
        assertEquals(false, MotionPreference.Full.reducesMotion(systemReducedMotion = true))
        assertEquals(true, MotionPreference.Reduced.reducesMotion(systemReducedMotion = false))
    }

    @Test
    fun metadataRefreshSchedulesAreOptInAndBounded() {
        assertEquals(null, MetadataRefreshSchedule.Off.intervalMillis)
        assertEquals(3_600_000L, MetadataRefreshSchedule.Hourly.intervalMillis)
        assertEquals(21_600_000L, MetadataRefreshSchedule.EverySixHours.intervalMillis)
        assertEquals(86_400_000L, MetadataRefreshSchedule.Daily.intervalMillis)
    }

    @Test
    fun cleanupGracePolicyTracksFirstSeenAndNeverDeletes() {
        val java = ProtectedVersion("java", "17.0.1-tem")
        val gradle = ProtectedVersion("gradle", "7.6")
        val initial = AppSettings(cleanupGracePeriod = CleanupGracePeriod.SevenDays)

        val observed = initial.reconcileLocalOnlyObservations(setOf(java), nowEpochMillis = 1_000L)
        val rescanned = observed.reconcileLocalOnlyObservations(setOf(java, gradle), nowEpochMillis = 2_000L)

        assertEquals(1_000L, rescanned.localOnlyObservations.first { it.candidate == "java" }.firstSeenEpochMillis)
        assertEquals(2_000L, rescanned.localOnlyObservations.first { it.candidate == "gradle" }.firstSeenEpochMillis)
        assertEquals(
            setOf(java),
            rescanned.reviewDueLocalOnly(1_000L + CleanupGracePeriod.SevenDays.graceMillis!!),
        )
    }

    @Test
    fun disabledCleanupPolicyClearsObservationMetadata() {
        val settings = AppSettings(
            cleanupGracePeriod = CleanupGracePeriod.Off,
            localOnlyObservations = listOf(LocalOnlyObservation("java", "17", 1L)),
        )

        assertEquals(
            emptyList(),
            settings
                .reconcileLocalOnlyObservations(setOf(ProtectedVersion("java", "17")), 2L)
                .localOnlyObservations,
        )
    }
}

private class FakeAppSettingsRepository(
    private val initial: AppSettings,
    private val onSave: (AppSettings) -> Unit,
) : AppSettingsRepository {
    override suspend fun load(): AppSettings = initial

    override suspend fun save(settings: AppSettings) {
        onSave(settings)
    }
}
