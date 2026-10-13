package com.worxbend.zephyr.domain

enum class RetryableReadOperation(
    val label: String,
) {
    InstalledCandidates("installed toolchains"),
    CandidateCatalog("candidate catalog"),
    CandidateDetail("candidate versions"),
    IntegrityChecks("integrity checks"),
}

data class ReadRetryStatus(
    val operation: RetryableReadOperation,
    val nextAttempt: Int,
    val maximumAttempts: Int,
)
