package com.worxbend.zephyr.domain

sealed interface StorageMeasurement {
    data class Exact(
        val bytes: Long,
    ) : StorageMeasurement {
        init {
            require(bytes >= 0) { "Storage bytes cannot be negative." }
        }
    }

    data class Unknown(
        val reason: StorageUnknownReason,
    ) : StorageMeasurement
}

enum class StorageUnknownReason(
    val label: String,
) {
    Missing("Directory disappeared during scan"),
    NotDirectory("Version path is not a directory"),
    SymbolicLink("Symbolic link found in payload"),
    Unreadable("Directory or file metadata is unreadable"),
    EntryLimit("Payload exceeds the safe scan limit"),
    ChangedDuringScan("Payload changed during scan"),
    UnsupportedEntry("Unsupported filesystem entry found"),
    Overflow("Payload is too large to represent"),
}

data class VersionStorage(
    val candidate: String,
    val candidateDisplayName: String,
    val version: String,
    val measurement: StorageMeasurement,
    val isDefault: Boolean,
    val isProtected: Boolean,
    val remoteAvailability: RemoteAvailability,
) {
    val bytes: Long?
        get() = (measurement as? StorageMeasurement.Exact)?.bytes

    val cleanupDisposition: StorageCleanupDisposition
        get() =
            when {
                isProtected -> StorageCleanupDisposition.BlockedProtected
                isDefault -> StorageCleanupDisposition.BlockedDefault
                remoteAvailability == RemoteAvailability.LocalOnly -> StorageCleanupDisposition.VerifiedLocalOnly
                else -> StorageCleanupDisposition.OptionalNonDefault
            }
}

enum class StorageCleanupDisposition(
    val label: String,
    val eligible: Boolean,
) {
    VerifiedLocalOnly("Verified local-only", true),
    OptionalNonDefault("Optional non-default version", true),
    BlockedDefault("Current default", false),
    BlockedProtected("Protected from cleanup", false),
}

data class StorageTotal(
    val knownBytes: Long,
    val unknownEntries: Int,
) {
    val exactBytes: Long?
        get() = knownBytes.takeIf { unknownEntries == 0 }

    val isExact: Boolean
        get() = unknownEntries == 0
}

data class CandidateStorage(
    val candidate: String,
    val displayName: String,
    val versions: List<VersionStorage>,
) {
    val total: StorageTotal
        get() = versions.storageTotal()
}

data class StorageInventory(
    val versions: List<VersionStorage>,
    val scannedAtEpochMillis: Long,
    val availableBytes: Long? = null,
) {
    val total: StorageTotal
        get() = versions.storageTotal()

    val candidates: List<CandidateStorage>
        get() =
            versions
                .groupBy(VersionStorage::candidate)
                .map { (candidate, versions) ->
                    CandidateStorage(
                        candidate = candidate,
                        displayName = versions.first().candidateDisplayName,
                        versions = versions.sortedWith(storageSizeComparator),
                    )
                }.sortedWith(
                    compareByDescending<CandidateStorage> { it.total.knownBytes }
                        .thenBy { it.displayName.lowercase() },
                )

    companion object {
        val Empty = StorageInventory(emptyList(), scannedAtEpochMillis = 0L)
    }
}

val storageSizeComparator: Comparator<VersionStorage> =
    compareByDescending<VersionStorage> { it.bytes != null }
        .thenByDescending { it.bytes ?: 0L }
        .thenBy { it.candidateDisplayName.lowercase() }
        .thenBy { it.version }

fun List<VersionStorage>.storageTotal(): StorageTotal =
    StorageTotal(
        knownBytes =
            fold(0L) { total, entry ->
                val bytes = entry.bytes ?: return@fold total
                if (Long.MAX_VALUE - total < bytes) Long.MAX_VALUE else total + bytes
            },
        unknownEntries = count { it.measurement is StorageMeasurement.Unknown },
    )
