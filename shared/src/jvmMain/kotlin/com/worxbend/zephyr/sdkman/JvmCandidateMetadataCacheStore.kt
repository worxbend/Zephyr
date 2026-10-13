package com.worxbend.zephyr.sdkman

import com.worxbend.zephyr.data.CandidateMetadataCache
import com.worxbend.zephyr.domain.CandidateCatalogItem
import com.worxbend.zephyr.domain.CandidateKind
import com.worxbend.zephyr.logging.ZephyrLogger
import com.worxbend.zephyr.storage.AtomicDocumentStore
import kotlinx.coroutines.CancellationException
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.util.Base64

interface CandidateMetadataCacheStore {
    fun load(): CandidateMetadataCache?

    fun save(items: List<CandidateCatalogItem>)
}

object NoOpCandidateMetadataCacheStore : CandidateMetadataCacheStore {
    override fun load(): CandidateMetadataCache? = null

    override fun save(items: List<CandidateCatalogItem>) = Unit
}

internal class JvmCandidateMetadataCacheStore(
    private val path: Path = defaultCandidateCachePath(),
    private val replace: (Path, Path) -> Unit = { temporary, destination ->
        Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
    },
    private val clock: () -> Long = System::currentTimeMillis,
) : CandidateMetadataCacheStore {
    override fun load(): CandidateMetadataCache? =
        runCatching {
            val destination = path.toAbsolutePath().normalize()
            validateCacheParent(destination.parent, create = false)
            AtomicDocumentStore(destination).read()?.let(::parseCandidateCache)
        }.getOrNull()

    override fun save(items: List<CandidateCatalogItem>) {
        var temporary: Path? = null
        try {
            val destination = path.toAbsolutePath().normalize()
            validateCacheParent(destination.parent, create = true)
            temporary = Files.createTempFile(destination.parent, ".zephyr-catalog-", ".tmp")
            val content = renderCandidateCache(CandidateMetadataCache(clock(), items)).toByteArray(StandardCharsets.UTF_8)
            require(content.size <= MAX_CANDIDATE_CACHE_BYTES) { "Candidate metadata cache is too large." }
            FileChannel.open(temporary, StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { channel ->
                val bytes = java.nio.ByteBuffer.wrap(content)
                while (bytes.hasRemaining()) channel.write(bytes)
                channel.force(true)
            }
            validateCacheParent(destination.parent, create = false)
            // Replace the directory entry itself, never open/truncate its target.
            // No non-atomic fallback: unsupported/failed replacement keeps old data.
            replace(temporary, destination)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            ZephyrLogger.warn("Candidate metadata cache could not be safely replaced; previous cache retained.")
        } finally {
            temporary?.let { runCatching { Files.deleteIfExists(it) } }
        }
    }
}

private fun validateCacheParent(
    parent: Path,
    create: Boolean,
) {
    var current = parent.root
    for (component in parent) {
        current = current.resolve(component)
        if (create && !Files.exists(current, NOFOLLOW_LINKS)) Files.createDirectory(current)
        require(Files.isDirectory(current, NOFOLLOW_LINKS) && !Files.isSymbolicLink(current)) {
            "Unsafe cache directory."
        }
    }
}

internal fun renderCandidateCache(cache: CandidateMetadataCache): String =
    buildString {
        appendLine("zephyr-candidate-cache=1")
        appendLine("cached-at-epoch-millis=${cache.cachedAtEpochMillis}")
        cache.items.sortedBy(CandidateCatalogItem::name).forEach { item ->
            appendLine(
                listOf(
                    item.name.encoded(),
                    item.displayName.encoded(),
                    item.stableVersion.encodedNullable(),
                    item.description.encodedNullable(),
                    item.websiteUrl.encodedNullable(),
                    item.kind.name,
                ).joinToString("\t"),
            )
        }
    }

internal fun parseCandidateCache(content: String): CandidateMetadataCache {
    val lines = content.lineSequence().filter(String::isNotBlank).toList()
    require(lines.firstOrNull() == "zephyr-candidate-cache=1") { "Unsupported candidate cache schema." }
    val cachedAt =
        lines
            .getOrNull(1)
            ?.takeIf { it.startsWith("cached-at-epoch-millis=") }
            ?.substringAfter('=')
            ?.toLongOrNull()
            ?: error("Candidate cache timestamp is invalid.")
    val items =
        lines.drop(2).map { line ->
            val fields = line.split('\t')
            require(fields.size == 6) { "Malformed candidate cache entry." }
            CandidateCatalogItem(
                name = fields[0].decoded(),
                displayName = fields[1].decoded(),
                stableVersion = fields[2].decodedNullable(),
                description = fields[3].decodedNullable(),
                websiteUrl = fields[4].decodedNullable(),
                kind = CandidateKind.valueOf(fields[5]),
                isInstalled = false,
            )
        }
    require(items.distinctBy(CandidateCatalogItem::name).size == items.size) {
        "Candidate cache contains duplicate entries."
    }
    return CandidateMetadataCache(cachedAt, items.sortedBy(CandidateCatalogItem::name))
}

private fun String.encoded(): String = Base64.getUrlEncoder().withoutPadding().encodeToString(toByteArray(StandardCharsets.UTF_8))

private fun String?.encodedNullable(): String = this?.encoded() ?: "-"

private fun String.decoded(): String = String(Base64.getUrlDecoder().decode(this), StandardCharsets.UTF_8)

private fun String.decodedNullable(): String? = if (this == "-") null else decoded()

private const val MAX_CANDIDATE_CACHE_BYTES = 8 * 1_024 * 1_024

private fun defaultCandidateCachePath(): Path {
    val root =
        System
            .getenv("XDG_CACHE_HOME")
            ?.takeIf(String::isNotBlank)
            ?.let(Path::of)
            ?: Path.of(System.getProperty("user.home"), ".cache")
    return root.resolve("zephyr").resolve("candidate-catalog-v1.cache")
}
