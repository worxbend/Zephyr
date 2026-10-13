package com.worxbend.zephyr.data

import com.worxbend.zephyr.domain.Candidate
import com.worxbend.zephyr.domain.CandidateCatalogItem
import com.worxbend.zephyr.domain.CandidateVersion
import com.worxbend.zephyr.domain.CommandOutcome
import com.worxbend.zephyr.domain.ConnectivityStatus
import com.worxbend.zephyr.domain.DiskImpactEstimate
import com.worxbend.zephyr.domain.IntegrityCheck
import com.worxbend.zephyr.domain.PlannedSdkmanCommand
import com.worxbend.zephyr.domain.ProtectedVersion
import com.worxbend.zephyr.domain.SdkmanSelfUpdateStatus
import com.worxbend.zephyr.domain.SdkmanStatus
import com.worxbend.zephyr.domain.SdkmanTransaction
import com.worxbend.zephyr.domain.StorageInventory

enum class CommandSatisfaction {
    Satisfied,
    Unsatisfied,
    Indeterminate,
}

interface SdkmanRepository {
    suspend fun detect(): SdkmanStatus

    suspend fun cliVersion(): String?

    suspend fun installedCandidates(): List<Candidate>

    suspend fun catalog(refreshMetadata: Boolean = false): List<CandidateCatalogItem>

    suspend fun cachedCatalog(): CandidateMetadataCache? = null

    suspend fun versions(candidate: String): List<CandidateVersion>

    suspend fun mergedCandidate(candidate: String): Candidate?

    suspend fun checkConnectivity(): ConnectivityStatus

    suspend fun integrityChecks(): List<IntegrityCheck>

    suspend fun estimateDiskImpact(transaction: SdkmanTransaction): DiskImpactEstimate

    suspend fun storageInventory(candidates: List<Candidate>): StorageInventory = StorageInventory.Empty

    suspend fun protectedVersions(): Set<ProtectedVersion>

    suspend fun setVersionProtected(
        candidate: String,
        version: String,
        protected: Boolean,
    ): CommandOutcome

    suspend fun refreshCandidateMetadata(): CommandOutcome

    suspend fun selfUpdate(): SdkmanSelfUpdateStatus

    suspend fun install(
        candidate: String,
        version: String,
    ): CommandOutcome

    suspend fun uninstall(
        candidate: String,
        version: String,
    ): CommandOutcome

    suspend fun setDefault(
        candidate: String,
        version: String,
    ): CommandOutcome

    suspend fun cleanLocalOnly(
        candidate: String,
        versions: List<String>,
    ): CommandOutcome

    suspend fun commandSatisfaction(command: PlannedSdkmanCommand): CommandSatisfaction = CommandSatisfaction.Indeterminate
}

data class CandidateMetadataCache(
    val cachedAtEpochMillis: Long,
    val items: List<CandidateCatalogItem>,
)

expect fun createSdkmanRepository(): SdkmanRepository
