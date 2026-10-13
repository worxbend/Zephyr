package com.worxbend.zephyr.domain

enum class DesiredStateSourceKind(
    val label: String,
) {
    Profile("Profile"),
    Snapshot("Snapshot"),
}

data class DesiredCandidateState(
    val candidate: String,
    val defaultVersion: String?,
    val installedVersions: List<String>,
) {
    init {
        require(isValidSdkmanCandidateName(candidate)) { "Invalid desired-state candidate." }
        require(defaultVersion == null || isValidSdkmanVersion(defaultVersion)) {
            "Invalid desired-state default version."
        }
        require(installedVersions.isNotEmpty()) { "A desired candidate requires at least one installed version." }
        require(installedVersions.all(::isValidSdkmanVersion)) { "Invalid desired-state version." }
        require(installedVersions.distinct().size == installedVersions.size) {
            "Desired-state versions must be unique."
        }
        require(defaultVersion == null || defaultVersion in installedVersions) {
            "The desired default must also be a desired installed version."
        }
    }
}

data class DesiredToolchainState(
    val schemaVersion: Int = CURRENT_DESIRED_STATE_SCHEMA,
    val sourceKind: DesiredStateSourceKind,
    val sourceLabel: String,
    val candidates: List<DesiredCandidateState>,
) {
    init {
        require(schemaVersion == CURRENT_DESIRED_STATE_SCHEMA) { "Unsupported desired-state schema." }
        require(sourceLabel.isNotBlank()) { "A desired-state source label is required." }
        require(sourceLabel.none { it.code < 32 || it.code == 127 }) {
            "Desired-state source labels cannot contain control characters."
        }
        require(candidates.isNotEmpty()) { "A desired state requires at least one candidate." }
        require(candidates.distinctBy(DesiredCandidateState::candidate).size == candidates.size) {
            "Desired-state candidates must be unique."
        }
    }
}

data class DesiredStateDrift(
    val missingVersions: List<InstallTarget>,
    val defaultChanges: List<InstallTarget>,
    val extraInstalledVersions: List<InstallTarget>,
    val localOnlyDesiredVersions: List<InstallTarget>,
) {
    val isAligned: Boolean
        get() =
            missingVersions.isEmpty() &&
                defaultChanges.isEmpty() &&
                extraInstalledVersions.isEmpty()

    val remediationCommands: List<PlannedSdkmanCommand>
        get() =
            missingVersions.map {
                PlannedSdkmanCommand(SdkmanCommandAction.Install, it.candidate, it.version)
            } +
                defaultChanges.map {
                    PlannedSdkmanCommand(SdkmanCommandAction.SetDefault, it.candidate, it.version)
                }
}

fun calculateDesiredStateDrift(
    desired: DesiredToolchainState,
    currentCandidates: List<Candidate>,
): DesiredStateDrift {
    val currentByName = currentCandidates.associateBy(Candidate::name)
    val desiredByName = desired.candidates.associateBy(DesiredCandidateState::candidate)
    val missing =
        desired.candidates
            .sortedBy(DesiredCandidateState::candidate)
            .flatMap { target ->
                val installed =
                    currentByName[target.candidate]
                        ?.installedVersions
                        .orEmpty()
                        .filter(CandidateVersion::isInstalled)
                        .map(CandidateVersion::version)
                        .toSet()
                target.installedVersions
                    .filterNot { it in installed }
                    .sorted()
                    .map { InstallTarget(target.candidate, it) }
            }
    val defaults =
        desired.candidates
            .sortedBy(DesiredCandidateState::candidate)
            .mapNotNull { target ->
                target.defaultVersion
                    ?.takeIf { it != currentByName[target.candidate]?.defaultVersion }
                    ?.let { InstallTarget(target.candidate, it) }
            }
    val extras =
        currentCandidates
            .sortedBy(Candidate::name)
            .flatMap { current ->
                val desiredVersions = desiredByName[current.name]?.installedVersions.orEmpty().toSet()
                current.installedVersions
                    .filter { it.isInstalled && it.version !in desiredVersions }
                    .map { InstallTarget(current.name, it.version) }
                    .sortedBy(InstallTarget::version)
            }
    val localOnlyDesired =
        desired.candidates
            .sortedBy(DesiredCandidateState::candidate)
            .flatMap { target ->
                val versions =
                    currentByName[target.candidate]
                        ?.installedVersions
                        .orEmpty()
                        .associateBy(CandidateVersion::version)
                target.installedVersions
                    .filter { versions[it]?.remoteAvailability == RemoteAvailability.LocalOnly }
                    .sorted()
                    .map { InstallTarget(target.candidate, it) }
            }
    return DesiredStateDrift(
        missingVersions = missing,
        defaultChanges = defaults,
        extraInstalledVersions = extras,
        localOnlyDesiredVersions = localOnlyDesired,
    )
}

const val CURRENT_DESIRED_STATE_SCHEMA = 1
