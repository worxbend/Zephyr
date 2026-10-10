package com.worxbend.zephyr.settings

import com.worxbend.zephyr.domain.InstallTarget
import com.worxbend.zephyr.domain.ProtectedVersion
import com.worxbend.zephyr.domain.DesiredToolchainState

data class AppSettings(
    val themePreference: ThemePreference = ThemePreference.System,
    val uiDensity: UiDensity = UiDensity.Compact,
    val textScale: TextScale = TextScale.Percent100,
    val motionPreference: MotionPreference = MotionPreference.System,
    val metadataRefreshSchedule: MetadataRefreshSchedule = MetadataRefreshSchedule.Off,
    val updateNotificationPolicy: UpdateNotificationPolicy = UpdateNotificationPolicy.Off,
    val operationNotificationPolicy: OperationNotificationPolicy = OperationNotificationPolicy.Off,
    val cleanupGracePeriod: CleanupGracePeriod = CleanupGracePeriod.Off,
    val localOnlyObservations: List<LocalOnlyObservation> = emptyList(),
    val showSdkmanHome: Boolean = true,
    val favoriteCandidates: Set<String> = emptySet(),
    val favoriteJdkVendors: Set<String> = emptySet(),
    val recentCandidates: List<String> = emptyList(),
    val toolchainProfiles: List<ToolchainProfile> = emptyList(),
    val projectWorkspaces: List<ProjectWorkspaceReference> = emptyList(),
    val desiredToolchainState: DesiredToolchainState? = null,
    val navigationWidthDp: Int = 0,
    val installedViewMode: CollectionViewMode = CollectionViewMode.Cards,
    val catalogViewMode: CollectionViewMode = CollectionViewMode.Cards,
    val savedJdkFilters: List<SavedJdkFilter> = emptyList(),
)

data class SavedJdkFilter(
    val name: String,
    val query: String,
    val status: String,
    val providerCode: String?,
    val sort: String,
)

data class LocalOnlyObservation(
    val candidate: String,
    val version: String,
    val firstSeenEpochMillis: Long,
)

fun AppSettings.reconcileLocalOnlyObservations(
    current: Set<ProtectedVersion>,
    nowEpochMillis: Long,
    verifiedCandidates: Set<String> = current.mapTo(linkedSetOf()) { it.candidate },
): AppSettings {
    if (cleanupGracePeriod == CleanupGracePeriod.Off) {
        return if (localOnlyObservations.isEmpty()) this else copy(localOnlyObservations = emptyList())
    }
    require(nowEpochMillis >= 0) { "Observation time must not be negative." }
    require(current.all { it.candidate in verifiedCandidates }) { "Observations require verified candidate evidence." }
    val existing = localOnlyObservations.associateBy { it.candidate to it.version }
    // An empty or failed audit is not evidence of removal. Replace only verified candidates.
    val preserved = localOnlyObservations.filter { it.candidate !in verifiedCandidates }
    val observed = current.map { target ->
        existing[target.candidate to target.version]
            ?: LocalOnlyObservation(target.candidate, target.version, nowEpochMillis)
    }
    val next = (preserved + observed)
        .sortedWith(compareBy(LocalOnlyObservation::candidate, LocalOnlyObservation::version))
    return if (next == localOnlyObservations) this else copy(localOnlyObservations = next)
}

fun AppSettings.reviewDueLocalOnly(nowEpochMillis: Long): Set<ProtectedVersion> {
    val graceMillis = cleanupGracePeriod.graceMillis ?: return emptySet()
    return localOnlyObservations
        .asSequence()
        .filter { nowEpochMillis - it.firstSeenEpochMillis >= graceMillis }
        .map { ProtectedVersion(it.candidate, it.version) }
        .toSet()
}

const val MIN_NAVIGATION_WIDTH_DP = 190
const val MAX_NAVIGATION_WIDTH_DP = 360

fun Int.normalizedNavigationWidth(): Int =
    if (this == 0) 0 else coerceIn(MIN_NAVIGATION_WIDTH_DP, MAX_NAVIGATION_WIDTH_DP)

data class ToolchainProfile(
    val name: String,
    val targets: List<InstallTarget>,
)

data class ProjectWorkspaceReference(
    val sdkmanRcPath: String,
    val displayName: String,
) {
    init {
        require(sdkmanRcPath.isNotBlank()) { "A project workspace path is required." }
        require(displayName.isNotBlank()) { "A project workspace name is required." }
        require(
            sdkmanRcPath.none { it.code < 32 || it.code == 127 } &&
                displayName.none { it.code < 32 || it.code == 127 },
        ) { "Project workspace values contain unsupported control characters." }
    }
}

fun AppSettings.recordRecentCandidate(candidate: String, limit: Int = 6): AppSettings {
    val normalized = candidate.trim()
    if (normalized.isEmpty()) return this
    return copy(
        recentCandidates = (listOf(normalized) + recentCandidates)
            .distinct()
            .take(limit.coerceAtLeast(1)),
    )
}

enum class ThemePreference(val label: String) {
    System("System"),
    Light("Light"),
    Dark("Dark"),
}

enum class UiDensity(val label: String) {
    Compact("Compact"),
    Comfortable("Comfortable"),
}

enum class TextScale(val label: String, val factor: Float) {
    Percent100("100%", 1f),
    Percent125("125%", 1.25f),
    Percent150("150%", 1.5f),
    Percent175("175%", 1.75f),
    Percent200("200%", 2f),
}

enum class MotionPreference(val label: String) {
    System("System"),
    Full("Full"),
    Reduced("Reduced"),
}

fun MotionPreference.reducesMotion(systemReducedMotion: Boolean): Boolean =
    when (this) {
        MotionPreference.System -> systemReducedMotion
        MotionPreference.Full -> false
        MotionPreference.Reduced -> true
    }

enum class MetadataRefreshSchedule(
    val label: String,
    val intervalMillis: Long?,
) {
    Off("Off", null),
    Hourly("Hourly", 60L * 60L * 1_000L),
    EverySixHours("Every 6 hours", 6L * 60L * 60L * 1_000L),
    Daily("Daily", 24L * 60L * 60L * 1_000L),
}

enum class UpdateNotificationPolicy(val label: String) {
    Off("Off"),
    UpdatesOnly("Updates only"),
    AllChecks("All checks"),
}

enum class OperationNotificationPolicy(val label: String) {
    Off("Off"),
    LongRunning("Long operations"),
    AllCompletions("All completions"),
}

enum class CleanupGracePeriod(
    val label: String,
    val graceMillis: Long?,
) {
    Off("Off", null),
    SevenDays("7 days", 7L * 24L * 60L * 60L * 1_000L),
    ThirtyDays("30 days", 30L * 24L * 60L * 60L * 1_000L),
    NinetyDays("90 days", 90L * 24L * 60L * 60L * 1_000L),
}

enum class CollectionViewMode(val label: String) {
    Cards("Cards"),
    Table("Table"),
}
