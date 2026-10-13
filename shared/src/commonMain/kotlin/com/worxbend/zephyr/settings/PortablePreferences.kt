package com.worxbend.zephyr.settings

import com.worxbend.zephyr.domain.InstallTarget

data class PortablePreferences(
    val themePreference: ThemePreference,
    val uiDensity: UiDensity,
    val textScale: TextScale,
    val motionPreference: MotionPreference,
    val metadataRefreshSchedule: MetadataRefreshSchedule,
    val updateNotificationPolicy: UpdateNotificationPolicy,
    val operationNotificationPolicy: OperationNotificationPolicy,
    val cleanupGracePeriod: CleanupGracePeriod,
    val showSdkmanHome: Boolean,
    val favoriteCandidates: Set<String>,
    val favoriteJdkVendors: Set<String>,
    val recentCandidates: List<String>,
    val toolchainProfiles: List<ToolchainProfile>,
    val navigationWidthDp: Int,
    val installedViewMode: CollectionViewMode,
    val catalogViewMode: CollectionViewMode,
    val savedJdkFilters: List<SavedJdkFilter>,
)

fun AppSettings.portablePreferences(): PortablePreferences =
    PortablePreferences(
        themePreference,
        uiDensity,
        textScale,
        motionPreference,
        metadataRefreshSchedule,
        updateNotificationPolicy,
        operationNotificationPolicy,
        cleanupGracePeriod,
        showSdkmanHome,
        favoriteCandidates,
        favoriteJdkVendors,
        recentCandidates,
        toolchainProfiles,
        navigationWidthDp,
        installedViewMode,
        catalogViewMode,
        savedJdkFilters,
    )

fun AppSettings.applyPortablePreferences(portable: PortablePreferences): AppSettings =
    copy(
        themePreference = portable.themePreference,
        uiDensity = portable.uiDensity,
        textScale = portable.textScale,
        motionPreference = portable.motionPreference,
        metadataRefreshSchedule = portable.metadataRefreshSchedule,
        updateNotificationPolicy = portable.updateNotificationPolicy,
        operationNotificationPolicy = portable.operationNotificationPolicy,
        cleanupGracePeriod = portable.cleanupGracePeriod,
        showSdkmanHome = portable.showSdkmanHome,
        favoriteCandidates = portable.favoriteCandidates,
        favoriteJdkVendors = portable.favoriteJdkVendors,
        recentCandidates = portable.recentCandidates,
        toolchainProfiles = portable.toolchainProfiles,
        navigationWidthDp = portable.navigationWidthDp.normalizedNavigationWidth(),
        installedViewMode = portable.installedViewMode,
        catalogViewMode = portable.catalogViewMode,
        savedJdkFilters = portable.savedJdkFilters,
    )

fun renderPortablePreferences(value: PortablePreferences): String =
    buildString {
        appendLine("zephyr-portable-preferences=1")
        appendLine("theme=${value.themePreference.name}")
        appendLine("density=${value.uiDensity.name}")
        appendLine("text-scale=${value.textScale.name}")
        appendLine("motion=${value.motionPreference.name}")
        appendLine("refresh=${value.metadataRefreshSchedule.name}")
        appendLine("update-notifications=${value.updateNotificationPolicy.name}")
        appendLine("operation-notifications=${value.operationNotificationPolicy.name}")
        appendLine("cleanup-grace=${value.cleanupGracePeriod.name}")
        appendLine("show-sdkman-home=${value.showSdkmanHome}")
        appendLine("installed-view=${value.installedViewMode.name}")
        appendLine("catalog-view=${value.catalogViewMode.name}")
        appendLine("navigation-width=${value.navigationWidthDp.normalizedNavigationWidth()}")
        value.favoriteCandidates.sorted().forEach { appendLine("favorite-candidate\t${it.escaped()}") }
        value.favoriteJdkVendors.sorted().forEach { appendLine("favorite-vendor\t${it.escaped()}") }
        value.recentCandidates.forEach { appendLine("recent\t${it.escaped()}") }
        value.toolchainProfiles.sortedBy { it.name }.forEach { profile ->
            profile.targets.sortedBy { it.candidate }.forEach { target ->
                appendLine("profile\t${profile.name.escaped()}\t${target.candidate.escaped()}\t${target.version.escaped()}")
            }
        }
        value.savedJdkFilters.sortedBy { it.name }.forEach {
            appendLine(
                listOf("filter", it.name, it.query, it.status, it.providerCode.orEmpty(), it.sort)
                    .joinToString("\t") { field -> field.escaped() },
            )
        }
    }

fun parsePortablePreferences(content: String): PortablePreferences {
    val lines = content.lineSequence().filter(String::isNotBlank).toList()
    require(lines.firstOrNull() == "zephyr-portable-preferences=1") { "Unsupported portable preferences schema." }
    val scalars =
        lines.drop(1).filter { '\t' !in it }.associate {
            it.substringBefore('=') to it.substringAfter('=', "")
        }

    fun scalar(key: String) = requireNotNull(scalars[key]) { "Missing $key preference." }
    val records = lines.drop(1).filter { '\t' in it }.map { it.split('\t').map(String::unescaped) }
    val profiles =
        records
            .filter { it.first() == "profile" }
            .groupBy { it[1] }
            .map { (name, rows) -> ToolchainProfile(name, rows.map { InstallTarget(it[2], it[3]) }) }
    return PortablePreferences(
        enumValueOf(scalar("theme")),
        enumValueOf(scalar("density")),
        enumValueOf(scalar("text-scale")),
        enumValueOf(scalar("motion")),
        enumValueOf(scalar("refresh")),
        enumValueOf(scalar("update-notifications")),
        enumValueOf(scalar("operation-notifications")),
        enumValueOf(scalar("cleanup-grace")),
        scalar("show-sdkman-home").toBooleanStrict(),
        records.filter { it.first() == "favorite-candidate" }.map { it[1] }.toSet(),
        records.filter { it.first() == "favorite-vendor" }.map { it[1] }.toSet(),
        records.filter { it.first() == "recent" }.map { it[1] },
        profiles,
        scalar("navigation-width").toInt().normalizedNavigationWidth(),
        enumValueOf(scalar("installed-view")),
        enumValueOf(scalar("catalog-view")),
        records.filter { it.first() == "filter" }.map {
            SavedJdkFilter(it[1], it[2], it[3], it[4].ifBlank { null }, it[5])
        },
    )
}

private fun String.escaped(): String = replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

private fun String.unescaped(): String {
    val result = StringBuilder()
    var escaped = false
    forEach { character ->
        if (escaped) {
            result.append(
                when (character) {
                    't' -> '\t'
                    'n' -> '\n'
                    else -> character
                },
            )
            escaped = false
        } else if (character == '\\') {
            escaped = true
        } else {
            result.append(character)
        }
    }
    if (escaped) result.append('\\')
    return result.toString()
}
