package com.worxbend.zephyr.features.maintenance

import com.worxbend.zephyr.domain.Candidate
import com.worxbend.zephyr.domain.cleanupEligibility
import com.worxbend.zephyr.viewmodel.ZephyrUiState

/** A detail read is not an audit admission. Only completed, live audit findings grant targets. */
fun trustedCleanupVersionsByCandidate(state: ZephyrUiState.Ready, candidates: List<Candidate>): Map<String, List<String>> {
    val admittedCandidates = state.localOnlyScanProgress?.trustedFindings.orEmpty().mapTo(mutableSetOf(), Candidate::name)
    return candidates.filter { it.name in admittedCandidates }.associate { it.name to trustedCleanupVersions(state, it) }
}

fun trustedCleanupVersions(state: ZephyrUiState.Ready, candidate: Candidate): List<String> {
    val finding = state.localOnlyScanProgress?.trustedFindings?.firstOrNull { it.name == candidate.name }
        ?: return emptyList()
    val admitted = cleanupEligibility(finding, state.protectedVersions, evidenceTrusted = true).eligibleVersions.toSet()
    return cleanupEligibility(candidate, state.protectedVersions, evidenceTrusted = true).eligibleVersions.filter { it in admitted }
}
