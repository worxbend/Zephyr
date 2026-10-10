package com.worxbend.zephyr.sdkman

import com.worxbend.zephyr.domain.DiskImpactKind
import com.worxbend.zephyr.domain.EstimateConfidence
import com.worxbend.zephyr.domain.InstallTarget
import com.worxbend.zephyr.domain.PlannedSdkmanCommand
import com.worxbend.zephyr.domain.SdkmanCommandAction
import com.worxbend.zephyr.domain.SdkmanTransaction
import com.worxbend.zephyr.domain.UninstallTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking
import okio.FileMetadata
import okio.FileSystem
import okio.ForwardingFileSystem
import okio.Path
import okio.Path.Companion.toPath

class SdkmanDiskEstimateSafetyTest {
    @Test
    fun mixedKnownAndMissingBatchUninstallIsUnknown() = runBlocking {
        withInstallations { home ->
            val repository = repository(FileSystem.SYSTEM, home)
            val estimate = repository.estimateDiskImpact(SdkmanTransaction.BatchUninstall(listOf(
                UninstallTarget("gradle", "8.0"), UninstallTarget("gradle", "missing"),
            )))
            assertEquals(DiskImpactKind.Unknown, estimate.kind)
            assertEquals(EstimateConfidence.Unknown, estimate.confidence)
            assertNull(estimate.bytes)
        }
    }

    @Test
    fun everyAggregatePreservesOverflowAsUnknown() = runBlocking {
        withInstallations { home ->
            val fileSystem = object : ForwardingFileSystem(FileSystem.SYSTEM) {
                override fun metadataOrNull(path: Path): FileMetadata? =
                    if (path.name == "payload") FileMetadata(isRegularFile = true, isDirectory = false, size = Long.MAX_VALUE)
                    else super.metadataOrNull(path)
            }
            val repository = repository(fileSystem, home)
            val transactions = listOf(
                SdkmanTransaction.BatchUninstall(listOf(UninstallTarget("gradle", "8.0"), UninstallTarget("gradle", "9.0"))),
                SdkmanTransaction.CleanLocalOnly("gradle", listOf("8.0", "9.0")),
                SdkmanTransaction.BatchInstall(listOf(InstallTarget("gradle", "10.0"), InstallTarget("gradle", "11.0"))),
                SdkmanTransaction.SnapshotRestore(listOf(
                    PlannedSdkmanCommand(SdkmanCommandAction.Install, "gradle", "10.0"),
                    PlannedSdkmanCommand(SdkmanCommandAction.Install, "gradle", "11.0"),
                )),
            )
            for (transaction in transactions) {
                val estimate = repository.estimateDiskImpact(transaction)
                assertEquals(EstimateConfidence.Unknown, estimate.confidence, transaction.toString())
                assertNull(estimate.bytes, transaction.toString())
            }
        }
    }

    private fun repository(fileSystem: FileSystem, home: Path) = JvmSdkmanRepository(fileSystem, { home }) {
        object : SdkmanCommandRunner {
            override suspend fun run(command: SdkmanCommand, timeout: kotlin.time.Duration): SdkmanCommandResult =
                error("Disk estimates must not invoke SDKMAN")
        }
    }

    private suspend fun withInstallations(block: suspend (Path) -> Unit) {
        val root = sdkmanTestDirectory("sdkman-size-").toString().toPath()
        try {
            for (version in listOf("8.0", "9.0")) {
                FileSystem.SYSTEM.createDirectories(root / "candidates" / "gradle" / version)
                FileSystem.SYSTEM.write(root / "candidates" / "gradle" / version / "payload") { write(ByteArray(17)) }
            }
            block(root)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(root)
        }
    }
}
