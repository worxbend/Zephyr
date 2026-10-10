package com.worxbend.zephyr.sdkman

import com.worxbend.zephyr.domain.CandidateCatalogItem
import com.worxbend.zephyr.domain.CandidateKind
import java.nio.file.Files
import okio.Path.Companion.toPath
import kotlin.io.path.deleteIfExists
import kotlin.test.Test
import kotlin.test.assertEquals

class JvmCandidateMetadataCacheStoreTest {
    @Test
    fun replacementDoesNotFollowDestinationSymlink() {
        val root = sdkmanTestDirectory("zephyr-cache-link-")
        try {
            val target = root.resolve("external")
            val path = root.resolve("catalog.cache")
            Files.writeString(target, "external fixture")
            Files.createSymbolicLink(path, target)
            JvmCandidateMetadataCacheStore(path) { 42L }.save(emptyList())
            assertEquals("external fixture", Files.readString(target))
            assertEquals(false, Files.isSymbolicLink(path))
            assertEquals(42L, JvmCandidateMetadataCacheStore(path).load()?.cachedAtEpochMillis)
        } finally {
            okio.FileSystem.SYSTEM.deleteRecursively(root.toString().toPath())
        }
    }

    @Test
    fun failedAtomicReplacementRetainsPriorCacheAndRemovesTemporaryFile() {
        val root = sdkmanTestDirectory("zephyr-cache-failure-")
        try {
            val path = root.resolve("catalog.cache")
            JvmCandidateMetadataCacheStore(path) { 42L }.save(emptyList())
            val before = Files.readAllBytes(path)
            JvmCandidateMetadataCacheStore(path, replace = { _, _ -> throw java.io.IOException("synthetic failure") }) { 99L }
                .save(emptyList())
            kotlin.test.assertContentEquals(before, Files.readAllBytes(path))
            assertEquals(42L, JvmCandidateMetadataCacheStore(path).load()?.cachedAtEpochMillis)
            Files.list(root).use { assertEquals(listOf(path), it.toList()) }
        } finally {
            okio.FileSystem.SYSTEM.deleteRecursively(root.toString().toPath())
        }
    }

    @Test
    fun rejectsSymlinkedCacheDirectoryWithoutExternalWrites() {
        val root = sdkmanTestDirectory("zephyr-cache-parent-")
        try {
            val outside = root.resolve("outside")
            Files.createDirectory(outside)
            Files.createSymbolicLink(root.resolve("zephyr"), outside)
            JvmCandidateMetadataCacheStore(root.resolve("zephyr/catalog.cache")).save(emptyList())
            Files.list(outside).use { assertEquals(0L, it.count()) }
        } finally {
            okio.FileSystem.SYSTEM.deleteRecursively(root.toString().toPath())
        }
    }

    @Test
    fun cacheRoundTripsMetadataAndTimestampDeterministically() {
        val root = sdkmanTestDirectory("zephyr-catalog-")
        val path = root.resolve("catalog.cache")
        try {
            val items = listOf(
                CandidateCatalogItem(
                    name = "gradle",
                    displayName = "Gradle",
                    stableVersion = "8.14",
                    description = "Build automation\twith unicode ✓",
                    websiteUrl = "https://gradle.org",
                    kind = CandidateKind.Sdk,
                    isInstalled = true,
                ),
            )
            val store = JvmCandidateMetadataCacheStore(path) { 42L }

            store.save(items)

            val loaded = store.load()!!
            assertEquals(42L, loaded.cachedAtEpochMillis)
            assertEquals(items.map { it.copy(isInstalled = false) }, loaded.items)
            assertEquals(
                renderCandidateCache(loaded),
                renderCandidateCache(parseCandidateCache(renderCandidateCache(loaded))),
            )
        } finally {
            path.deleteIfExists()
            Files.delete(root)
        }
    }
}
