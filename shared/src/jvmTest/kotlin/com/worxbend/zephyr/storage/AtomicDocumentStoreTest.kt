package com.worxbend.zephyr.storage

import com.worxbend.zephyr.data.operationTestDirectory
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AtomicDocumentStoreTest {
    @Test
    fun replacesCompleteDocumentWithOwnerOnlyPermissionsAndNoTemporaryResidue() {
        val directory = operationTestDirectory("zephyr-atomic-document-")
        try {
            val path = directory.resolve("journal")
            val store = AtomicDocumentStore(path)
            assertEquals(null, store.read())
            store.replace("first")
            store.replace("second")
            assertEquals("second", store.read())
            assertEquals(setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE), Files.getPosixFilePermissions(path))
            Files.list(directory).use { assertEquals(listOf(path), it.toList()) }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun rejectedOversizeReplacementPreservesOldBytesAndCleansTemporaryFile() {
        val directory = operationTestDirectory("zephyr-rejected-document-")
        try {
            val path = directory.resolve("journal")
            val store = AtomicDocumentStore(path)
            store.replace("old evidence")
            val bytes = Files.readAllBytes(path)
            assertFailsWith<IllegalStateException> { store.replace("x".repeat(8 * 1_024 * 1_024 + 1)) }
            assertContentEquals(bytes, Files.readAllBytes(path))
            Files.list(directory).use { assertEquals(listOf(path), it.toList()) }
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun replacementReplacesLeafSymlinkWithoutFollowingItsTarget() {
        val directory = operationTestDirectory("zephyr-leaf-replacement-")
        try {
            val external = directory.resolve("external")
            Files.writeString(external, "external evidence")
            val path = directory.resolve("journal")
            Files.createSymbolicLink(path, external)
            val store = AtomicDocumentStore(path)
            assertFailsWith<IllegalStateException> { store.read() }
            store.replace("app document")
            assertFalse(Files.isSymbolicLink(path))
            assertEquals("app document", store.read())
            assertEquals("external evidence", Files.readString(external))
        } finally { directory.toFile().deleteRecursively() }
    }

    @Test
    fun rejectsSymlinkDirectoryBeforeCreatingTemporaryFiles() {
        val directory = operationTestDirectory("zephyr-directory-replacement-")
        try {
            val external = directory.resolve("external")
            Files.createDirectory(external)
            val alias = directory.resolve("alias")
            Files.createSymbolicLink(alias, external)
            assertFailsWith<IllegalStateException> { AtomicDocumentStore(alias.resolve("journal")).replace("new") }
            Files.list(external).use { assertTrue(it.toList().isEmpty()) }
        } finally { directory.toFile().deleteRecursively() }
    }
}
