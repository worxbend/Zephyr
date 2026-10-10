package com.worxbend.zephyr.sdkman

import com.worxbend.zephyr.domain.CommandOutcomeStatus
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import okio.FileSystem
import okio.Path.Companion.toPath

class SdkmanFilesystemSafetyTest {
    @Test
    fun supportsTrustedHomeAliasAndAbsoluteCurrentLinkThroughThatAlias() = runBlocking {
        val root = sdkmanTestDirectory("sdkman-home-alias-")
        try {
            val real = root.resolve("real")
            val alias = root.resolve("alias")
            Files.createDirectories(real.resolve("bin"))
            Files.writeString(real.resolve("bin/sdkman-init.sh"), "# fixture")
            Files.createDirectories(real.resolve("candidates/gradle/8.0"))
            Files.createSymbolicLink(alias, real)
            Files.createSymbolicLink(real.resolve("candidates/gradle/current"), alias.resolve("candidates/gradle/8.0"))
            val repository = JvmSdkmanRepository(FileSystem.SYSTEM, { alias.toString().toPath() }) {
                object : SdkmanCommandRunner {
                    override suspend fun run(command: SdkmanCommand, timeout: kotlin.time.Duration): SdkmanCommandResult =
                        error("Already satisfied alias must not execute SDKMAN")
                }
            }
            assertTrue(repository.detect().isInstalled)
            assertEquals("8.0", repository.installedCandidates().single().defaultVersion)
            assertEquals(CommandOutcomeStatus.AlreadySatisfied, repository.install("gradle", "8.0").status)
            assertEquals(CommandOutcomeStatus.AlreadySatisfied, repository.setDefault("gradle", "8.0").status)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(root.toString().toPath())
        }
    }

    @Test
    fun mutationPostconditionRechecksAncestorAfterCommandCompletion() = runBlocking {
        val root = sdkmanTestDirectory("sdkman-postcondition-link-")
        try {
            val home = root.resolve("home")
            val outside = root.resolve("outside")
            Files.createDirectories(home.resolve("candidates/gradle"))
            Files.createDirectories(outside.resolve("8.0"))
            Files.writeString(outside.resolve("8.0/payload"), "external fixture")
            val repository = JvmSdkmanRepository(FileSystem.SYSTEM, { home.toString().toPath() }) {
                object : SdkmanCommandRunner {
                    override suspend fun run(command: SdkmanCommand, timeout: kotlin.time.Duration): SdkmanCommandResult {
                        Files.delete(home.resolve("candidates/gradle"))
                        Files.createSymbolicLink(home.resolve("candidates/gradle"), outside)
                        return SdkmanCommandResult(0, "finished", "")
                    }
                }
            }
            assertEquals(CommandOutcomeStatus.Indeterminate, repository.install("gradle", "8.0").status)
            assertEquals("external fixture", Files.readString(outside.resolve("8.0/payload")))
        } finally {
            FileSystem.SYSTEM.deleteRecursively(root.toString().toPath())
        }
    }

    @Test
    fun refusesCandidateAndRootAncestorLinksBeforeDirectAccess() = runBlocking {
        for (linkRoot in listOf(false, true)) {
            val fixture = sdkmanTestDirectory("sdkman-containment-")
            try {
                val home = fixture.resolve("home")
                val outside = fixture.resolve("outside")
                Files.createDirectories(home.resolve("bin"))
                Files.writeString(home.resolve("bin/sdkman-init.sh"), "# fixture")
                val externalVersion = if (linkRoot) outside.resolve("gradle/8.0") else outside.resolve("8.0")
                Files.createDirectories(externalVersion)
                Files.writeString(externalVersion.resolve("payload"), "external fixture")
                if (linkRoot) {
                    Files.createSymbolicLink(home.resolve("candidates"), outside)
                } else {
                    Files.createDirectories(home.resolve("candidates"))
                    Files.createSymbolicLink(home.resolve("candidates/gradle"), outside)
                }
                val commands = mutableListOf<SdkmanCommand>()
                val repository = JvmSdkmanRepository(FileSystem.SYSTEM, { home.toString().toPath() }) {
                    object : SdkmanCommandRunner {
                        override suspend fun run(command: SdkmanCommand, timeout: kotlin.time.Duration): SdkmanCommandResult {
                            commands += command
                            return SdkmanCommandResult(0, "8.0 9.0", "")
                        }
                    }
                }
                assertEquals(CommandOutcomeStatus.Indeterminate, repository.install("gradle", "8.0").status)
                assertEquals(CommandOutcomeStatus.Indeterminate, repository.uninstall("gradle", "8.0").status)
                assertEquals(CommandOutcomeStatus.Indeterminate, repository.setDefault("gradle", "8.0").status)
                assertFailsWith<IllegalStateException> { repository.mergedCandidate("gradle") }
                assertTrue(repository.installedCandidates().isEmpty())
                assertTrue(commands.isEmpty(), "Unsafe ancestors must not reach SDKMAN")
                assertEquals("external fixture", Files.readString(externalVersion.resolve("payload")))
            } finally {
                FileSystem.SYSTEM.deleteRecursively(fixture.toString().toPath())
            }
        }
    }
}
