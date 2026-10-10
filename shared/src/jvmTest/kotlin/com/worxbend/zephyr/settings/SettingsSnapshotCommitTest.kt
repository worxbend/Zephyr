package com.worxbend.zephyr.settings

import com.worxbend.zephyr.domain.DesiredCandidateState
import com.worxbend.zephyr.domain.DesiredStateSourceKind
import com.worxbend.zephyr.domain.DesiredToolchainState
import com.worxbend.zephyr.domain.InstallTarget
import java.util.Base64
import java.util.prefs.BackingStoreException
import java.util.prefs.Preferences
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SettingsSnapshotCommitTest {
    @Test
    fun eachInjectedCommitFailureRetainsThePreviousWholeSnapshotAndAllowsRetry() = runBlocking {
        for (failedStep in 1..5) {
            var step = 0
            var armed = false
            val preferences = MemorySettingsPreferences(failure = {
                if (armed && ++step == failedStep) throw BackingStoreException("injected step $failedStep")
            })
            val repository = JvmAppSettingsRepository(preferences)
            val old = AppSettings(themePreference = ThemePreference.Dark, favoriteCandidates = setOf("gradle"))
            val new = old.copy(themePreference = ThemePreference.Light, favoriteCandidates = setOf("java"),
                toolchainProfiles = listOf(ToolchainProfile("new", listOf(InstallTarget("java", "21-tem")))))
            repository.save(old)
            armed = true
            assertFailsWith<BackingStoreException> { repository.save(new) }
            assertEquals(old, repository.load(), "commit failure $failedStep")
            assertEquals(old, JvmAppSettingsRepository(preferences).load(), "restart after failure $failedStep")
            armed = false
            repository.save(new)
            assertEquals(new, repository.load())
        }
    }

    @Test
    fun exactPreferencesBoundaryIsAcceptedAndOversizedSnapshotIsRejectedBeforeWrites() {
        val preferences = MemorySettingsPreferences()
        val persistence = PreferencesSettingsPersistence(preferences)
        val boundary = "x".repeat(Preferences.MAX_VALUE_LENGTH)
        persistence.commit(boundary)
        assertEquals(boundary, persistence.readSnapshot())
        val before = preferences.values.toMap()
        assertFailsWith<IllegalArgumentException> { persistence.commit("x".repeat(AppSettingsCodec.MAX_DOCUMENT_LENGTH + 1)) }
        assertEquals(before, preferences.values)
    }

    @Test
    fun corruptAndUnsupportedDocumentsCannotBeSilentlyOverwritten() = runBlocking {
        val valid = AppSettingsCodec.encode(AppSettings(themePreference = ThemePreference.Dark))
        val broken = listOf(valid.dropLast(2), valid.replace("settings:1", "settings:2"),
            valid.replace("Dark", "Light"), "garbage").filter { it != valid }
        for (snapshot in broken) {
            val preferences = MemorySettingsPreferences()
            preferences.put(PreferencesSettingsPersistence.ACTIVE_KEY, "a")
            preferences.putInt(PreferencesSettingsPersistence.SNAPSHOT_PREFIX + "a-count", 1)
            preferences.put(PreferencesSettingsPersistence.SNAPSHOT_PREFIX + "a-0", snapshot)
            val before = preferences.values.toMap()
            val repository = JvmAppSettingsRepository(preferences)
            assertFailsWith<IllegalArgumentException> { repository.load() }
            assertFailsWith<IllegalArgumentException> { repository.save(AppSettings()) }
            assertEquals(before, preferences.values)
        }
    }

    @Test
    fun validLegacyCollectionsAndChunkedDesiredStateMigrateWithoutLoss() = runBlocking {
        val preferences = MemorySettingsPreferences()
        preferences.put("theme", "Dark")
        preferences.put("favorite-candidates", "gradle\njava")
        preferences.put("toolchain-profiles", legacy("Backend\u001fjava\u001e21-tem"))
        preferences.put("project-workspaces", legacy("/scratch/backend/.sdkmanrc\u001fBackend"))
        preferences.put("saved-jdk-filters", legacy("JDK\u001f21\u001fAvailable\u001ftem\u001fVersion"))
        preferences.put("local-only-observations", legacy("gradle\u001f7.6\u001f1000"))
        val desired = legacy("1\u001fProfile\u001fBackend\njava\u001f21-tem\u001f17-tem,21-tem\n")
        preferences.putInt("desired-state-chunks", 2)
        preferences.put("desired-state-0", desired.take(10))
        preferences.put("desired-state-1", desired.drop(10))
        val expected = AppSettings(
            themePreference = ThemePreference.Dark,
            favoriteCandidates = setOf("gradle", "java"),
            toolchainProfiles = listOf(ToolchainProfile("Backend", listOf(InstallTarget("java", "21-tem")))),
            projectWorkspaces = listOf(ProjectWorkspaceReference("/scratch/backend/.sdkmanrc", "Backend")),
            savedJdkFilters = listOf(SavedJdkFilter("JDK", "21", "Available", "tem", "Version")),
            localOnlyObservations = listOf(LocalOnlyObservation("gradle", "7.6", 1000)),
            desiredToolchainState = DesiredToolchainState(sourceKind = DesiredStateSourceKind.Profile,
                sourceLabel = "Backend", candidates = listOf(DesiredCandidateState("java", "21-tem", listOf("17-tem", "21-tem")))),
        )
        val repository = JvmAppSettingsRepository(preferences)
        assertEquals(expected, repository.load())
        assertEquals(null, preferences.get(PreferencesSettingsPersistence.ACTIVE_KEY, null))
        repository.save(expected)
        assertEquals(expected, JvmAppSettingsRepository(preferences).load())
        assertEquals("Dark", preferences.get("theme", null), "migration retains original keys")
    }

    @Test
    fun malformedLegacyRecordsNeverShrinkCollectionsAndOverwriteEvidence() = runBlocking {
        for ((key, value) in listOf(
            "toolchain-profiles" to legacy("Broken\u001fjava\u001e21-tem\u001finvalid-target"),
            "local-only-observations" to legacy("gradle\u001f7.6\u001f-1"),
            "saved-jdk-filters" to legacy("truncated"),
            "project-workspaces" to legacy("truncated"),
        )) {
            val preferences = MemorySettingsPreferences()
            preferences.put(key, value)
            val repository = JvmAppSettingsRepository(preferences)
            assertFailsWith<IllegalArgumentException>("$key must fail closed") { repository.load() }
            assertFailsWith<IllegalArgumentException> { repository.save(AppSettings()) }
            assertEquals(mapOf(key to value), preferences.values)
        }
    }

    @Test
    fun codecPreservesUnicodeAndDelimiterCharactersWithoutSplittingRecords() {
        val expected = AppSettings(
            toolchainProfiles = listOf(ToolchainProfile("Backend\u001f\n☃", listOf(InstallTarget("java", "21-tem")))),
            savedJdkFilters = listOf(SavedJdkFilter("λ", "\u001f\n", "Any", null, "Version")),
        )
        assertEquals(expected, AppSettingsCodec.decode(AppSettingsCodec.encode(expected)))
        assertTrue(AppSettingsCodec.encode(expected).length <= AppSettingsCodec.MAX_DOCUMENT_LENGTH)
    }

    @Test
    fun legacyDesiredSnapshotAboveSinglePreferenceLimitRemainsEditableAfterMigration() = runBlocking {
        val preferences = MemorySettingsPreferences()
        val versions = (1..900).map { "21.$it-tem" }
        val desired = legacy("1\u001fProfile\u001fLarge\njava\u001f${versions.first()}\u001f${versions.joinToString(",")}\n")
        assertTrue(desired.length > Preferences.MAX_VALUE_LENGTH)
        val chunks = desired.chunked(3000)
        preferences.putInt("desired-state-chunks", chunks.size)
        chunks.forEachIndexed { index, chunk -> preferences.put("desired-state-$index", chunk) }
        val repository = JvmAppSettingsRepository(preferences)
        val original = repository.load()
        val updated = original.copy(themePreference = ThemePreference.Dark)
        repository.save(updated)
        assertEquals(updated, repository.load())
        assertTrue(preferences.values.values.all { it.length <= Preferences.MAX_VALUE_LENGTH })
    }

    @Test
    fun collectionAboveSinglePreferenceLimitCommitsAsOneCompleteSnapshot() = runBlocking {
        val preferences = MemorySettingsPreferences()
        val repository = JvmAppSettingsRepository(preferences)
        val expected = AppSettings(themePreference = ThemePreference.Dark,
            localOnlyObservations = (1..600).map { LocalOnlyObservation("java", "21.$it-tem", it.toLong()) })
        repository.save(expected)
        assertEquals(expected, JvmAppSettingsRepository(preferences).load())
        assertTrue(preferences.values.values.all { it.length <= Preferences.MAX_VALUE_LENGTH })
    }

    @Test
    fun multiChunkFailureCannotMixTheOldAndNewSettings() = runBlocking {
        val new = AppSettings(themePreference = ThemePreference.Dark,
            localOnlyObservations = (1..600).map { LocalOnlyObservation("java", "21.$it-tem", it.toLong()) })
        val operations = AppSettingsCodec.encode(new).chunked(Preferences.MAX_VALUE_LENGTH).size + 4
        for (failedStep in 1..operations) {
            var step = 0
            var armed = false
            val preferences = MemorySettingsPreferences(failure = {
                if (armed && ++step == failedStep) throw BackingStoreException("injected")
            })
            val repository = JvmAppSettingsRepository(preferences)
            val previous = AppSettings(themePreference = ThemePreference.Light)
            repository.save(previous)
            armed = true
            assertFailsWith<BackingStoreException> { repository.save(new) }
            assertEquals(previous, repository.load(), "operation $failedStep")
        }
    }

    @Test
    fun oversizedCompleteDocumentIsRejectedBeforeAnyPreferencesWrite() = runBlocking {
        val preferences = MemorySettingsPreferences()
        val repository = JvmAppSettingsRepository(preferences)
        repository.save(AppSettings(themePreference = ThemePreference.Dark))
        val before = preferences.values.toMap()
        val oversized = AppSettings(themePreference = ThemePreference.Light,
            savedJdkFilters = (1..200).map { SavedJdkFilter("$it", "x".repeat(8192), "Any", null, "Version") })
        assertFailsWith<IllegalArgumentException> { repository.save(oversized) }
        assertEquals(before, preferences.values)
        val unrelated = AppSettings(themePreference = ThemePreference.Dark, uiDensity = UiDensity.Comfortable)
        repository.save(unrelated)
        assertEquals(unrelated, repository.load())
    }

    private fun legacy(value: String): String = Base64.getUrlEncoder().withoutPadding().encodeToString(value.toByteArray())
}
