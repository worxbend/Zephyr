package com.worxbend.zephyr.storage

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.channels.ReadableByteChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes

/** User-selected files: no build-root restriction, parent chmod, or directory creation.
 * Leaf symlinks are rejected; user directory shortcuts are permitted. Destination
 * changes are checked before commit, but this is not a hostile filesystem-race sandbox.
 */
internal class UserDocumentFiles(
    private val maxBytes: Int = 1_048_576,
    private val move: (Path, Path) -> Unit = { temporary, destination ->
        Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
    },
) {
    init {
        require(maxBytes > 0)
    }

    fun read(path: Path): String {
        val destination = path.toAbsolutePath().normalize()
        val attributes = requireNotNull(attributes(destination)) { "The selected document does not exist." }
        check(attributes.size() <= maxBytes) { "The selected document exceeds the $maxBytes byte safety limit." }
        FileChannel.open(destination, StandardOpenOption.READ, NOFOLLOW_LINKS).use { channel ->
            // Check and bound the opened channel too: the file can grow after stat.
            check(channel.size() <= maxBytes) { "The selected document is too large." }
            return readBoundedUtf8(channel, maxBytes)
        }
    }

    suspend fun write(
        path: Path,
        content: String,
        confirmOverwrite: () -> Boolean,
    ): Boolean {
        val selected = path.toAbsolutePath().normalize()
        val directory = requireNotNull(selected.parent).toRealPath()
        val destination = directory.resolve(selected.fileName)
        val original = attributes(destination)
        if (original != null && !confirmOverwrite()) return false
        currentCoroutineContext().ensureActive()
        val bytes = content.toByteArray(UTF_8)
        check(bytes.size <= maxBytes) { "The exported document exceeds the $maxBytes byte safety limit." }
        val temporary = Files.createTempFile(directory, ".zephyr-export-", ".tmp")
        try {
            FileChannel.open(temporary, StandardOpenOption.WRITE, NOFOLLOW_LINKS).use { channel ->
                val buffer = ByteBuffer.wrap(bytes)
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            val current = attributes(destination)
            check(current.sameVersionAs(original)) { "The selected document changed; choose it again before replacing it." }
            currentCoroutineContext().ensureActive()
            // Unsupported atomic moves fail; never fall back to truncating the old file.
            move(temporary, destination)
            runCatching { FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) } }
            return true
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    private fun attributes(path: Path): BasicFileAttributes? {
        val result =
            try {
                Files.readAttributes(path, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            } catch (_: NoSuchFileException) {
                return null
            }
        check(result.isRegularFile && !result.isSymbolicLink) { "The selected document must be a regular file, not a symbolic link." }
        return result
    }
}

private fun BasicFileAttributes?.sameVersionAs(other: BasicFileAttributes?): Boolean =
    if (this == null || other == null) {
        this == other
    } else {
        fileKey() == other.fileKey() && size() == other.size() && lastModifiedTime() == other.lastModifiedTime()
    }

internal fun readBoundedUtf8(
    channel: ReadableByteChannel,
    maxBytes: Int,
): String {
    require(maxBytes > 0)
    val bytes = ByteArrayOutputStream()
    val buffer = ByteBuffer.allocate(8_192)
    while (channel.read(buffer) >= 0) {
        buffer.flip()
        check(bytes.size().toLong() + buffer.remaining() <= maxBytes) { "Document is too large." }
        val chunk = ByteArray(buffer.remaining())
        buffer.get(chunk)
        bytes.write(chunk)
        buffer.clear()
    }
    return UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
}
