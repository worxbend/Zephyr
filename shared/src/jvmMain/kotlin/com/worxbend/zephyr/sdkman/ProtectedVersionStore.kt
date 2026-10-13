package com.worxbend.zephyr.sdkman

import com.worxbend.zephyr.domain.ProtectedVersion
import java.util.prefs.Preferences

interface ProtectedVersionStore {
    fun load(): Set<ProtectedVersion>

    fun save(versions: Set<ProtectedVersion>)
}

internal class InMemoryProtectedVersionStore(
    initial: Set<ProtectedVersion> = emptySet(),
) : ProtectedVersionStore {
    private var versions = initial

    override fun load(): Set<ProtectedVersion> = versions

    override fun save(versions: Set<ProtectedVersion>) {
        this.versions = versions
    }
}

internal class PreferencesProtectedVersionStore(
    private val preferences: Preferences = Preferences.userNodeForPackage(PreferencesProtectedVersionStore::class.java),
) : ProtectedVersionStore {
    override fun load(): Set<ProtectedVersion> =
        preferences
            .get(PROTECTED_VERSIONS_KEY, "")
            .lineSequence()
            .mapNotNull { line ->
                val candidate = line.substringBefore('\t', missingDelimiterValue = "")
                val version = line.substringAfter('\t', missingDelimiterValue = "")
                runCatching { ProtectedVersion(candidate, version) }.getOrNull()
            }.toSet()

    override fun save(versions: Set<ProtectedVersion>) {
        val serialized =
            versions
                .sortedWith(compareBy(ProtectedVersion::candidate, ProtectedVersion::version))
                .joinToString("\n") { "${it.candidate}\t${it.version}" }
        require(serialized.length <= MAX_PREFERENCES_VALUE_LENGTH) {
            "Too many protected versions to persist."
        }
        preferences.put(PROTECTED_VERSIONS_KEY, serialized)
        preferences.flush()
    }

    private companion object {
        const val PROTECTED_VERSIONS_KEY = "protected-versions"
        const val MAX_PREFERENCES_VALUE_LENGTH = 8_000
    }
}
