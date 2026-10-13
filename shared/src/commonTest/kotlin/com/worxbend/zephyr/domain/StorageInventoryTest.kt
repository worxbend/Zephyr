package com.worxbend.zephyr.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class StorageInventoryTest {
    @Test
    fun reportsKnownAndUnknownTotalsWithoutInventingBytes() {
        val inventory =
            StorageInventory(
                versions =
                    listOf(
                        entry("java", "21-tem", StorageMeasurement.Exact(4_096)),
                        entry("java", "17-tem", StorageMeasurement.Unknown(StorageUnknownReason.SymbolicLink)),
                        entry("gradle", "9.0", StorageMeasurement.Exact(1_024)),
                    ),
                scannedAtEpochMillis = 42,
            )

        assertEquals(5_120, inventory.total.knownBytes)
        assertEquals(1, inventory.total.unknownEntries)
        assertNull(inventory.total.exactBytes)
        assertEquals(listOf("java", "gradle"), inventory.candidates.map { it.candidate })
        assertEquals(
            4_096,
            inventory.candidates
                .first()
                .total.knownBytes,
        )
    }

    @Test
    fun exposesOnlyExplicitSafeCleanupOpportunities() {
        val protected =
            entry(
                candidate = "java",
                version = "11-tem",
                measurement = StorageMeasurement.Exact(1),
                isProtected = true,
                availability = RemoteAvailability.LocalOnly,
            )
        val default =
            entry(
                candidate = "java",
                version = "21-tem",
                measurement = StorageMeasurement.Exact(1),
                isDefault = true,
            )
        val localOnly =
            entry(
                candidate = "java",
                version = "17-tem",
                measurement = StorageMeasurement.Exact(1),
                availability = RemoteAvailability.LocalOnly,
            )
        val optional =
            entry(
                candidate = "gradle",
                version = "8.0",
                measurement = StorageMeasurement.Exact(1),
            )

        assertEquals(StorageCleanupDisposition.BlockedProtected, protected.cleanupDisposition)
        assertEquals(StorageCleanupDisposition.BlockedDefault, default.cleanupDisposition)
        assertEquals(StorageCleanupDisposition.VerifiedLocalOnly, localOnly.cleanupDisposition)
        assertEquals(StorageCleanupDisposition.OptionalNonDefault, optional.cleanupDisposition)
        assertFalse(protected.cleanupDisposition.eligible)
        assertFalse(default.cleanupDisposition.eligible)
        assertTrue(localOnly.cleanupDisposition.eligible)
        assertTrue(optional.cleanupDisposition.eligible)
    }

    private fun entry(
        candidate: String,
        version: String,
        measurement: StorageMeasurement,
        isDefault: Boolean = false,
        isProtected: Boolean = false,
        availability: RemoteAvailability = RemoteAvailability.Available,
    ) = VersionStorage(
        candidate = candidate,
        candidateDisplayName = candidate.replaceFirstChar(Char::titlecase),
        version = version,
        measurement = measurement,
        isDefault = isDefault,
        isProtected = isProtected,
        remoteAvailability = availability,
    )
}
