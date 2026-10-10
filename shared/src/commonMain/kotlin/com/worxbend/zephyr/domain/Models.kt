package com.worxbend.zephyr.domain

data class SdkmanStatus(
    val isInstalled: Boolean,
    val home: String?,
    val cliVersion: String? = null,
    val selfUpdateStatus: SdkmanSelfUpdateStatus = SdkmanSelfUpdateStatus.NotChecked,
    val metadataStatus: CandidateMetadataStatus = CandidateMetadataStatus.NotChecked,
    val reason: String? = null,
)

sealed interface SdkmanSelfUpdateStatus {
    data object NotChecked : SdkmanSelfUpdateStatus
    data object UpToDate : SdkmanSelfUpdateStatus
    data object Updated : SdkmanSelfUpdateStatus
    data class Failed(val message: String) : SdkmanSelfUpdateStatus
}

sealed interface CandidateMetadataStatus {
    data object NotChecked : CandidateMetadataStatus
    data object Refreshing : CandidateMetadataStatus
    data object Refreshed : CandidateMetadataStatus
    data class Failed(val message: String) : CandidateMetadataStatus
}

data class Candidate(
    val name: String,
    val displayName: String,
    val description: String? = null,
    val websiteUrl: String? = null,
    val kind: CandidateKind,
    val installedVersions: List<CandidateVersion>,
    /** The SDKMAN version persisted through the candidate's `current` symlink. */
    val defaultVersion: String?,
    val hasLocalOnlyVersions: Boolean,
    val localOnlyVersionCount: Int,
    val localOnlyVersions: List<String>,
    val remoteEvidence: RemoteEvidenceState = RemoteEvidenceState.Unknown,
)

enum class CandidateKind {
    Sdk,
    Jdk,
}

data class CandidateVersion(
    val version: String,
    val isInstalled: Boolean,
    val isDefault: Boolean,
    val remoteAvailability: RemoteAvailability,
) {
    constructor(
        version: String,
        isInstalled: Boolean,
        isDefault: Boolean,
        isRemoteAvailable: Boolean,
    ) : this(
        version = version,
        isInstalled = isInstalled,
        isDefault = isDefault,
        remoteAvailability = if (isRemoteAvailable) RemoteAvailability.Available else RemoteAvailability.LocalOnly,
    )

    val isRemoteAvailable: Boolean
        get() = remoteAvailability == RemoteAvailability.Available

    val isConfirmedLocalOnly: Boolean
        get() = remoteAvailability == RemoteAvailability.LocalOnly
}

enum class RemoteAvailability(val label: String) {
    Available("Available"),
    LocalOnly("Local only"),
    Unknown("Availability unknown"),
}

enum class RemoteEvidenceState(val label: String) {
    Unknown("Remote evidence unavailable"),
    LivePartial("Remote evidence incomplete"),
    LiveComplete("Remote evidence verified"),
}

data class CandidateCatalogItem(
    val name: String,
    val displayName: String,
    val stableVersion: String?,
    val description: String?,
    val websiteUrl: String?,
    val kind: CandidateKind,
    val isInstalled: Boolean,
)

fun List<CandidateCatalogItem>.withInstalledCandidates(candidates: List<Candidate>): List<CandidateCatalogItem> {
    val installedNames = candidates.asSequence().map { it.name }.toSet()
    return map { item -> item.copy(isInstalled = item.name in installedNames) }
}

data class JavaVersion(
    val identifier: String,
    val featureVersion: String,
    val providerCode: String?,
    val providerName: String?,
    val isInstalled: Boolean,
    val isDefault: Boolean,
    val remoteAvailability: RemoteAvailability,
) {
    val isRemoteAvailable: Boolean
        get() = remoteAvailability == RemoteAvailability.Available

    val isConfirmedLocalOnly: Boolean
        get() = remoteAvailability == RemoteAvailability.LocalOnly
}

data class JavaVersionGroup(
    val title: String,
    val versions: List<JavaVersion>,
)

data class CommandOutcome(
    val success: Boolean,
    val message: String,
    val status: CommandOutcomeStatus = if (success) CommandOutcomeStatus.Applied else CommandOutcomeStatus.Failed,
) {
    init {
        val verifiedSuccess = status == CommandOutcomeStatus.Applied ||
            status == CommandOutcomeStatus.AppliedWithWarning || status == CommandOutcomeStatus.AlreadySatisfied
        require(success == verifiedSuccess) { "Command success must match its verification status." }
    }
}

enum class CommandOutcomeStatus(val label: String) {
    Applied("Applied"),
    AppliedWithWarning("Applied with warning"),
    AlreadySatisfied("Already satisfied"),
    Failed("Failed"),
    Indeterminate("Could not verify"),
}

fun candidateKindFor(name: String): CandidateKind =
    if (name == "java") CandidateKind.Jdk else CandidateKind.Sdk

fun displayNameFor(name: String): String =
    if (name == "java") "JDK" else name.split('-', '_')
        .joinToString(" ") { part -> part.replaceFirstChar { it.titlecase() } }

fun javaFeatureVersion(identifier: String?): String? =
    identifier?.substringBefore('.')?.substringBefore('-')?.takeIf { it.isNotBlank() }

fun javaProviderCode(identifier: String?): String? =
    identifier?.substringAfterLast('-', missingDelimiterValue = "")?.takeIf { it.isNotBlank() }

fun javaProviderName(code: String?): String? =
    code?.let { jdkVendorKnowledge(it)?.displayName ?: it }

fun CandidateVersion.toJavaVersion(): JavaVersion {
    val providerCode = javaProviderCode(version)
    return JavaVersion(
        identifier = version,
        featureVersion = javaFeatureVersion(version) ?: version,
        providerCode = providerCode,
        providerName = javaProviderName(providerCode),
        isInstalled = isInstalled,
        isDefault = isDefault,
        remoteAvailability = remoteAvailability,
    )
}
