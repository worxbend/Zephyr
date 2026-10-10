package com.worxbend.zephyr.settings

import com.worxbend.zephyr.domain.InstallTarget
import com.worxbend.zephyr.domain.DesiredCandidateState
import com.worxbend.zephyr.domain.DesiredStateSourceKind
import com.worxbend.zephyr.domain.DesiredToolchainState
import java.nio.charset.StandardCharsets
import java.util.Base64

/** Read-only migration boundary for the original per-key settings format. */
internal object LegacySettingsCodec {
    fun decode(values: Map<String, String>): AppSettings =
        AppSettings(
            themePreference = values.enumValue(THEME_KEY, ThemePreference.System),
            uiDensity = values.enumValue(DENSITY_KEY, UiDensity.Compact),
            textScale = values.enumValue(TEXT_SCALE_KEY, TextScale.Percent100),
            motionPreference = values.enumValue(MOTION_KEY, MotionPreference.System),
            metadataRefreshSchedule = values.enumValue(REFRESH_SCHEDULE_KEY, MetadataRefreshSchedule.Off),
            updateNotificationPolicy = values.enumValue(NOTIFICATION_POLICY_KEY, UpdateNotificationPolicy.Off),
            operationNotificationPolicy = values.enumValue(
                OPERATION_NOTIFICATION_POLICY_KEY,
                OperationNotificationPolicy.Off,
            ),
            cleanupGracePeriod = values.enumValue(CLEANUP_GRACE_KEY, CleanupGracePeriod.Off),
            localOnlyObservations = values.localOnlyObservations(LOCAL_ONLY_OBSERVATIONS_KEY),
            showSdkmanHome = values[SHOW_SDKMAN_HOME_KEY]?.toBooleanStrict() ?: true,
            favoriteCandidates = values.stringSet(FAVORITE_CANDIDATES_KEY),
            favoriteJdkVendors = values.stringSet(FAVORITE_JDK_VENDORS_KEY),
            recentCandidates = values.stringList(RECENT_CANDIDATES_KEY),
            toolchainProfiles = values.profiles(PROFILES_KEY),
            projectWorkspaces = values.projectWorkspaces(PROJECT_WORKSPACES_KEY),
            desiredToolchainState = values.desiredToolchainState(),
            navigationWidthDp = (values[NAVIGATION_WIDTH_KEY]?.toInt() ?: 0).normalizedNavigationWidth(),
            installedViewMode = values.enumValue(INSTALLED_VIEW_MODE_KEY, CollectionViewMode.Cards),
            catalogViewMode = values.enumValue(CATALOG_VIEW_MODE_KEY, CollectionViewMode.Cards),
            savedJdkFilters = values.savedJdkFilters(SAVED_JDK_FILTERS_KEY),
        )

    private inline fun <reified T : Enum<T>> Map<String, String>.enumValue(key: String, fallback: T): T =
        getOrDefault(key, fallback.name)
            .let { stored -> enumValues<T>().firstOrNull { it.name == stored } }
            ?: error("Unsupported legacy settings enum: $key")

    private fun Map<String, String>.stringSet(key: String): Set<String> =
        getOrDefault(key, "")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .toSet()


    private fun Map<String, String>.stringList(key: String): List<String> =
        getOrDefault(key, "")
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .toList()


    private fun Map<String, String>.profiles(key: String): List<ToolchainProfile> =
        getOrDefault(key, "")
            .lineSequence()
            .filter(String::isNotEmpty)
            .mapNotNull { encoded ->
                runCatching {
                    val decoded = String(PROFILE_DECODER.decode(encoded), StandardCharsets.UTF_8)
                    val fields = decoded.split(PROFILE_FIELD_SEPARATOR)
                    val name = fields.firstOrNull()?.trim().orEmpty()
                    val targets = fields.drop(1).map { target ->
                        val parts = target.split(TARGET_FIELD_SEPARATOR, limit = 2)
                        require(parts.size == 2 && parts.all(String::isNotBlank)) { "Invalid legacy profile target." }
                        InstallTarget(parts[0], parts[1])
                    }
                    require(name.isNotEmpty() && targets.isNotEmpty()) { "Invalid legacy profile." }
                    ToolchainProfile(name, targets)
                }.getOrThrow()
            }
            .toList()


    private fun Map<String, String>.projectWorkspaces(key: String): List<ProjectWorkspaceReference> =
        getOrDefault(key, "")
            .lineSequence()
            .filter(String::isNotEmpty)
            .mapNotNull { encoded ->
                runCatching {
                    val fields = String(PROFILE_DECODER.decode(encoded), StandardCharsets.UTF_8)
                        .split(PROFILE_FIELD_SEPARATOR, limit = 2)
                    require(fields.size == 2) { "Invalid legacy workspace." }
                    ProjectWorkspaceReference(fields[0], fields[1])
                }.getOrThrow()
            }
            .distinctBy(ProjectWorkspaceReference::sdkmanRcPath)
            .toList()


    private fun Map<String, String>.desiredToolchainState(): DesiredToolchainState? {
        val chunks = (get(DESIRED_STATE_CHUNK_COUNT_KEY)?.toInt() ?: 0)
        if (chunks == 0) return null
        require(chunks in 1..MAX_DESIRED_STATE_CHUNKS) { "Invalid legacy desired state chunk count." }
        val encoded = buildString {
            repeat(chunks) { index ->
                append(getValue("$DESIRED_STATE_CHUNK_PREFIX$index"))
            }
        }
        require(encoded.isNotEmpty()) { "Empty legacy desired state." }
        return runCatching {
            val content = String(PROFILE_DECODER.decode(encoded), StandardCharsets.UTF_8)
            val lines = content.lineSequence().filter(String::isNotBlank).toList()
            val header = lines.first().split(PROFILE_FIELD_SEPARATOR)
            require(header.size == 3)
            val candidates = lines.drop(1).map { line ->
                val fields = line.split(PROFILE_FIELD_SEPARATOR)
                require(fields.size == 3)
                DesiredCandidateState(
                    candidate = fields[0],
                    defaultVersion = fields[1].ifEmpty { null },
                    installedVersions = fields[2].split(',').filter(String::isNotBlank),
                )
            }
            DesiredToolchainState(
                schemaVersion = header[0].toInt(),
                sourceKind = DesiredStateSourceKind.valueOf(header[1]),
                sourceLabel = header[2],
                candidates = candidates,
            )
        }.getOrThrow()
    }


    private fun Map<String, String>.savedJdkFilters(key: String): List<SavedJdkFilter> =
        getOrDefault(key, "")
            .lineSequence()
            .filter(String::isNotEmpty)
            .mapNotNull { encoded ->
                runCatching {
                    val fields = String(PROFILE_DECODER.decode(encoded), StandardCharsets.UTF_8)
                        .split(PROFILE_FIELD_SEPARATOR)
                    require(fields.size == 5 && fields[0].isNotBlank()) { "Invalid legacy saved filter." }
                    SavedJdkFilter(
                        name = fields[0],
                        query = fields[1],
                        status = fields[2],
                        providerCode = fields[3].ifEmpty { null },
                        sort = fields[4],
                    )
                }.getOrThrow()
            }
            .toList()


    private fun Map<String, String>.localOnlyObservations(key: String): List<LocalOnlyObservation> =
        getOrDefault(key, "")
            .lineSequence()
            .filter(String::isNotEmpty)
            .mapNotNull { encoded ->
                runCatching {
                    val fields = String(PROFILE_DECODER.decode(encoded), StandardCharsets.UTF_8)
                        .split(PROFILE_FIELD_SEPARATOR)
                    require(fields.size == 3) { "Invalid legacy observation." }
                    LocalOnlyObservation(
                        candidate = fields[0],
                        version = fields[1],
                        firstSeenEpochMillis = fields[2].toLong(),
                    ).also {
                        require(it.candidate.isNotBlank() && it.version.isNotBlank() && it.firstSeenEpochMillis >= 0) {
                            "Invalid legacy observation."
                        }
                    }
                }.getOrThrow()
            }
            .toList()


    private const val THEME_KEY = "theme"
    private const val DENSITY_KEY = "density"
    private const val TEXT_SCALE_KEY = "text-scale"
    private const val MOTION_KEY = "motion"
    private const val REFRESH_SCHEDULE_KEY = "metadata-refresh-schedule"
    private const val NOTIFICATION_POLICY_KEY = "update-notification-policy"
    private const val OPERATION_NOTIFICATION_POLICY_KEY = "operation-notification-policy"
    private const val CLEANUP_GRACE_KEY = "cleanup-grace"
    private const val LOCAL_ONLY_OBSERVATIONS_KEY = "local-only-observations"
    private const val SHOW_SDKMAN_HOME_KEY = "show-sdkman-home"
    private const val FAVORITE_CANDIDATES_KEY = "favorite-candidates"
    private const val FAVORITE_JDK_VENDORS_KEY = "favorite-jdk-vendors"
    private const val RECENT_CANDIDATES_KEY = "recent-candidates"
    private const val PROFILES_KEY = "toolchain-profiles"
    private const val PROJECT_WORKSPACES_KEY = "project-workspaces"
    private const val DESIRED_STATE_CHUNK_COUNT_KEY = "desired-state-chunks"
    private const val DESIRED_STATE_CHUNK_PREFIX = "desired-state-"
    private const val DESIRED_STATE_CHUNK_SIZE = 3_000
    private const val MAX_DESIRED_STATE_CHUNKS = 128
    private const val NAVIGATION_WIDTH_KEY = "navigation-width-dp"
    private const val INSTALLED_VIEW_MODE_KEY = "installed-view-mode"
    private const val CATALOG_VIEW_MODE_KEY = "catalog-view-mode"
    private const val SAVED_JDK_FILTERS_KEY = "saved-jdk-filters"
    private const val PROFILE_FIELD_SEPARATOR = '\u001F'
    private const val TARGET_FIELD_SEPARATOR = '\u001E'
    private val PROFILE_DECODER: Base64.Decoder = Base64.getUrlDecoder()
}
