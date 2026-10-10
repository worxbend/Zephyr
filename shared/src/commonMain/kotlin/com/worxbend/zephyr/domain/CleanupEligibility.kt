package com.worxbend.zephyr.domain

/** Shared presentation policy; platform admission still revalidates current filesystem evidence. */
data class CleanupEligibility(
    val eligibleVersions: List<String>,
    val blockedReasons: Map<String, CleanupBlockReason>,
)

enum class CleanupBlockReason {
    Unverified,
    Default,
    Protected,
    NotInstalledLocalOnly,
}

fun cleanupEligibility(
    candidate: Candidate,
    protectedVersions: Set<ProtectedVersion>,
    evidenceTrusted: Boolean,
): CleanupEligibility {
    val eligible = mutableListOf<String>()
    val blocked = linkedMapOf<String, CleanupBlockReason>()
    val installedByVersion = candidate.installedVersions.associateBy(CandidateVersion::version)
    for (version in candidate.localOnlyVersions.distinct()) {
        val installed = installedByVersion[version]
        val reason = when {
            !evidenceTrusted || candidate.remoteEvidence != RemoteEvidenceState.LiveComplete -> CleanupBlockReason.Unverified
            version == candidate.defaultVersion || installed?.isDefault == true -> CleanupBlockReason.Default
            ProtectedVersion(candidate.name, version) in protectedVersions -> CleanupBlockReason.Protected
            installed?.isInstalled != true || !installed.isConfirmedLocalOnly -> CleanupBlockReason.NotInstalledLocalOnly
            else -> null
        }
        if (reason == null) eligible += version else blocked[version] = reason
    }
    return CleanupEligibility(eligible.toList(), blocked.toMap())
}
