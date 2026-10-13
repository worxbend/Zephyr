package com.worxbend.zephyr.domain

enum class IntegrityCheckId {
    RequiredScripts,
    CandidatesDirectory,
    CandidateEntries,
    VersionEntries,
    DefaultLinks,
}

enum class IntegrityStatus(
    val label: String,
) {
    Passed("Passed"),
    Warning("Warning"),
    Failed("Failed"),
}

data class IntegrityCheck(
    val id: IntegrityCheckId,
    val title: String,
    val status: IntegrityStatus,
    val detail: String,
)
