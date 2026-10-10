package com.worxbend.zephyr.sdkman

import com.worxbend.zephyr.data.SdkmanRepository
import com.worxbend.zephyr.data.CommandSatisfaction
import com.worxbend.zephyr.domain.Candidate
import com.worxbend.zephyr.domain.CandidateCatalogItem
import com.worxbend.zephyr.domain.CandidateVersion
import com.worxbend.zephyr.domain.CommandOutcome
import com.worxbend.zephyr.domain.CommandOutcomeStatus
import com.worxbend.zephyr.domain.ConnectivityState
import com.worxbend.zephyr.domain.ConnectivityStatus
import com.worxbend.zephyr.domain.DiskImpactEstimate
import com.worxbend.zephyr.domain.DiskImpactKind
import com.worxbend.zephyr.domain.EstimateConfidence
import com.worxbend.zephyr.domain.IntegrityCheck
import com.worxbend.zephyr.domain.IntegrityCheckId
import com.worxbend.zephyr.domain.IntegrityStatus
import com.worxbend.zephyr.domain.ProtectedVersion
import com.worxbend.zephyr.domain.PlannedSdkmanCommand
import com.worxbend.zephyr.domain.RemoteAvailability
import com.worxbend.zephyr.domain.RemoteEvidenceState
import com.worxbend.zephyr.domain.SdkmanSelfUpdateStatus
import com.worxbend.zephyr.domain.SdkmanCommandAction
import com.worxbend.zephyr.domain.SdkmanStatus
import com.worxbend.zephyr.domain.SdkmanTransaction
import com.worxbend.zephyr.domain.StorageInventory
import com.worxbend.zephyr.domain.StorageMeasurement
import com.worxbend.zephyr.domain.StorageUnknownReason
import com.worxbend.zephyr.domain.VersionStorage
import com.worxbend.zephyr.domain.candidateKindFor
import com.worxbend.zephyr.domain.displayNameFor
import com.worxbend.zephyr.domain.isValidSdkmanCandidateName
import com.worxbend.zephyr.domain.isValidSdkmanVersion
import com.worxbend.zephyr.logging.ZephyrLogger
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class JvmSdkmanRepository(
    private val fileSystem: FileSystem,
    private val sdkmanHomeResolver: () -> Path = ::defaultSdkmanHome,
    private val protectedVersionStore: ProtectedVersionStore = InMemoryProtectedVersionStore(),
    private val candidateCacheStore: CandidateMetadataCacheStore = NoOpCandidateMetadataCacheStore,
    private val commandRunnerFactory: (Path) -> SdkmanCommandRunner,
) : SdkmanRepository {
    private var sdkmanHome: Path? = null
    private var commandRunner: SdkmanCommandRunner? = null
    private var catalogCache: List<CandidateCatalogItem>? = null

    override suspend fun detect(): SdkmanStatus {
        val home = locateHome()
        sdkmanHome = home
        commandRunner = commandRunnerFactory(home)

        val init = home / "bin" / "sdkman-init.sh"
        val candidates = home / "candidates"
        val missing = when {
            fileSystem.metadataOrNull(init) == null -> "Missing SDKMAN init script at $init."
            !inspector().safeRoot() -> "Missing SDKMAN candidates directory at $candidates."
            else -> null
        }

        return if (missing == null) {
            SdkmanStatus(isInstalled = true, home = home.toString())
        } else {
            SdkmanStatus(isInstalled = false, home = home.toString(), reason = missing)
        }
    }

    override suspend fun cliVersion(): String? {
        val result = runner().run(SdkmanCommand.Version, 8.seconds)
        if (!result.success) {
            ZephyrLogger.warn("Unable to read SDKMAN CLI version: ${result.output.ifBlank { "exit ${result.exitCode}" }}")
        }
        return result.stdout.lineSequence().firstOrNull { it.contains("SDKMAN", ignoreCase = true) }?.trim()
            ?: result.stdout.trim().takeIf { it.isNotBlank() }
    }

    override suspend fun installedCandidates(): List<Candidate> {
        val candidatesPath = home() / "candidates"
        if (!inspector().safeRoot()) return emptyList()
        return fileSystem.listOrNull(candidatesPath).orEmpty()
            .mapNotNull { candidatePath ->
                val metadata = fileSystem.metadataOrNull(candidatePath) ?: return@mapNotNull null
                candidatePath.name.takeIf {
                    metadata.isDirectory && metadata.symlinkTarget == null && isValidCandidate(it)
                }
            }
            .mapNotNull { name ->
                val versions = installedVersionsFor(name)
                if (versions.isEmpty()) return@mapNotNull null
                val default = defaultVersionFor(name)
                val kind = candidateKindFor(name)
                Candidate(
                    name = name,
                    displayName = displayNameFor(name),
                    description = null,
                    websiteUrl = null,
                    kind = kind,
                    installedVersions = versions.map {
                        CandidateVersion(
                            version = it,
                            isInstalled = true,
                            isDefault = it == default,
                            remoteAvailability = RemoteAvailability.Unknown,
                        )
                    },
                    defaultVersion = default,
                    hasLocalOnlyVersions = false,
                    localOnlyVersionCount = 0,
                    localOnlyVersions = emptyList(),
                )
            }
            .sortedWith(compareBy<Candidate> { it.kind != com.worxbend.zephyr.domain.CandidateKind.Jdk }.thenBy { it.displayName })
    }

    override suspend fun catalog(refreshMetadata: Boolean): List<CandidateCatalogItem> {
        if (!refreshMetadata) catalogCache?.let { return it }
        if (refreshMetadata) {
            val updateOutcome = refreshCandidateMetadata()
            if (!updateOutcome.success) {
                ZephyrLogger.warn("Continuing catalog load after metadata refresh failure: ${updateOutcome.message}")
            }
        }
        val installed = installedCandidates().map { it.name }.toSet()
        val result = runner().run(SdkmanCommand.ListCandidates, 20.seconds)
        if (!result.success) {
            val message = "sdk list failed: ${result.output.ifBlank { "exit ${result.exitCode}" }}"
            ZephyrLogger.warn(message)
            throw IllegalStateException(message)
        }
        val parsed = SdkmanListParser.parseCatalog(result.stdout, installed)
        if (parsed.isEmpty()) {
            val message = "sdk list returned no parseable packages. Output started with: ${result.stdout.take(300)}"
            ZephyrLogger.warn(message)
            throw IllegalStateException(message)
        }
        return parsed.also {
            catalogCache = it
            candidateCacheStore.save(it)
        }
    }

    override suspend fun cachedCatalog(): com.worxbend.zephyr.data.CandidateMetadataCache? =
        candidateCacheStore.load()

    override suspend fun versions(candidate: String): List<CandidateVersion> {
        val report = versionReport(candidate)
        if (report.versions.isEmpty()) throw IllegalStateException("No versions could be read for $candidate.")
        return report.versions
    }

    override suspend fun mergedCandidate(candidate: String): Candidate? {
        validateCandidate(candidate)
        check(inspector().safeCandidate(candidate)) { "Unsafe SDKMAN candidate directory." }
        val localVersions = installedVersionsFor(candidate)
        val default = defaultVersionFor(candidate)
        val report = versionReport(candidate)
        val remote = report.versions.map { version ->
            if (report.isTrusted) {
                version
            } else {
                version.copy(
                    remoteAvailability = if (version.remoteAvailability == RemoteAvailability.Available) {
                        RemoteAvailability.Available
                    } else {
                        RemoteAvailability.Unknown
                    },
                )
            }
        }
        val remoteByVersion = remote.associateBy { it.version }
        val merged = (remote.map { version ->
            version.copy(
                isInstalled = version.isInstalled || version.version in localVersions,
                isDefault = version.version == default,
            )
        } + localVersions.filterNot { it in remoteByVersion }.map { version ->
            CandidateVersion(
                version = version,
                isInstalled = true,
                isDefault = version == default,
                remoteAvailability = RemoteAvailability.Unknown,
            )
        }).distinctBy { it.version }.sortedWith(versionComparator())

        val kind = candidateKindFor(candidate)
        val metadata = catalogCache?.firstOrNull { it.name == candidate }
        val localOnly = merged
            .filter { report.isTrusted && it.isInstalled && it.isConfirmedLocalOnly }
            .map { it.version }
        if (merged.isEmpty() && metadata == null) return null
        return Candidate(
            name = candidate,
            displayName = metadata?.displayName ?: displayNameFor(candidate),
            description = metadata?.description,
            websiteUrl = metadata?.websiteUrl,
            kind = kind,
            installedVersions = merged,
            defaultVersion = default,
            hasLocalOnlyVersions = localOnly.isNotEmpty(),
            localOnlyVersionCount = localOnly.size,
            localOnlyVersions = localOnly,
            remoteEvidence = if (report.isTrusted) RemoteEvidenceState.LiveComplete else RemoteEvidenceState.LivePartial,
        )
    }

    override suspend fun checkConnectivity(): ConnectivityStatus {
        return try {
            ConnectivityStatus.from(runner().diagnoseConnectivity(SDKMAN_READ_TIMEOUT))
        } catch (exception: CancellationException) {
            throw exception
        } catch (exception: Exception) {
            ZephyrLogger.warn("SDKMAN connectivity diagnostic failed.")
            ConnectivityStatus.from(
                com.worxbend.zephyr.domain.ConnectivityDiagnostic(
                    route = com.worxbend.zephyr.domain.ConnectivityRouteKind.Direct,
                    checkedAtEpochMillis = System.currentTimeMillis(),
                    latencyMillis = 0,
                    outcome = com.worxbend.zephyr.domain.ConnectivityOutcome.Indeterminate,
                ),
            )
        }
    }

    override suspend fun integrityChecks(): List<IntegrityCheck> {
        val sdkmanHome = home()
        val candidatesPath = sdkmanHome / "candidates"
        val missingScripts = REQUIRED_SDKMAN_SCRIPTS.filter { relative ->
            fileSystem.metadataOrNull(sdkmanHome / relative)?.let { metadata ->
                !metadata.isDirectory && metadata.symlinkTarget == null
            } != true
        }
        val safeCandidatesRoot = inspector().safeRoot()
        val candidateEntries = if (safeCandidatesRoot) fileSystem.listOrNull(candidatesPath).orEmpty() else emptyList()
        val invalidCandidates = candidateEntries.filter { path ->
            val metadata = fileSystem.metadataOrNull(path)
            !isValidCandidate(path.name) || metadata?.isDirectory != true || metadata.symlinkTarget != null
        }
        val validCandidates = candidateEntries - invalidCandidates.toSet()
        val invalidVersions = validCandidates.flatMap { candidatePath ->
            fileSystem.listOrNull(candidatePath).orEmpty()
                .filter { it.name != "current" }
                .filter { versionPath ->
                    val metadata = fileSystem.metadataOrNull(versionPath)
                    !isValidVersion(versionPath.name) || metadata?.isDirectory != true || metadata.symlinkTarget != null
                }
                .map { "${candidatePath.name}/${it.name}" }
        }
        val invalidDefaultLinks = validCandidates.mapNotNull { candidatePath ->
            val current = candidatePath / "current"
            fileSystem.metadataOrNull(current)?.let {
                candidatePath.name.takeIf { currentLinkTargetVersion(candidatePath, current) == null }
            }
        }

        return listOf(
            IntegrityCheck(
                id = IntegrityCheckId.RequiredScripts,
                title = "Required scripts",
                status = if (missingScripts.isEmpty()) IntegrityStatus.Passed else IntegrityStatus.Failed,
                detail = if (missingScripts.isEmpty()) {
                    "${REQUIRED_SDKMAN_SCRIPTS.size} required SDKMAN scripts are present."
                } else {
                    "Missing or unsafe: ${missingScripts.joinToString()}."
                },
            ),
            IntegrityCheck(
                id = IntegrityCheckId.CandidatesDirectory,
                title = "Candidates directory",
                status = if (safeCandidatesRoot) {
                    IntegrityStatus.Passed
                } else {
                    IntegrityStatus.Failed
                },
                detail = if (safeCandidatesRoot) {
                    "The SDKMAN candidates directory is available."
                } else {
                    "The SDKMAN candidates directory is missing or unreadable."
                },
            ),
            integrityEntryCheck(
                id = IntegrityCheckId.CandidateEntries,
                title = "Candidate entries",
                invalid = invalidCandidates.map(Path::name),
                successDetail = "${validCandidates.size} candidate director${if (validCandidates.size == 1) "y" else "ies"} passed validation.",
            ),
            integrityEntryCheck(
                id = IntegrityCheckId.VersionEntries,
                title = "Version entries",
                invalid = invalidVersions,
                successDetail = "Installed version directories use valid identifiers and do not escape through symlinks.",
            ),
            IntegrityCheck(
                id = IntegrityCheckId.DefaultLinks,
                title = "Default links",
                status = if (invalidDefaultLinks.isEmpty()) IntegrityStatus.Passed else IntegrityStatus.Failed,
                detail = if (invalidDefaultLinks.isEmpty()) {
                    "Every current link resolves to a local version directory."
                } else {
                    "Broken or escaping current links: ${invalidDefaultLinks.take(MAX_INTEGRITY_DETAIL_ITEMS).joinToString()}."
                },
            ),
        )
    }

    override suspend fun estimateDiskImpact(transaction: SdkmanTransaction): DiskImpactEstimate {
        val available = availableDiskBytes()
        return when (transaction) {
            is SdkmanTransaction.Install -> {
                validateCandidate(transaction.candidate)
                val siblingSizes = installedVersionsFor(transaction.candidate)
                    .mapNotNull { version -> directorySize(home() / "candidates" / transaction.candidate / version) }
                    .filter { it > 0 }
                    .sorted()
                val estimatedBytes = siblingSizes.takeIf { it.isNotEmpty() }?.let(::medianSize)
                DiskImpactEstimate(
                    kind = if (estimatedBytes == null) DiskImpactKind.Unknown else DiskImpactKind.Required,
                    bytes = estimatedBytes,
                    availableBytes = available,
                    confidence = if (estimatedBytes == null) EstimateConfidence.Unknown else EstimateConfidence.Estimated,
                    explanation = if (estimatedBytes == null) {
                        "No local sibling installation is available for a size estimate."
                    } else {
                        "Estimated from the median size of ${siblingSizes.size} installed ${displayNameFor(transaction.candidate)} version(s)."
                    },
                )
            }
            is SdkmanTransaction.BatchInstall -> {
                val estimates = transaction.targets.map { target ->
                    estimateDiskImpact(SdkmanTransaction.Install(target.candidate, target.version))
                }
                val total = exactStorageSum(estimates.map { it.bytes })
                DiskImpactEstimate(
                    kind = if (total != null) DiskImpactKind.Required else DiskImpactKind.Unknown,
                    bytes = total,
                    availableBytes = available,
                    confidence = if (total != null) {
                        EstimateConfidence.Estimated
                    } else {
                        EstimateConfidence.Unknown
                    },
                    explanation = if (total != null) {
                        "Combined estimate for ${transaction.targets.size} sequential installs."
                    } else {
                        "One or more selected candidates have no local sibling size evidence."
                    },
                )
            }
            is SdkmanTransaction.SnapshotRestore,
            is SdkmanTransaction.ToolchainActivation,
            is SdkmanTransaction.UpdateActivation,
            -> {
                val installCommands = transaction.commands.filter { it.action == SdkmanCommandAction.Install }
                val estimates = installCommands.map { command ->
                    estimateDiskImpact(
                        SdkmanTransaction.Install(
                            requireNotNull(command.candidate),
                            requireNotNull(command.version),
                        ),
                    )
                }
                val total = exactStorageSum(estimates.map { it.bytes })
                DiskImpactEstimate(
                    kind = when {
                        estimates.isEmpty() -> DiskImpactKind.None
                        total != null -> DiskImpactKind.Required
                        else -> DiskImpactKind.Unknown
                    },
                    bytes = when {
                        estimates.isEmpty() -> 0
                        total != null -> total
                        else -> null
                    },
                    availableBytes = available,
                    confidence = when {
                        estimates.isEmpty() -> EstimateConfidence.Exact
                        total != null -> EstimateConfidence.Estimated
                        else -> EstimateConfidence.Unknown
                    },
                    explanation = when {
                        estimates.isEmpty() -> "Changing persisted defaults does not add or remove versions."
                        total != null -> "Combined estimate for ${installCommands.size} planned install(s)."
                        else -> "One or more planned installs have no local sibling size evidence."
                    },
                )
            }
            is SdkmanTransaction.Uninstall -> reclaimableEstimate(
                versions = listOf(transaction.version),
                candidate = transaction.candidate,
                availableBytes = available,
            )
            is SdkmanTransaction.BatchUninstall -> {
                val estimates = transaction.targets.map { target ->
                    reclaimableEstimate(
                        versions = listOf(target.version),
                        candidate = target.candidate,
                        availableBytes = available,
                    )
                }
                val total = exactStorageSum(estimates.map { it.bytes })
                DiskImpactEstimate(
                    kind = if (total == null) DiskImpactKind.Unknown else DiskImpactKind.Reclaimable,
                    bytes = total,
                    availableBytes = available,
                    confidence = if (total == null) EstimateConfidence.Unknown else EstimateConfidence.Exact,
                    explanation = if (total == null) {
                        "One or more selected version sizes are unknown or exceed the supported total."
                    } else {
                        "Exact combined size of ${transaction.targets.size} selected local version directories."
                    },
                )
            }
            is SdkmanTransaction.CleanLocalOnly -> reclaimableEstimate(
                versions = transaction.versions,
                candidate = transaction.candidate,
                availableBytes = available,
            )
            is SdkmanTransaction.SetDefault,
            SdkmanTransaction.RefreshMetadata,
            -> DiskImpactEstimate(
                kind = DiskImpactKind.None,
                bytes = 0,
                availableBytes = available,
                confidence = EstimateConfidence.Exact,
                explanation = "This operation does not add or remove an installed candidate version.",
            )
            SdkmanTransaction.SelfUpdate -> DiskImpactEstimate(
                kind = DiskImpactKind.Unknown,
                availableBytes = available,
                confidence = EstimateConfidence.Unknown,
                explanation = "SDKMAN does not publish the size of its own update before execution.",
            )
        }
    }

    override suspend fun storageInventory(candidates: List<Candidate>): StorageInventory {
        val protected = protectedVersionStore.load()
        val entries = candidates
            .flatMap { candidate ->
                validateCandidate(candidate.name)
                candidate.installedVersions
                    .asSequence()
                    .filter(CandidateVersion::isInstalled)
                    .map { version ->
                        require(isValidVersion(version.version)) { "Invalid SDKMAN version identifier." }
                        VersionStorage(
                            candidate = candidate.name,
                            candidateDisplayName = candidate.displayName,
                            version = version.version,
                            measurement = if (versionPathState(versionPath(candidate.name, version.version)) == PathPostcondition.Satisfied) {
                                measureStorageDirectory(fileSystem, versionPath(candidate.name, version.version))
                            } else {
                                StorageMeasurement.Unknown(StorageUnknownReason.Unreadable)
                            },
                            isDefault = candidate.defaultVersion == version.version,
                            isProtected = ProtectedVersion(candidate.name, version.version) in protected,
                            remoteAvailability = version.remoteAvailability,
                        )
                    }
                    .toList()
            }
            .sortedWith(com.worxbend.zephyr.domain.storageSizeComparator)
        return StorageInventory(
            versions = entries,
            scannedAtEpochMillis = System.currentTimeMillis(),
            availableBytes = availableDiskBytes(),
        )
    }

    override suspend fun protectedVersions(): Set<ProtectedVersion> = protectedVersionStore.load()

    override suspend fun setVersionProtected(
        candidate: String,
        version: String,
        protected: Boolean,
    ): CommandOutcome {
        invalidCommandInput(candidate, version)?.let { return it }
        val target = ProtectedVersion(candidate, version)
        val current = protectedVersionStore.load()
        val updated = if (protected) current + target else current - target
        return try {
            protectedVersionStore.save(updated)
            CommandOutcome(
                success = true,
                message = if (protected) "Protected $version from cleanup." else "Removed cleanup protection from $version.",
            )
        } catch (exception: Exception) {
            ZephyrLogger.warn("Unable to persist protected SDKMAN versions.", exception)
            CommandOutcome(false, "Unable to save protected versions: ${exception.message}")
        }
    }

    override suspend fun refreshCandidateMetadata(): CommandOutcome {
        catalogCache = null
        return runner().run(SdkmanCommand.UpdateCandidateMetadata, 30.seconds).toOutcome("Candidate metadata refreshed.")
    }

    override suspend fun selfUpdate(): SdkmanSelfUpdateStatus {
        val result = runner().run(SdkmanCommand.SelfUpdate, 2.minutes)
        val output = result.output.lowercase()
        return when {
            !result.success -> {
                val message = result.output.ifBlank { "SDKMAN self-update failed." }
                ZephyrLogger.warn(message)
                SdkmanSelfUpdateStatus.Failed(message)
            }
            "no update" in output || "up-to-date" in output || "up to date" in output -> SdkmanSelfUpdateStatus.UpToDate
            else -> SdkmanSelfUpdateStatus.Updated
        }
    }

    override suspend fun install(candidate: String, version: String): CommandOutcome {
        invalidCommandInput(candidate, version)?.let { return it }
        catalogCache = null
        val target = versionPath(candidate, version)
        when (versionPathState(target)) {
            PathPostcondition.Satisfied -> return alreadySatisfied("$version is already installed.")
            PathPostcondition.Indeterminate -> return indeterminate("The existing $version installation could not be verified safely.")
            PathPostcondition.Unsatisfied -> Unit
        }
        val result = runner().run(SdkmanCommand.Install(candidate, version), 10.minutes)
        return result.verifiedOutcome(
            postcondition = versionPathState(target),
            successMessage = "Installed $version.",
            missingMessage = "SDKMAN finished, but $version is not installed.",
        )
    }

    override suspend fun uninstall(candidate: String, version: String): CommandOutcome {
        invalidCommandInput(candidate, version)?.let { return it }
        if (defaultVersionFor(candidate) == version) {
            return CommandOutcome(false, "Choose another default before uninstalling $version.")
        }
        if (ProtectedVersion(candidate, version) in protectedVersionStore.load()) {
            return CommandOutcome(false, "Unpin $version before uninstalling it.")
        }
        catalogCache = null
        val target = versionPath(candidate, version)
        when (versionPathState(target)) {
            PathPostcondition.Unsatisfied -> return alreadySatisfied("$version is already absent.")
            PathPostcondition.Indeterminate -> return indeterminate("The $version installation could not be verified safely.")
            PathPostcondition.Satisfied -> Unit
        }
        val result = runner().run(SdkmanCommand.Uninstall(candidate, version), 2.minutes)
        return result.verifiedOutcome(
            postcondition = versionPathState(target).inverted(),
            successMessage = "Uninstalled $version.",
            missingMessage = "SDKMAN finished, but $version is still installed.",
        )
    }

    override suspend fun setDefault(candidate: String, version: String): CommandOutcome {
        invalidCommandInput(candidate, version)?.let { return it }
        when (versionPathState(versionPath(candidate, version))) {
            PathPostcondition.Unsatisfied -> return CommandOutcome(false, "Install $version before making it the default.")
            PathPostcondition.Indeterminate -> return indeterminate("The $version installation could not be verified safely.")
            PathPostcondition.Satisfied -> Unit
        }
        if (defaultVersionFor(candidate) == version) {
            return alreadySatisfied("$version is already the default.")
        }
        val result = runner().run(SdkmanCommand.SetDefault(candidate, version), 30.seconds)
        return result.verifiedOutcome(
            postcondition = defaultPostcondition(candidate, version),
            successMessage = "Default set to $version.",
            missingMessage = "SDKMAN finished, but the default is not $version.",
        )
    }

    override suspend fun cleanLocalOnly(candidate: String, versions: List<String>): CommandOutcome {
        if (!isValidCandidate(candidate)) return invalidCandidateOutcome()
        if (versions.isEmpty()) return CommandOutcome(false, "Select at least one version to clean.")
        if (versions.any { !isValidVersion(it) }) return invalidVersionOutcome()
        val default = defaultVersionFor(candidate)
        val requestedVersions = versions.distinct()
        val eligible = requestedVersions.filterNot { it == default }
        if (eligible.size != requestedVersions.size) {
            return CommandOutcome(false, "Choose another default version before cleaning this one.")
        }
        val protected = protectedVersionStore.load()
        if (eligible.any { ProtectedVersion(candidate, it) in protected }) {
            return CommandOutcome(false, "Unpin protected versions before cleaning them.")
        }
        val evidence = try {
            versionReport(candidate)
        } catch (exception: IllegalStateException) {
            ZephyrLogger.warn("Unable to verify local-only versions before cleanup for $candidate.", exception)
            return CommandOutcome(false, "Unable to verify local-only versions. Try scanning again.")
        }
        if (!evidence.isTrusted) {
            ZephyrLogger.warn("Cleanup blocked because SDKMAN version evidence was incomplete for $candidate: ${evidence.issues.joinToString()}")
            return CommandOutcome(false, "Cleanup is blocked because live SDKMAN version evidence is incomplete.")
        }
        val installed = installedVersionsFor(candidate).toSet()
        val localOnlyVersions = evidence.versions
            .filter { it.isConfirmedLocalOnly && it.version in installed }
            .map { it.version }
            .toSet()
        if (eligible.any { it !in localOnlyVersions }) {
            return CommandOutcome(false, "Only versions confirmed as local-only can be cleaned.")
        }
        val outcomes = mutableListOf<CommandOutcome>()
        eligible.forEach { version ->
            val target = versionPath(candidate, version)
            when (versionPathState(target)) {
                PathPostcondition.Unsatisfied -> {
                    outcomes += alreadySatisfied("$version is already absent.")
                    return@forEach
                }
                PathPostcondition.Indeterminate -> {
                    outcomes += indeterminate("The $version installation could not be verified safely.")
                    return@forEach
                }
                PathPostcondition.Satisfied -> Unit
            }
            val result = runner().run(SdkmanCommand.Uninstall(candidate, version), 2.minutes)
            outcomes += result.verifiedOutcome(
                postcondition = versionPathState(target).inverted(),
                successMessage = "Cleaned $version.",
                missingMessage = "$version is still installed after cleanup.",
            )
        }
        catalogCache = null
        val unsuccessful = outcomes.filterNot { it.success }
        return when {
            unsuccessful.isNotEmpty() -> CommandOutcome(
                success = false,
                message = "Cleanup could not verify ${unsuccessful.size} of ${eligible.size} version(s).",
                status = if (unsuccessful.any { it.status == CommandOutcomeStatus.Indeterminate }) {
                    CommandOutcomeStatus.Indeterminate
                } else {
                    CommandOutcomeStatus.Failed
                },
            )
            outcomes.all { it.status == CommandOutcomeStatus.AlreadySatisfied } ->
                alreadySatisfied("Selected local-only versions are already absent.")
            outcomes.any { it.status == CommandOutcomeStatus.AppliedWithWarning } -> CommandOutcome(
                success = true,
                message = "Cleaned ${eligible.size} local-only version(s), with SDKMAN warnings.",
                status = CommandOutcomeStatus.AppliedWithWarning,
            )
            else -> CommandOutcome(true, "Cleaned ${eligible.size} local-only version(s).")
        }
    }

    override suspend fun commandSatisfaction(command: PlannedSdkmanCommand): CommandSatisfaction {
        val candidate = command.candidate
        val version = command.version
        if (candidate != null && version != null) {
            invalidCommandInput(candidate, version)?.let { return CommandSatisfaction.Indeterminate }
        }
        val condition = when (command.action) {
            SdkmanCommandAction.Install -> versionPathState(
                versionPath(requireNotNull(candidate), requireNotNull(version)),
            )
            SdkmanCommandAction.Uninstall -> versionPathState(
                versionPath(requireNotNull(candidate), requireNotNull(version)),
            ).inverted()
            SdkmanCommandAction.SetDefault -> defaultPostcondition(
                requireNotNull(candidate),
                requireNotNull(version),
            )
            SdkmanCommandAction.UpdateMetadata,
            SdkmanCommandAction.SelfUpdate,
            -> PathPostcondition.Indeterminate
        }
        return when (condition) {
            PathPostcondition.Satisfied -> CommandSatisfaction.Satisfied
            PathPostcondition.Unsatisfied -> CommandSatisfaction.Unsatisfied
            PathPostcondition.Indeterminate -> CommandSatisfaction.Indeterminate
        }
    }

    private suspend fun versionReport(candidate: String): VersionParseReport {
        validateCandidate(candidate)
        check(inspector().safeCandidate(candidate)) { "Unsafe SDKMAN candidate directory." }
        val result = runner().run(SdkmanCommand.ListVersions(candidate), 20.seconds)
        if (!result.success) {
            val message = "Unable to list versions for $candidate (SDKMAN exit ${result.exitCode})."
            ZephyrLogger.warn(message)
            throw IllegalStateException(message)
        }
        return SdkmanListParser.parseVersionsReport(
            output = result.stdout,
            outputTruncated = COMMAND_OUTPUT_TRUNCATED_MARKER in result.stderr,
        )
    }

    private fun versionPath(candidate: String, version: String): Path =
        home() / "candidates" / candidate / version

    private fun inspector(): SdkmanFilesystemInspector = SdkmanFilesystemInspector(fileSystem, home())

    private fun versionPathState(path: Path): PathPostcondition = inspector().versionState(path)

    private fun defaultPostcondition(candidate: String, version: String): PathPostcondition {
        if (!inspector().safeCandidate(candidate)) return PathPostcondition.Indeterminate
        val candidatePath = home() / "candidates" / candidate
        val current = candidatePath / "current"
        val metadata = fileSystem.metadataOrNull(current) ?: return PathPostcondition.Unsatisfied
        if (metadata.symlinkTarget == null) return PathPostcondition.Indeterminate
        return if (currentLinkTargetVersion(candidatePath, current) == version) {
            PathPostcondition.Satisfied
        } else {
            PathPostcondition.Unsatisfied
        }
    }

    private fun installedVersionsFor(candidate: String): List<String> {
        validateCandidate(candidate)
        if (!inspector().safeCandidate(candidate)) return emptyList()
        val candidatePath = home() / "candidates" / candidate
        return fileSystem.listOrNull(candidatePath).orEmpty()
            .mapNotNull { versionPath ->
                val metadata = fileSystem.metadataOrNull(versionPath) ?: return@mapNotNull null
                versionPath.name.takeIf {
                    it != "current" && metadata.isDirectory && metadata.symlinkTarget == null && isValidVersion(it)
                }
            }
            .sorted()
    }

    private fun reclaimableEstimate(
        versions: List<String>,
        candidate: String,
        availableBytes: Long?,
    ): DiskImpactEstimate {
        validateCandidate(candidate)
        require(versions.all(::isValidVersion)) { "Invalid SDKMAN version identifier." }
        val sizes = versions.map { version ->
            directorySize(home() / "candidates" / candidate / version)
        }
        val total = exactStorageSum(sizes)
        val exact = total != null
        return DiskImpactEstimate(
            kind = if (exact) DiskImpactKind.Reclaimable else DiskImpactKind.Unknown,
            bytes = total.takeIf { exact },
            availableBytes = availableBytes,
            confidence = if (exact) EstimateConfidence.Exact else EstimateConfidence.Unknown,
            explanation = if (exact) {
                "Calculated from ${versions.size} installed version director${if (versions.size == 1) "y" else "ies"}."
            } else {
                "One or more installed version directories could not be measured."
            },
        )
    }

    private fun directorySize(root: Path): Long? {
        if (versionPathState(root) != PathPostcondition.Satisfied) return null
        return (measureStorageDirectory(fileSystem, root) as? StorageMeasurement.Exact)?.bytes
    }

    private fun availableDiskBytes(): Long? =
        runCatching {
            java.nio.file.Files.getFileStore(java.nio.file.Path.of(home().toString())).usableSpace
        }.getOrNull()

    private fun defaultVersionFor(candidate: String): String? {
        validateCandidate(candidate)
        if (!inspector().safeCandidate(candidate)) return null
        val candidatePath = home() / "candidates" / candidate
        return currentLinkTargetVersion(candidatePath, candidatePath / "current")
    }

    private fun currentLinkTargetVersion(candidatePath: Path, currentPath: Path): String? {
        if (!inspector().safeCandidate(candidatePath.name)) return null
        val symlinkTarget = fileSystem.metadataOrNull(currentPath)?.symlinkTarget ?: return null
        val candidateNio = java.nio.file.Path.of(candidatePath.toString()).toAbsolutePath().normalize()
        val rawTarget = java.nio.file.Path.of(symlinkTarget.toString())
        val resolvedTarget = if (rawTarget.isAbsolute) {
            rawTarget.normalize()
        } else {
            java.nio.file.Path.of(currentPath.parent!!.toString()).resolve(rawTarget).normalize()
        }
        val canonicalTarget = runCatching { fileSystem.canonicalize(resolvedTarget.toString().toPath()) }.getOrNull()
            ?: return null
        val targetNio = java.nio.file.Path.of(canonicalTarget.toString()).toAbsolutePath().normalize()
        if (targetNio.parent != candidateNio) return null
        val version = targetNio.fileName?.toString()?.takeIf(::isValidVersion) ?: return null
        val targetMetadata = fileSystem.metadataOrNull(candidatePath / version) ?: return null
        return version.takeIf { targetMetadata.isDirectory && targetMetadata.symlinkTarget == null }
    }

    private fun locateHome(): Path {
        val configured = sdkmanHomeResolver()
        return runCatching { fileSystem.canonicalize(configured) }.getOrDefault(configured)
    }

    private fun home(): Path = sdkmanHome ?: locateHome().also { sdkmanHome = it }

    private fun runner(): SdkmanCommandRunner =
        commandRunner ?: commandRunnerFactory(home()).also { commandRunner = it }

    private fun invalidCommandInput(candidate: String, version: String): CommandOutcome? =
        when {
            !isValidCandidate(candidate) -> invalidCandidateOutcome()
            !isValidVersion(version) -> invalidVersionOutcome()
            else -> null
        }

    private fun validateCandidate(candidate: String) {
        require(isValidCandidate(candidate)) { "Invalid SDKMAN candidate name." }
    }

    private fun isValidCandidate(candidate: String): Boolean = isValidSdkmanCandidateName(candidate)

    private fun isValidVersion(version: String): Boolean = isValidSdkmanVersion(version)

    private fun invalidCandidateOutcome(): CommandOutcome =
        CommandOutcome(false, "Invalid SDKMAN candidate name.")

    private fun invalidVersionOutcome(): CommandOutcome =
        CommandOutcome(false, "Invalid SDKMAN version identifier.")

    private fun SdkmanCommandResult.toOutcome(successMessage: String): CommandOutcome =
        if (success) {
            CommandOutcome(true, stdout.lineSequence().lastOrNull { it.isNotBlank() }?.trim() ?: successMessage)
        } else {
            CommandOutcome(false, output.ifBlank { "SDKMAN command failed." })
        }

    private fun SdkmanCommandResult.verifiedOutcome(
        postcondition: PathPostcondition,
        successMessage: String,
        missingMessage: String,
    ): CommandOutcome =
        when (postcondition) {
            PathPostcondition.Satisfied -> if (success) {
                CommandOutcome(true, successMessage, CommandOutcomeStatus.Applied)
            } else {
                CommandOutcome(
                    success = true,
                    message = "$successMessage SDKMAN exited with code $exitCode.",
                    status = CommandOutcomeStatus.AppliedWithWarning,
                )
            }
            PathPostcondition.Unsatisfied -> CommandOutcome(
                success = false,
                message = if (success) missingMessage else "SDKMAN exited with code $exitCode and the requested change was not applied.",
                status = CommandOutcomeStatus.Failed,
            )
            PathPostcondition.Indeterminate -> indeterminate(
                "SDKMAN finished, but the resulting filesystem state could not be verified safely.",
            )
        }

    private fun alreadySatisfied(message: String): CommandOutcome =
        CommandOutcome(true, message, CommandOutcomeStatus.AlreadySatisfied)

    private fun indeterminate(message: String): CommandOutcome =
        CommandOutcome(false, message, CommandOutcomeStatus.Indeterminate)
}

internal fun measureStorageDirectory(
    fileSystem: FileSystem,
    root: Path,
    maxEntries: Int = MAX_DISK_ESTIMATE_ENTRIES,
): StorageMeasurement {
    require(maxEntries > 0) { "Storage scan entry limit must be positive." }
    val rootMetadata = runCatching { fileSystem.metadataOrNull(root) }.getOrNull()
        ?: return StorageMeasurement.Unknown(StorageUnknownReason.Missing)
    if (rootMetadata.symlinkTarget != null) {
        return StorageMeasurement.Unknown(StorageUnknownReason.SymbolicLink)
    }
    if (!rootMetadata.isDirectory) {
        return StorageMeasurement.Unknown(StorageUnknownReason.NotDirectory)
    }

    val pending = ArrayDeque<Path>()
    val observed = mutableListOf(root to rootMetadata.storageFingerprint())
    pending.add(root)
    var total = 0L
    var visited = 0
    while (pending.isNotEmpty()) {
        val directory = pending.removeLast()
        val children = runCatching { fileSystem.list(directory) }
            .getOrElse { return StorageMeasurement.Unknown(StorageUnknownReason.Unreadable) }
        for (child in children) {
            visited += 1
            if (visited > maxEntries) {
                return StorageMeasurement.Unknown(StorageUnknownReason.EntryLimit)
            }
            val metadata = runCatching { fileSystem.metadataOrNull(child) }.getOrNull()
                ?: return StorageMeasurement.Unknown(StorageUnknownReason.ChangedDuringScan)
            observed += child to metadata.storageFingerprint()
            when {
                metadata.symlinkTarget != null ->
                    return StorageMeasurement.Unknown(StorageUnknownReason.SymbolicLink)
                metadata.isDirectory -> pending.add(child)
                metadata.isRegularFile -> {
                    val size = metadata.size
                        ?: return StorageMeasurement.Unknown(StorageUnknownReason.Unreadable)
                    if (size < 0) return StorageMeasurement.Unknown(StorageUnknownReason.Unreadable)
                    if (Long.MAX_VALUE - total < size) {
                        return StorageMeasurement.Unknown(StorageUnknownReason.Overflow)
                    }
                    total += size
                }
                else -> return StorageMeasurement.Unknown(StorageUnknownReason.UnsupportedEntry)
            }
        }
    }
    val changed = observed.any { (path, fingerprint) ->
        runCatching { fileSystem.metadataOrNull(path) }.getOrNull()?.storageFingerprint() != fingerprint
    }
    return if (changed) {
        StorageMeasurement.Unknown(StorageUnknownReason.ChangedDuringScan)
    } else {
        StorageMeasurement.Exact(total)
    }
}

private data class StorageFingerprint(
    val isRegularFile: Boolean,
    val isDirectory: Boolean,
    val symlinkTarget: Path?,
    val size: Long?,
    val createdAtMillis: Long?,
    val lastModifiedAtMillis: Long?,
)

private fun okio.FileMetadata.storageFingerprint(): StorageFingerprint =
    StorageFingerprint(
        isRegularFile = isRegularFile,
        isDirectory = isDirectory,
        symlinkTarget = symlinkTarget,
        size = size,
        createdAtMillis = createdAtMillis,
        lastModifiedAtMillis = lastModifiedAtMillis,
    )

private fun PathPostcondition.inverted(): PathPostcondition =
    when (this) {
        PathPostcondition.Satisfied -> PathPostcondition.Unsatisfied
        PathPostcondition.Unsatisfied -> PathPostcondition.Satisfied
        PathPostcondition.Indeterminate -> PathPostcondition.Indeterminate
    }

internal fun exactStorageSum(sizes: List<Long?>): Long? {
    var total = 0L
    for (component in sizes) {
        val size = component ?: return null
        if (size < 0 || Long.MAX_VALUE - total < size) return null
        total += size
    }
    return total
}

private fun medianSize(sortedSizes: List<Long>): Long {
    val middle = sortedSizes.size / 2
    return if (sortedSizes.size % 2 == 1) {
        sortedSizes[middle]
    } else {
        val lower = sortedSizes[middle - 1]
        val upper = sortedSizes[middle]
        lower + (upper - lower) / 2
    }
}

private fun integrityEntryCheck(
    id: IntegrityCheckId,
    title: String,
    invalid: List<String>,
    successDetail: String,
): IntegrityCheck =
    IntegrityCheck(
        id = id,
        title = title,
        status = if (invalid.isEmpty()) IntegrityStatus.Passed else IntegrityStatus.Warning,
        detail = if (invalid.isEmpty()) {
            successDetail
        } else {
            "Ignored ${invalid.size} malformed or symlinked entr${if (invalid.size == 1) "y" else "ies"}: " +
                "${invalid.take(MAX_INTEGRITY_DETAIL_ITEMS).joinToString()}."
        },
    )

private const val MAX_DISK_ESTIMATE_ENTRIES = 1_000_000
private const val COMMAND_OUTPUT_TRUNCATED_MARKER = "Command output was truncated."
private const val MAX_INTEGRITY_DETAIL_ITEMS = 5
private val SDKMAN_READ_TIMEOUT = 8.seconds
private val REQUIRED_SDKMAN_SCRIPTS = listOf(
    "bin/sdkman-init.sh",
    "src/sdkman-main.sh",
    "src/sdkman-list.sh",
    "src/sdkman-install.sh",
    "src/sdkman-uninstall.sh",
    "src/sdkman-default.sh",
    "src/sdkman-update.sh",
    "src/sdkman-selfupdate.sh",
)

private fun defaultSdkmanHome(): Path {
    val configured = System.getenv("SDKMAN_DIR")?.takeIf { it.isNotBlank() }
    return (configured ?: "${System.getProperty("user.home")}/.sdkman").toPath()
}
