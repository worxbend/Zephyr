package com.worxbend.zephyr.storage

import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission

/** Single-process storage boundary; no cross-process compare-and-swap guarantee. */
internal class AtomicDocumentStore(private val destination: Path) {
    fun read(): String? {
        val attributes = try {
            Files.readAttributes(destination, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
        } catch (_: NoSuchFileException) {
            return null
        }
        check(attributes.isRegularFile && !attributes.isSymbolicLink) { "Document is not a regular file." }
        check(attributes.size() <= MAX_DOCUMENT_BYTES) { "Document is too large." }
        // Open without following the leaf even if it changes after the attribute check.
        FileChannel.open(destination, StandardOpenOption.READ, NOFOLLOW_LINKS).use { channel ->
            val bytes = java.io.ByteArrayOutputStream()
            val buffer = ByteBuffer.allocate(8_192)
            while (channel.read(buffer) >= 0) {
                buffer.flip()
                check(bytes.size().toLong() + buffer.remaining() <= MAX_DOCUMENT_BYTES) { "Document is too large." }
                val chunk = ByteArray(buffer.remaining())
                buffer.get(chunk)
                bytes.write(chunk)
                buffer.clear()
            }
            return UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
        }
    }

    fun replace(content: String) {
        val directory = requireNotNull(destination.toAbsolutePath().normalize().parent)
        Files.createDirectories(directory)
        check(!Files.isSymbolicLink(directory)) { "Document directory is a symbolic link." }
        ownerOnly(directory, directory = true)
        val temporary = Files.createTempFile(directory, ".task-center-", ".tmp")
        try {
            ownerOnly(temporary, directory = false)
            FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                val buffer = ByteBuffer.wrap(content.toByteArray(UTF_8))
                check(buffer.remaining().toLong() <= MAX_DOCUMENT_BYTES) { "Document is too large." }
                while (buffer.hasRemaining()) channel.write(buffer)
                channel.force(true)
            }
            // No non-atomic fallback: an unsupported replacement leaves the old document intact.
            Files.move(temporary, destination, ATOMIC_MOVE, REPLACE_EXISTING)
            // Directory fsync is best effort because it is not supported on every filesystem.
            runCatching { FileChannel.open(directory, StandardOpenOption.READ).use { it.force(true) } }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

private fun ownerOnly(path: Path, directory: Boolean) {
    val permissions = mutableSetOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
    if (directory) permissions += PosixFilePermission.OWNER_EXECUTE
    try {
        Files.setPosixFilePermissions(path, permissions)
    } catch (_: UnsupportedOperationException) {
        // Windows does not expose POSIX permissions; the platform directory ACL applies.
    }
}
private const val MAX_DOCUMENT_BYTES = 8L * 1_024 * 1_024
