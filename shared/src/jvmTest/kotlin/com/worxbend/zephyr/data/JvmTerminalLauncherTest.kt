package com.worxbend.zephyr.data

import com.worxbend.zephyr.domain.InstallTarget
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmTerminalLauncherTest {
    @Test
    fun activationValuesStayOutOfTheShellScript() {
        var started: ProcessBuilder? = null
        val launcher =
            JvmTerminalLauncher(
                terminalCandidates = listOf(File("/bin/true")),
                bash = File("/bin/true"),
                processStarter = { started = it },
            )

        val result =
            launcher.launch(
                sdkmanHome = "/tmp/sdkman home",
                candidate = "java",
                version = "21.0.5-tem",
            )

        assertTrue(result.launched)
        val process = requireNotNull(started)
        val command = process.command()
        assertFalse(command.last().contains("21.0.5-tem"))
        assertEquals("java", process.environment()["ZEPHYR_SDKMAN_CANDIDATE"])
        assertEquals("21.0.5-tem", process.environment()["ZEPHYR_SDKMAN_VERSION"])
    }

    @Test
    fun rejectsControlCharactersBeforeStartingAProcess() {
        var started = false
        val launcher =
            JvmTerminalLauncher(
                terminalCandidates = listOf(File("/bin/true")),
                bash = File("/bin/true"),
                processStarter = { started = true },
            )

        val result = launcher.launch("/tmp/sdkman", "java\ninvalid", "21")

        assertFalse(result.launched)
        assertFalse(started)
    }

    @Test
    fun terminalFamiliesUseTheirSupportedCommandSeparator() {
        assertEquals("--", terminalCommand(File("/usr/bin/ptyxis"), File("/bin/bash"))[1])
        assertEquals("-e", terminalCommand(File("/usr/bin/alacritty"), File("/bin/bash"))[1])
        assertEquals("/bin/bash", terminalCommand(File("/usr/bin/kitty"), File("/bin/bash"))[1])
    }

    @Test
    fun workspaceTerminalUsesProjectDirectoryAndEnvironmentOnlyWithoutChangingDefaults() {
        val project = Files.createTempDirectory("zephyr-terminal-project-")
        try {
            var started: ProcessBuilder? = null
            val launcher =
                JvmTerminalLauncher(
                    terminalCandidates = listOf(File("/bin/true")),
                    bash = File("/bin/true"),
                    processStarter = { started = it },
                )

            val result =
                launcher.launchWorkspace(
                    sdkmanHome = "/tmp/sdkman home",
                    workingDirectory = project.toString(),
                    targets =
                        listOf(
                            InstallTarget("java", "21.0.5-tem"),
                            InstallTarget("gradle", "9.0"),
                        ),
                )

            assertTrue(result.launched)
            val process = requireNotNull(started)
            assertEquals(project.toRealPath().toFile(), process.directory())
            assertEquals("2", process.environment()["ZEPHYR_SDKMAN_TARGET_COUNT"])
            assertEquals("java", process.environment()["ZEPHYR_SDKMAN_CANDIDATE_0"])
            assertEquals("21.0.5-tem", process.environment()["ZEPHYR_SDKMAN_VERSION_0"])
            assertEquals("gradle", process.environment()["ZEPHYR_SDKMAN_CANDIDATE_1"])
            val script = process.command().last()
            assertFalse(script.contains("21.0.5-tem"))
            assertFalse(script.contains("gradle"))
            assertFalse(script.contains("sdk default"))
            assertTrue(script.contains("sdk use"))
        } finally {
            project.toFile().deleteRecursively()
        }
    }

    @Test
    fun workspaceTerminalRejectsSymlinkedProjectDirectory() {
        val root = Files.createTempDirectory("zephyr-terminal-link-")
        try {
            val project = Files.createDirectory(root.resolve("project"))
            val link = root.resolve("linked-project")
            Files.createSymbolicLink(link, project)
            var started = false
            val launcher =
                JvmTerminalLauncher(
                    terminalCandidates = listOf(File("/bin/true")),
                    bash = File("/bin/true"),
                    processStarter = { started = true },
                )

            val result =
                launcher.launchWorkspace(
                    "/tmp/sdkman",
                    link.toString(),
                    listOf(InstallTarget("java", "21-tem")),
                )

            assertFalse(result.launched)
            assertFalse(started)
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
