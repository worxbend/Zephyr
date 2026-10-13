package com.worxbend.zephyr.storage

import com.worxbend.zephyr.data.operationTestDirectory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.nio.channels.Channels
import java.nio.charset.MalformedInputException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class UserDocumentFilesTest {
    @Test
    fun boundedReadDoesNotTrustASeparateFileSizeCheck() {
        val channel = Channels.newChannel(ByteArrayInputStream("x".repeat(32).toByteArray()))
        channel.use { assertFailsWith<IllegalStateException> { readBoundedUtf8(it, 16) } }
    }

    @Test
    fun readsExactByteLimitAndRejectsMalformedUtf8() {
        Channels.newChannel(ByteArrayInputStream("✓".toByteArray())).use {
            assertEquals("✓", readBoundedUtf8(it, 3))
        }
        Channels.newChannel(ByteArrayInputStream(byteArrayOf(0xc3.toByte()))).use {
            assertFailsWith<MalformedInputException> { readBoundedUtf8(it, 3) }
        }
    }

    @Test
    fun selectedDocumentsRejectLeafLinksButAllowLinkedUserDirectories() {
        val root = operationTestDirectory("zephyr-selected-read-")
        try {
            val directory = Files.createDirectory(root.resolve("documents"))
            val document = directory.resolve("snapshot")
            Files.writeString(document, "fixture")
            val directoryAlias = root.resolve("shortcut")
            Files.createSymbolicLink(directoryAlias, directory)
            assertEquals("fixture", UserDocumentFiles().read(directoryAlias.resolve("snapshot")))
            val leafAlias = root.resolve("leaf")
            Files.createSymbolicLink(leafAlias, document)
            assertFailsWith<IllegalStateException> { UserDocumentFiles().read(leafAlias) }
            assertFailsWith<IllegalStateException> { UserDocumentFiles(3).read(document) }
        } finally {
            root.toFile().deleteRecursively()
        }
    }

    @Test
    fun deniedOverwriteLeavesDocumentAndDirectoryPermissionsUntouched() =
        runBlocking {
            val root = operationTestDirectory("zephyr-selected-denied-")
            try {
                val document = root.resolve("snapshot")
                Files.writeString(document, "previous")
                val permissions = Files.getPosixFilePermissions(root)
                assertFalse(UserDocumentFiles().write(document, "new") { false })
                assertEquals("previous", Files.readString(document))
                assertEquals(permissions, Files.getPosixFilePermissions(root))
                Files.list(root).use { assertEquals(listOf(document), it.toList()) }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun failedAtomicMoveRetainsOldBytesAndCleansTemporaryFile() =
        runBlocking {
            val root = operationTestDirectory("zephyr-selected-failure-")
            try {
                val document = root.resolve("snapshot")
                Files.writeString(document, "previous")
                val files = UserDocumentFiles(move = { _, _ -> throw java.io.IOException("fixture failure") })
                assertFailsWith<java.io.IOException> { files.write(document, "new") { true } }
                assertEquals("previous", Files.readString(document))
                Files.list(root).use { assertEquals(listOf(document), it.toList()) }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun changedDestinationDuringConfirmationIsNotOverwritten() =
        runBlocking {
            val root = operationTestDirectory("zephyr-selected-changed-")
            try {
                val document = root.resolve("snapshot")
                Files.writeString(document, "previous")
                assertFailsWith<IllegalStateException> {
                    UserDocumentFiles().write(document, "new") {
                        Files.writeString(document, "changed by another writer")
                        true
                    }
                }
                assertEquals("changed by another writer", Files.readString(document))
                Files.list(root).use { assertEquals(listOf(document), it.toList()) }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun cancellationDuringConfirmationDoesNotWrite() =
        runBlocking {
            val root = operationTestDirectory("zephyr-selected-cancelled-")
            try {
                val document = root.resolve("snapshot")
                Files.writeString(document, "previous")
                assertFailsWith<CancellationException> {
                    UserDocumentFiles().write(document, "new") { throw CancellationException("fixture cancellation") }
                }
                assertEquals("previous", Files.readString(document))
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun exportRejectsLeafLinksWithoutChangingTheirTargets() =
        runBlocking {
            val root = operationTestDirectory("zephyr-selected-write-link-")
            try {
                val external = root.resolve("external")
                Files.writeString(external, "external fixture")
                val link = root.resolve("snapshot")
                Files.createSymbolicLink(link, external)
                assertFailsWith<IllegalStateException> { UserDocumentFiles().write(link, "new") { true } }
                assertEquals("external fixture", Files.readString(external))
                Unit
            } finally {
                root.toFile().deleteRecursively()
            }
        }
}
