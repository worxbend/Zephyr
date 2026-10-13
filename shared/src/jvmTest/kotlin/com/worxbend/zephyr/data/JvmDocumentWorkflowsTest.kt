package com.worxbend.zephyr.data

import com.worxbend.zephyr.domain.InstallTarget
import com.worxbend.zephyr.settings.AppSettings
import com.worxbend.zephyr.settings.portablePreferences
import com.worxbend.zephyr.storage.UserDocumentFiles
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JvmDocumentWorkflowsTest {
    private val snapshot =
        EnvironmentSnapshot(capturedAtEpochMillis = 42, candidates = listOf(SnapshotCandidate("java", "21-tem", listOf("21-tem"))))
    private val preferences = AppSettings().portablePreferences()
    private val targets = listOf(InstallTarget("java", "21-tem"))

    @Test
    fun allServicesRoundTripUsingInjectedDialogsAndRealFiles() =
        runBlocking {
            val root = operationTestDirectory("zephyr-document-roundtrip-")
            try {
                val path = root.resolve(".sdkmanrc")
                val dialogs = MemoryDialogs(path)
                val snapshotService = JvmEnvironmentSnapshotService(dialogs)
                assertEquals(path.fileName.toString(), snapshotService.chooseAndWrite(snapshot)?.fileName)
                assertEquals(snapshot, snapshotService.chooseAndRead())
                val preferencesService = JvmPortablePreferencesService(dialogs)
                assertEquals(path.fileName.toString(), preferencesService.chooseAndWrite(preferences))
                assertEquals(preferences, preferencesService.chooseAndRead())
                val projectService = JvmProjectToolchainService(dialogs)
                assertEquals(path.fileName.toString(), projectService.chooseAndWrite(targets)?.fileName)
                assertEquals(targets, projectService.chooseAndRead()?.targets)
                assertEquals(targets, projectService.chooseWorkspace()?.targets)
                assertEquals(2, dialogs.confirmations)
                Files.list(root).use { assertEquals(listOf(path), it.toList()) }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun cancelledChoosersReturnNullAndDoNotCreateDocuments() =
        runBlocking {
            val dialogs = MemoryDialogs(null)
            workflows(dialogs).forEach { workflow ->
                assertEquals(null, workflow.read(), workflow.name)
                assertEquals(null, workflow.write(), workflow.name)
            }
            assertEquals(null, JvmProjectToolchainService(dialogs).chooseWorkspace())
            assertEquals(0, dialogs.confirmations)
        }

    @Test
    fun allServicesRespectDeniedOverwrite() =
        runBlocking {
            val root = operationTestDirectory("zephyr-document-overwrite-")
            try {
                val path = root.resolve(".sdkmanrc")
                Files.writeString(path, "previous bytes")
                val dialogs = MemoryDialogs(path, approved = false)
                workflows(dialogs).forEach { workflow ->
                    assertEquals(null, workflow.write(), workflow.name)
                    assertEquals("previous bytes", Files.readString(path), workflow.name)
                }
                assertEquals(3, dialogs.confirmations)
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun allServicesRejectOversizedImports() =
        runBlocking {
            val root = operationTestDirectory("zephyr-document-large-")
            try {
                val path = root.resolve(".sdkmanrc")
                Files.writeString(path, "\n".repeat(1_048_577))
                workflows(MemoryDialogs(path)).forEach { workflow ->
                    val failure = assertFailsWith<IllegalStateException> { workflow.read() }
                    assertTrue("safety limit" in failure.message.orEmpty(), workflow.name)
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun allServicesPropagateFailedAtomicExportsWithoutTruncation() =
        runBlocking {
            val root = operationTestDirectory("zephyr-document-export-failure-")
            try {
                val path = root.resolve(".sdkmanrc")
                Files.writeString(path, "previous bytes")
                val files = UserDocumentFiles(move = { _, _ -> throw java.io.IOException("fixture move failure") })
                workflows(MemoryDialogs(path), files).forEach { workflow ->
                    assertFailsWith<java.io.IOException> { workflow.write() }
                    assertEquals("previous bytes", Files.readString(path), workflow.name)
                    Files.list(root).use { assertEquals(listOf(path), it.toList()) }
                }
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    private fun workflows(
        dialogs: DocumentDialogs,
        files: UserDocumentFiles = UserDocumentFiles(),
    ): List<Workflow> {
        val snapshots = JvmEnvironmentSnapshotService(dialogs, files)
        val portable = JvmPortablePreferencesService(dialogs, files)
        val project = JvmProjectToolchainService(dialogs, files)
        return listOf(
            Workflow("snapshot", { snapshots.chooseAndRead() }, { snapshots.chooseAndWrite(snapshot) }),
            Workflow("preferences", { portable.chooseAndRead() }, { portable.chooseAndWrite(preferences) }),
            Workflow("project", { project.chooseAndRead() }, { project.chooseAndWrite(targets) }),
        )
    }

    private data class Workflow(
        val name: String,
        val read: suspend () -> Any?,
        val write: suspend () -> Any?,
    )

    private class MemoryDialogs(
        private val selected: Path?,
        private val approved: Boolean = true,
    ) : DocumentDialogs {
        var confirmations = 0

        override fun chooseOpen(
            title: String,
            extension: String,
        ) = selected

        override fun chooseSave(
            title: String,
            suggestedName: String,
            extension: String,
        ) = selected

        override fun confirmOverwrite(path: Path): Boolean {
            confirmations++
            return approved
        }
    }
}
