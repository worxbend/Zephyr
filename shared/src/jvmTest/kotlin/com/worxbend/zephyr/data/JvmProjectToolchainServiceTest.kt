package com.worxbend.zephyr.data

import com.worxbend.zephyr.domain.InstallTarget
import com.worxbend.zephyr.settings.ProjectWorkspaceReference
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class JvmProjectToolchainServiceTest {
    @Test
    fun reloadsPinnedSdkmanRcFromCanonicalProjectDirectory() =
        runBlocking {
            val root = Files.createTempDirectory("zephyr-workspace-")
            try {
                val project = Files.createDirectory(root.resolve("backend"))
                val sdkmanRc = project.resolve(".sdkmanrc")
                Files.writeString(sdkmanRc, "java=21-tem\ngradle=9.0\n")
                val reference = ProjectWorkspaceReference(sdkmanRc.toString(), "Backend")

                val document = JvmProjectToolchainService().readWorkspace(reference)

                assertEquals(project.toRealPath().toString(), document.projectDirectory)
                assertEquals(project.fileName.toString(), document.reference.displayName)
                assertEquals(
                    listOf(InstallTarget("java", "21-tem"), InstallTarget("gradle", "9.0")),
                    document.targets,
                )
            } finally {
                root.toFile().deleteRecursively()
            }
        }

    @Test
    fun rejectsSymlinkedOrMisnamedWorkspaceFiles() =
        runBlocking {
            val root = Files.createTempDirectory("zephyr-workspace-unsafe-")
            try {
                val project = Files.createDirectory(root.resolve("project"))
                val real = project.resolve("toolchain")
                Files.writeString(real, "java=21-tem\n")
                val linked = project.resolve(".sdkmanrc")
                Files.createSymbolicLink(linked, real)
                val misnamed = project.resolve("sdkmanrc.txt")
                Files.writeString(misnamed, "java=21-tem\n")
                val service = JvmProjectToolchainService()

                assertFailsWith<IllegalArgumentException> {
                    service.readWorkspace(ProjectWorkspaceReference(linked.toString(), "Linked"))
                }
                assertFailsWith<IllegalArgumentException> {
                    service.readWorkspace(ProjectWorkspaceReference(misnamed.toString(), "Misnamed"))
                }
                Unit
            } finally {
                root.toFile().deleteRecursively()
            }
        }
}
