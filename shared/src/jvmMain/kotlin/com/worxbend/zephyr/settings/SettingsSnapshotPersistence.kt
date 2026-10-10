package com.worxbend.zephyr.settings

import java.util.prefs.Preferences

/** Storage owns commit ordering; the codec owns representation and validation. */
internal interface SettingsSnapshotPersistence {
    fun readSnapshot(): String?
    fun readLegacyValues(): Map<String, String>
    fun commit(snapshot: String)
}

/**
 * Two complete generations with a single commit pointer. Stage and flush the inactive generation
 * before moving the pointer. Any crash exposes one complete document, never mixed per-key values.
 * Preferences does not offer cross-process transactions: one application writer is supported.
 */
internal class PreferencesSettingsPersistence(private val preferences: Preferences) : SettingsSnapshotPersistence {
    override fun readSnapshot(): String? {
        val active = preferences.get(ACTIVE_KEY, null) ?: return null
        require(active == "a" || active == "b") { "Unsupported settings generation." }
        val prefix = "$SNAPSHOT_PREFIX$active"
        val count = requireNotNull(preferences.get("$prefix-count", null)?.toIntOrNull()) {
            "Missing or invalid committed settings chunk count."
        }
        require(count in 1..MAX_CHUNKS) { "Invalid committed settings chunk count." }
        return buildString {
            repeat(count) { index ->
                append(requireNotNull(preferences.get("$prefix-$index", null)) { "Missing committed settings chunk." })
            }
        }.also { require(it.length <= AppSettingsCodec.MAX_DOCUMENT_LENGTH) }
    }

    override fun readLegacyValues(): Map<String, String> = preferences.keys()
        .filterNot { it == ACTIVE_KEY || it.startsWith(SNAPSHOT_PREFIX) }
        .associateWith { requireNotNull(preferences.get(it, null)) }

    override fun commit(snapshot: String) {
        // Validate every Preferences constraint before the first write.
        require(snapshot.length <= AppSettingsCodec.MAX_DOCUMENT_LENGTH) { "Settings snapshot exceeds persistence limit." }
        val chunks = snapshot.chunked(Preferences.MAX_VALUE_LENGTH)
        require(chunks.size in 1..MAX_CHUNKS && chunks.all { it.length <= Preferences.MAX_VALUE_LENGTH })
        val previous = preferences.get(ACTIVE_KEY, null)
        require(previous == null || previous == "a" || previous == "b") { "Unsupported settings generation." }
        val next = if (previous == "a") "b" else "a"
        chunks.forEachIndexed { index, chunk -> preferences.put("$SNAPSHOT_PREFIX$next-$index", chunk) }
        preferences.putInt("$SNAPSHOT_PREFIX$next-count", chunks.size)
        preferences.flush()
        try {
            preferences.put(ACTIVE_KEY, next)
            preferences.flush()
        } catch (failure: Exception) {
            // A reported failed commit must continue to read the previous complete snapshot.
            // If rollback durability also fails, a crash can select old OR new, never mixed data.
            try {
                if (previous == null) preferences.remove(ACTIVE_KEY) else preferences.put(ACTIVE_KEY, previous)
                preferences.flush()
            } catch (rollbackFailure: Exception) {
                failure.addSuppressed(rollbackFailure)
            }
            throw failure
        }
    }

    internal companion object {
        private const val MAX_CHUNKS = AppSettingsCodec.MAX_DOCUMENT_LENGTH / Preferences.MAX_VALUE_LENGTH
        const val ACTIVE_KEY = "settings-active-generation"
        const val SNAPSHOT_PREFIX = "settings-snapshot-"
    }
}
