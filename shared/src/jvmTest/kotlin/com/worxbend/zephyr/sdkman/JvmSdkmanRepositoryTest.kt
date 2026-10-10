package com.worxbend.zephyr.sdkman

import com.worxbend.zephyr.domain.DiskImpactKind
import com.worxbend.zephyr.domain.EstimateConfidence
import com.worxbend.zephyr.domain.ProtectedVersion
import com.worxbend.zephyr.domain.ConnectivityState
import com.worxbend.zephyr.domain.ConnectivityDiagnostic
import com.worxbend.zephyr.domain.ConnectivityOutcome
import com.worxbend.zephyr.domain.ConnectivityRouteKind
import com.worxbend.zephyr.domain.CommandOutcomeStatus
import com.worxbend.zephyr.domain.IntegrityCheckId
import com.worxbend.zephyr.domain.IntegrityStatus
import com.worxbend.zephyr.domain.SdkmanTransaction
import com.worxbend.zephyr.domain.StorageMeasurement
import com.worxbend.zephyr.domain.StorageUnknownReason
import com.worxbend.zephyr.domain.StorageCleanupDisposition
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking

class JvmSdkmanRepositoryTest {
    @Test
    fun measuresLogicalPayloadAndRejectsSymlinksInsteadOfFollowingOrSkippingThem() {
        val root = sdkmanTestDirectory("zephyr-storage-test-").toString().toPath()
        try {
            val fileSystem = FileSystem.SYSTEM
            fileSystem.createDirectories(root / "nested")
            fileSystem.write(root / "runtime.bin") { write(ByteArray(1_024)) }
            fileSystem.write(root / "nested" / "metadata.bin") { write(ByteArray(512)) }

            assertEquals(StorageMeasurement.Exact(1_536), measureStorageDirectory(fileSystem, root))

            fileSystem.createSymlink(root / "nested" / "escape", root / "runtime.bin")
            assertEquals(
                StorageMeasurement.Unknown(StorageUnknownReason.SymbolicLink),
                measureStorageDirectory(fileSystem, root),
            )
        } finally {
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun marksPayloadUnknownWhenSafeTraversalCapIsExceeded() {
        val root = sdkmanTestDirectory("zephyr-storage-cap-test-").toString().toPath()
        try {
            FileSystem.SYSTEM.write(root / "one.bin") { writeByte(1) }
            FileSystem.SYSTEM.write(root / "two.bin") { writeByte(2) }

            assertEquals(
                StorageMeasurement.Unknown(StorageUnknownReason.EntryLimit),
                measureStorageDirectory(FileSystem.SYSTEM, root, maxEntries = 1),
            )
        } finally {
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
    }

    @Test
    fun storageInventoryPreservesDefaultProtectionAndRemoteEvidence() = runBlocking {
        val home = sdkmanTestDirectory("zephyr-storage-inventory-test-").toString().toPath()
        try {
            createSdkmanHome(home)
            val protected = ProtectedVersion("java", "17.0.1-tem")
            val repository = JvmSdkmanRepository(
                fileSystem = FileSystem.SYSTEM,
                sdkmanHomeResolver = { home },
                protectedVersionStore = InMemoryProtectedVersionStore(setOf(protected)),
            ) { RecordingRunner(home) }
            repository.detect()
            val candidate = requireNotNull(repository.mergedCandidate("java"))

            val inventory = repository.storageInventory(listOf(candidate))
            val byVersion = inventory.versions.associateBy { it.version }

            assertTrue(requireNotNull(byVersion["17.0.1-tem"]).isProtected)
            assertEquals(StorageCleanupDisposition.BlockedProtected, byVersion["17.0.1-tem"]?.cleanupDisposition)
            assertTrue(requireNotNull(byVersion["21.0.5-tem"]).isDefault)
            assertEquals(StorageCleanupDisposition.BlockedDefault, byVersion["21.0.5-tem"]?.cleanupDisposition)
            assertTrue(inventory.total.isExact)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(home, mustExist = false)
        }
    }

    @Test
    fun rejectsUnsafeCommandIdentifiersBeforeInvokingSdkman() = runBlocking {
        val runner = RecordingRunner()
        val repository = JvmSdkmanRepository(FileSystem.SYSTEM) { runner }

        val unsafeCandidate = repository.install("../java", "21.0.1-tem")
        val unsafeVersion = repository.uninstall("java", "21; rm -rf /")
        val emptyCleanRequest = repository.cleanLocalOnly("java", emptyList())

        assertFalse(unsafeCandidate.success)
        assertFalse(unsafeVersion.success)
        assertFalse(emptyCleanRequest.success)
        assertEquals(0, runner.commands.size)
    }

    @Test
    fun rejectsUnsafeCandidateBeforeAccessingFilesystem() = runBlocking {
        val repository = JvmSdkmanRepository(FileSystem.SYSTEM) { RecordingRunner() }

        assertFailsWith<IllegalArgumentException> { repository.mergedCandidate("../../etc") }
        Unit
    }

    @Test
    fun invalidatesTheCatalogAfterMetadataRefresh() = runBlocking {
        val runner = RecordingRunner()
        val home = sdkmanTestDirectory("zephyr-catalog-refresh-").toString().toPath()
        try {
            val repository = JvmSdkmanRepository(FileSystem.SYSTEM, { home }) { runner }
            repository.catalog(refreshMetadata = false)
            repository.catalog(refreshMetadata = false)
            repository.refreshCandidateMetadata()
            repository.catalog(refreshMetadata = false)
            assertEquals(2, runner.commands.count { it == SdkmanCommand.ListCandidates })
            assertEquals(1, runner.commands.count { it == SdkmanCommand.UpdateCandidateMetadata })
        } finally {
            FileSystem.SYSTEM.deleteRecursively(home)
        }
    }

    @Test
    fun acceptsStandardSdkmanIdentifiers() = runBlocking {
        val home = sdkmanTestDirectory("zephyr-sdkman-test-").toString().toPath()
        try {
            createSdkmanHome(home)
            val runner = RecordingRunner(home)
            val repository = JvmSdkmanRepository(FileSystem.SYSTEM, { home }) { runner }

            val outcome = repository.install("java", "25.0.1-tem")

            assertTrue(outcome.success)
            assertEquals(CommandOutcomeStatus.Applied, outcome.status)
            assertEquals(SdkmanCommand.Install("java", "25.0.1-tem"), runner.commands.single())
        } finally {
            FileSystem.SYSTEM.deleteRecursively(home, mustExist = false)
        }
    }

    @Test
    fun mergesFilesystemVersionsAndProtectsTheDefaultVersionDuringCleanup() = runBlocking {
        val home = sdkmanTestDirectory("zephyr-sdkman-test-").toString().toPath()
        try {
            createSdkmanHome(home)
            val runner = RecordingRunner(home)
            val repository = JvmSdkmanRepository(FileSystem.SYSTEM, { home }) { runner }

            assertTrue(repository.detect().isInstalled)
            val installed = repository.installedCandidates()
            val merged = repository.mergedCandidate("java")
            val cleanup = repository.cleanLocalOnly("java", listOf("21.0.5-tem"))

            assertEquals(listOf("java"), installed.map { it.name })
            assertEquals("21.0.5-tem", installed.single().defaultVersion)
            assertEquals(listOf("17.0.1-tem"), merged?.localOnlyVersions)
            assertTrue(merged?.installedVersions?.first { it.version == "17.0.1-tem" }?.isInstalled == true)
            assertFalse(cleanup.success)
            assertFalse(runner.commands.any { it is SdkmanCommand.Uninstall })
        } finally {
            FileSystem.SYSTEM.deleteRecursively(home, mustExist = false)
        }
    }

    @Test
    fun ignoresMalformedOrSymlinkedSdkmanEntriesDuringFilesystemDiscovery() = runBlocking {
        val home = sdkmanTestDirectory("zephyr-sdkman-test-").toString().toPath()
        try {
            createSdkmanHome(home)
            val fileSystem = FileSystem.SYSTEM
            fileSystem.createDirectories(home / "candidates" / "invalid;candidate" / "1.0")
            fileSystem.createDirectories(home / "candidates" / "java" / "invalid;version")
            fileSystem.createDirectories(home / "outside" / "99.0")
            fileSystem.createSymlink(home / "candidates" / "evil-link", home / "candidates" / "java")
            fileSystem.createDirectories(home / "candidates" / "gradle" / "8.0")
            fileSystem.createSymlink(home / "candidates" / "gradle" / "current", home / "outside" / "99.0")
            val repository = JvmSdkmanRepository(fileSystem, { home }) { RecordingRunner() }

            val installed = repository.installedCandidates()

            assertEquals(listOf("java", "gradle"), installed.map { it.name })
            assertEquals(listOf("17.0.1-tem", "21.0.5-tem"), installed.first { it.name == "java" }.installedVersions.map { it.version })
            assertNull(installed.first { it.name == "gradle" }.defaultVersion)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(home, mustExist = false)
        }
    }

    @Test
    fun cleansOnlyVersionsVerifiedAsLocalOnly() = runBlocking {
        val home = sdkmanTestDirectory("zephyr-sdkman-test-").toString().toPath()
        try {
            createSdkmanHome(home)
            val runner = RecordingRunner(home)
            val repository = JvmSdkmanRepository(FileSystem.SYSTEM, { home }) { runner }

            val unverified = repository.cleanLocalOnly("java", listOf("19.0.0-tem"))
            val verified = repository.cleanLocalOnly("java", listOf("17.0.1-tem"))

            assertFalse(unverified.success)
            assertEquals("Only versions confirmed as local-only can be cleaned.", unverified.message)
            assertTrue(verified.success)
            assertEquals(listOf(SdkmanCommand.Uninstall("java", "17.0.1-tem")), runner.commands.filterIsInstance<SdkmanCommand.Uninstall>())
        } finally {
            FileSystem.SYSTEM.deleteRecursively(home, mustExist = false)
        }
    }

    @Test
    fun estimatesExactCleanupAndMedianInstallDiskImpact() = runBlocking {
        val home = sdkmanTestDirectory("zephyr-sdkman-test-").toString().toPath()
        try {
            createSdkmanHome(home)
            val fileSystem = FileSystem.SYSTEM
            fileSystem.write(home / "candidates" / "java" / "17.0.1-tem" / "runtime.bin") {
                write(ByteArray(1_024))
            }
            fileSystem.write(home / "candidates" / "java" / "21.0.5-tem" / "runtime.bin") {
                write(ByteArray(3_072))
            }
            val repository = JvmSdkmanRepository(fileSystem, { home }) { RecordingRunner() }

            val cleanup = repository.estimateDiskImpact(
                SdkmanTransaction.CleanLocalOnly("java", listOf("17.0.1-tem", "21.0.5-tem")),
            )
            val install = repository.estimateDiskImpact(
                SdkmanTransaction.Install("java", "25.0.1-tem"),
            )

            assertEquals(DiskImpactKind.Reclaimable, cleanup.kind)
            assertEquals(4_096L, cleanup.bytes)
            assertEquals(EstimateConfidence.Exact, cleanup.confidence)
            assertEquals(DiskImpactKind.Required, install.kind)
            assertEquals(2_048L, install.bytes)
            assertEquals(EstimateConfidence.Estimated, install.confidence)
            assertTrue((install.availableBytes ?: 0) > 0)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(home, mustExist = false)
        }
    }

    @Test
    fun repositoryBoundaryBlocksProtectedCleanupAndUninstall() = runBlocking {
        val home = sdkmanTestDirectory("zephyr-sdkman-test-").toString().toPath()
        try {
            createSdkmanHome(home)
            val protected = ProtectedVersion("java", "17.0.1-tem")
            val store = InMemoryProtectedVersionStore(setOf(protected))
            val runner = RecordingRunner(home)
            val repository = JvmSdkmanRepository(FileSystem.SYSTEM, { home }, store) { runner }

            val uninstall = repository.uninstall("java", "17.0.1-tem")
            val cleanup = repository.cleanLocalOnly("java", listOf("17.0.1-tem"))

            assertFalse(uninstall.success)
            assertFalse(cleanup.success)
            assertTrue(uninstall.message.contains("Unpin"))
            assertTrue(cleanup.message.contains("Unpin"))
            assertFalse(runner.commands.any { it is SdkmanCommand.Uninstall })

            assertTrue(repository.setVersionProtected("java", "17.0.1-tem", false).success)
            assertTrue(repository.cleanLocalOnly("java", listOf("17.0.1-tem")).success)
            assertEquals(
                listOf(SdkmanCommand.Uninstall("java", "17.0.1-tem")),
                runner.commands.filterIsInstance<SdkmanCommand.Uninstall>(),
            )
        } finally {
            FileSystem.SYSTEM.deleteRecursively(home, mustExist = false)
        }
    }

    @Test
    fun repositoryBoundaryBlocksDefaultUninstall() = runBlocking {
        val home = sdkmanTestDirectory("zephyr-sdkman-test-").toString().toPath()
        try {
            createSdkmanHome(home)
            val runner = RecordingRunner()
            val repository = JvmSdkmanRepository(FileSystem.SYSTEM, { home }) { runner }

            val outcome = repository.uninstall("java", "21.0.5-tem")

            assertFalse(outcome.success)
            assertTrue(outcome.message.contains("another default"))
            assertFalse(runner.commands.any { it is SdkmanCommand.Uninstall })
        } finally {
            FileSystem.SYSTEM.deleteRecursively(home, mustExist = false)
        }
    }

    @Test
    fun protectedVersionsPersistAcrossStoreInstances() {
        val preferences = SdkmanMemoryPreferences()
        val protected = setOf(
            ProtectedVersion("java", "21.0.5-tem"),
            ProtectedVersion("gradle", "9.0.0"),
        )
        PreferencesProtectedVersionStore(preferences).save(protected)
        assertEquals(protected, PreferencesProtectedVersionStore(preferences).load())
    }

    @Test
    fun connectivityDiagnosticUsesTheReadOnlyRunnerBoundaryWithoutMutations() = runBlocking {
        val runner = object : SdkmanCommandRunner {
            val commands = mutableListOf<SdkmanCommand>()

            override suspend fun run(
                command: SdkmanCommand,
                timeout: kotlin.time.Duration,
            ): SdkmanCommandResult {
                commands += command
                error("Connectivity diagnostics must not invoke a normal SDKMAN command.")
            }

            override suspend fun diagnoseConnectivity(timeout: kotlin.time.Duration): ConnectivityDiagnostic =
                ConnectivityDiagnostic(
                    route = ConnectivityRouteKind.Proxy,
                    checkedAtEpochMillis = 321,
                    latencyMillis = 17,
                    outcome = ConnectivityOutcome.Online,
                )
        }
        val repository = JvmSdkmanRepository(
            fileSystem = FileSystem.SYSTEM,
            commandRunnerFactory = { runner },
        )

        val status = repository.checkConnectivity()

        assertEquals(ConnectivityState.Online, status.state)
        assertEquals(ConnectivityRouteKind.Proxy, status.diagnostic?.route)
        assertEquals(17, status.diagnostic?.latencyMillis)
        assertTrue(runner.commands.isEmpty())
    }

    @Test
    fun connectivityCancellationPropagates() = runBlocking {
        val runner = object : SdkmanCommandRunner {
            override suspend fun run(
                command: SdkmanCommand,
                timeout: kotlin.time.Duration,
            ) = error("No normal command expected.")

            override suspend fun diagnoseConnectivity(timeout: kotlin.time.Duration): ConnectivityDiagnostic =
                throw CancellationException("cancel diagnostic")
        }
        val repository = JvmSdkmanRepository(
            fileSystem = FileSystem.SYSTEM,
            commandRunnerFactory = { runner },
        )

        assertFailsWith<CancellationException> {
            repository.checkConnectivity()
        }
        Unit
    }

    @Test
    fun reportsIntegrityBoundariesIndependentlyAndRejectsEscapingCurrentLinks() = runBlocking {
        val home = sdkmanTestDirectory("zephyr-sdkman-test-").toString().toPath()
        try {
            createSdkmanHome(home)
            val fileSystem = FileSystem.SYSTEM
            REQUIRED_TEST_SCRIPTS.forEach { relative ->
                val path = home / relative
                fileSystem.createDirectories(path.parent!!)
                fileSystem.write(path) { writeUtf8("# test") }
            }
            fileSystem.createDirectories(home / "candidates" / "invalid;candidate" / "1.0")
            fileSystem.createDirectories(home / "candidates" / "java" / "invalid;version")
            fileSystem.createDirectories(home / "outside" / "21.0.5-tem")
            fileSystem.delete(home / "candidates" / "java" / "current")
            fileSystem.createSymlink(
                home / "candidates" / "java" / "current",
                home / "outside" / "21.0.5-tem",
            )
            val repository = JvmSdkmanRepository(fileSystem, { home }) { RecordingRunner() }

            val checks = repository.integrityChecks().associateBy { it.id }
            val installed = repository.installedCandidates()

            assertEquals(IntegrityStatus.Passed, checks[IntegrityCheckId.RequiredScripts]?.status)
            assertEquals(IntegrityStatus.Passed, checks[IntegrityCheckId.CandidatesDirectory]?.status)
            assertEquals(IntegrityStatus.Warning, checks[IntegrityCheckId.CandidateEntries]?.status)
            assertEquals(IntegrityStatus.Warning, checks[IntegrityCheckId.VersionEntries]?.status)
            assertEquals(IntegrityStatus.Failed, checks[IntegrityCheckId.DefaultLinks]?.status)
            assertNull(installed.first { it.name == "java" }.defaultVersion)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(home, mustExist = false)
        }
    }

    @Test
    fun refusesCleanupWhenRemoteVersionsCannotBeVerified() = runBlocking {
        val home = sdkmanTestDirectory("zephyr-sdkman-test-").toString().toPath()
        try {
            createSdkmanHome(home)
            val runner = FailingVersionsRunner()
            val repository = JvmSdkmanRepository(FileSystem.SYSTEM, { home }) { runner }

            assertFailsWith<IllegalStateException> { repository.mergedCandidate("java") }
            val cleanup = repository.cleanLocalOnly("java", listOf("17.0.1-tem"))

            assertFalse(cleanup.success)
            assertEquals("Unable to verify local-only versions. Try scanning again.", cleanup.message)
            assertFalse(runner.commands.any { it is SdkmanCommand.Uninstall })
        } finally {
            FileSystem.SYSTEM.deleteRecursively(home, mustExist = false)
        }
    }

    @Test
    fun blocksCleanupWhenVersionOutputIsOnlyPartiallyParsed() = runBlocking {
        val home = sdkmanTestDirectory("zephyr-sdkman-test-").toString().toPath()
        try {
            createSdkmanHome(home)
            val runner = object : SdkmanCommandRunner {
                val commands = mutableListOf<SdkmanCommand>()

                override suspend fun run(command: SdkmanCommand, timeout: kotlin.time.Duration): SdkmanCommandResult {
                    commands += command
                    return SdkmanCommandResult(
                        exitCode = 0,
                        stdout = if (command is SdkmanCommand.ListVersions) PARTIAL_JAVA_VERSIONS_OUTPUT else "completed",
                        stderr = "",
                    )
                }
            }
            val repository = JvmSdkmanRepository(FileSystem.SYSTEM, { home }) { runner }

            val merged = repository.mergedCandidate("java")
            val outcome = repository.cleanLocalOnly("java", listOf("17.0.1-tem"))

            assertTrue(merged?.localOnlyVersions.isNullOrEmpty())
            assertFalse(outcome.success)
            assertEquals("Cleanup is blocked because live SDKMAN version evidence is incomplete.", outcome.message)
            assertFalse(runner.commands.any { it is SdkmanCommand.Uninstall })
        } finally {
            FileSystem.SYSTEM.deleteRecursively(home, mustExist = false)
        }
    }

    @Test
    fun doesNotTrustSuccessfulExitWithoutInstallPostcondition() = runBlocking {
        val home = sdkmanTestDirectory("zephyr-sdkman-test-").toString().toPath()
        try {
            createSdkmanHome(home)
            val runner = RecordingRunner(home, applyMutations = false)
            val repository = JvmSdkmanRepository(FileSystem.SYSTEM, { home }) { runner }

            val outcome = repository.install("java", "25.0.1-tem")

            assertFalse(outcome.success)
            assertEquals(CommandOutcomeStatus.Failed, outcome.status)
            assertTrue(outcome.message.contains("not installed"))
        } finally {
            FileSystem.SYSTEM.deleteRecursively(home, mustExist = false)
        }
    }

    @Test
    fun reportsAppliedWithWarningWhenFilesystemChangedDespiteNonzeroExit() = runBlocking {
        val home = sdkmanTestDirectory("zephyr-sdkman-test-").toString().toPath()
        try {
            createSdkmanHome(home)
            val runner = RecordingRunner(home, mutationExitCode = 7)
            val repository = JvmSdkmanRepository(FileSystem.SYSTEM, { home }) { runner }

            val outcome = repository.install("java", "25.0.1-tem")

            assertTrue(outcome.success)
            assertEquals(CommandOutcomeStatus.AppliedWithWarning, outcome.status)
            assertTrue(outcome.message.contains("code 7"))
        } finally {
            FileSystem.SYSTEM.deleteRecursively(home, mustExist = false)
        }
    }

    @Test
    fun verifiesDefaultAndUninstallPostconditions() = runBlocking {
        val home = sdkmanTestDirectory("zephyr-sdkman-test-").toString().toPath()
        try {
            createSdkmanHome(home)
            val runner = RecordingRunner(home)
            val repository = JvmSdkmanRepository(FileSystem.SYSTEM, { home }) { runner }

            val changedDefault = repository.setDefault("java", "17.0.1-tem")
            val removedPrevious = repository.uninstall("java", "21.0.5-tem")

            assertEquals(CommandOutcomeStatus.Applied, changedDefault.status)
            assertEquals(CommandOutcomeStatus.Applied, removedPrevious.status)
            assertFalse(FileSystem.SYSTEM.exists(home / "candidates" / "java" / "21.0.5-tem"))
        } finally {
            FileSystem.SYSTEM.deleteRecursively(home, mustExist = false)
        }
    }

    private fun createSdkmanHome(home: Path) {
        val fileSystem = FileSystem.SYSTEM
        fileSystem.createDirectories(home / "bin")
        fileSystem.createDirectories(home / "candidates" / "java" / "17.0.1-tem")
        fileSystem.createDirectories(home / "candidates" / "java" / "21.0.5-tem")
        fileSystem.write(home / "bin" / "sdkman-init.sh") { writeUtf8("# test SDKMAN init") }
        fileSystem.createSymlink(home / "candidates" / "java" / "current", home / "candidates" / "java" / "21.0.5-tem")
    }

    private class RecordingRunner(
        private val home: Path? = null,
        private val mutationExitCode: Int = 0,
        private val applyMutations: Boolean = true,
    ) : SdkmanCommandRunner {
        val commands = mutableListOf<SdkmanCommand>()

        override suspend fun run(command: SdkmanCommand, timeout: kotlin.time.Duration): SdkmanCommandResult {
            commands += command
            if (applyMutations && home != null) {
                when (command) {
                    is SdkmanCommand.Install ->
                        FileSystem.SYSTEM.createDirectories(home / "candidates" / command.candidate / command.version)
                    is SdkmanCommand.Uninstall ->
                        FileSystem.SYSTEM.deleteRecursively(
                            home / "candidates" / command.candidate / command.version,
                            mustExist = false,
                        )
                    is SdkmanCommand.SetDefault -> {
                        val current = home / "candidates" / command.candidate / "current"
                        FileSystem.SYSTEM.delete(current, mustExist = false)
                        FileSystem.SYSTEM.createSymlink(
                            current,
                            home / "candidates" / command.candidate / command.version,
                        )
                    }
                    else -> Unit
                }
            }
            val isMutation = command is SdkmanCommand.Install ||
                command is SdkmanCommand.Uninstall ||
                command is SdkmanCommand.SetDefault
            return SdkmanCommandResult(
                exitCode = if (isMutation) mutationExitCode else 0,
                stdout = when (command) {
                    SdkmanCommand.ListCandidates -> CATALOG_OUTPUT
                    is SdkmanCommand.ListVersions -> JAVA_VERSIONS_OUTPUT
                    else -> "Command completed"
                },
                stderr = "",
            )
        }
    }

    private class FailingVersionsRunner : SdkmanCommandRunner {
        val commands = mutableListOf<SdkmanCommand>()

        override suspend fun run(command: SdkmanCommand, timeout: kotlin.time.Duration): SdkmanCommandResult {
            commands += command
            return if (command is SdkmanCommand.ListVersions) {
                SdkmanCommandResult(exitCode = 1, stdout = "", stderr = "network unavailable")
            } else {
                SdkmanCommandResult(exitCode = 0, stdout = "Command completed", stderr = "")
            }
        }
    }

    private companion object {
        val REQUIRED_TEST_SCRIPTS = listOf(
            "src/sdkman-main.sh",
            "src/sdkman-list.sh",
            "src/sdkman-install.sh",
            "src/sdkman-uninstall.sh",
            "src/sdkman-default.sh",
            "src/sdkman-update.sh",
            "src/sdkman-selfupdate.sh",
        )

        const val CATALOG_OUTPUT = """
            ================================================================================
            Available Candidates
            ================================================================================
            Ant (1.10.17)                                            https://ant.apache.org/

            Apache Ant is a Java library and command-line tool.

                                                                   ${'$'} sdk install ant
        """

        const val JAVA_VERSIONS_OUTPUT = """
            Available Java Versions for Linux 64bit
            ================================================================================
             Vendor        | Use | Version      | Dist    | Status     | Identifier
            --------------------------------------------------------------------------------
             Temurin       | >>> | 21.0.5       | tem     |            | 21.0.5-tem
             Temurin       |     | 17.0.1       | tem     | local only | 17.0.1-tem
            ================================================================================
        """

        const val PARTIAL_JAVA_VERSIONS_OUTPUT = """
            Available Java Versions for Linux 64bit
            ================================================================================
             Vendor        | Use | Version      | Dist    | Status     | Identifier
            --------------------------------------------------------------------------------
             Temurin       |     | 17.0.1       | tem     | local only | 17.0.1-tem
             changed upstream row | unknown-2027!
            ================================================================================
        """
    }
}
