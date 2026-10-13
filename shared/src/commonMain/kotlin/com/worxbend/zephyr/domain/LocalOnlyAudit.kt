package com.worxbend.zephyr.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class LocalOnlyCandidateScanStatus {
    Pending,
    Active,
    Completed,
    Failed,
}

data class LocalOnlyScanFailure(
    val candidate: String,
    val message: String,
)

data class LocalOnlyCandidateScanProgress(
    val candidate: String,
    val status: LocalOnlyCandidateScanStatus,
    val finding: Candidate? = null,
    val failure: LocalOnlyScanFailure? = null,
)

data class LocalOnlyScanProgress(
    val candidates: List<LocalOnlyCandidateScanProgress>,
    val running: Boolean,
) {
    val total: Int get() = candidates.size
    val completed: Int
        get() =
            candidates.count {
                it.status == LocalOnlyCandidateScanStatus.Completed ||
                    it.status == LocalOnlyCandidateScanStatus.Failed
            }
    val activeCandidates: List<String>
        get() = candidates.filter { it.status == LocalOnlyCandidateScanStatus.Active }.map { it.candidate }
    val failures: List<LocalOnlyScanFailure>
        get() = candidates.mapNotNull(LocalOnlyCandidateScanProgress::failure)
    val trustedFindings: List<Candidate>
        get() =
            candidates.mapNotNull { item ->
                item.finding?.takeIf {
                    item.status == LocalOnlyCandidateScanStatus.Completed &&
                        it.remoteEvidence == RemoteEvidenceState.LiveComplete
                }
            }
}

class LocalOnlyAudit(
    private val concurrencyLimit: Int = DEFAULT_CONCURRENCY_LIMIT,
) {
    init {
        require(concurrencyLimit > 0) { "Local-only audit concurrency must be positive." }
    }

    suspend fun scan(
        installedSnapshot: List<Candidate>,
        initialProgress: LocalOnlyScanProgress? = null,
        targetCandidates: Set<String> = installedSnapshot.mapTo(linkedSetOf(), Candidate::name),
        readCandidate: suspend (String) -> Candidate?,
        publish: (LocalOnlyScanProgress) -> Unit,
    ): LocalOnlyScanProgress =
        coroutineScope {
            val snapshot = installedSnapshot.toList()
            val snapshotNames = snapshot.map(Candidate::name)
            require(snapshotNames.size == snapshotNames.distinct().size) {
                "Installed candidate snapshot contains duplicate names."
            }
            require(targetCandidates.all { it in snapshotNames }) {
                "Audit targets must belong to the installed snapshot."
            }

            val previousByName = initialProgress?.candidates.orEmpty().associateBy { it.candidate }
            val items =
                snapshot
                    .map { candidate ->
                        previousByName[candidate.name]
                            ?.takeIf { candidate.name !in targetCandidates }
                            ?: LocalOnlyCandidateScanProgress(candidate.name, LocalOnlyCandidateScanStatus.Pending)
                    }.toMutableList()
            val indexByName = snapshotNames.withIndex().associate { it.value to it.index }
            val stateMutex = Mutex()

            fun progress(running: Boolean) = LocalOnlyScanProgress(items.toList(), running)
            publish(progress(running = targetCandidates.isNotEmpty()))

            val queue = Channel<String>(capacity = targetCandidates.size)
            targetCandidates.forEach { queue.trySend(it).getOrThrow() }
            queue.close()

            val workers =
                List(minOf(concurrencyLimit, targetCandidates.size)) {
                    launch {
                        for (candidateName in queue) {
                            val index = indexByName.getValue(candidateName)
                            stateMutex.withLock {
                                items[index] =
                                    LocalOnlyCandidateScanProgress(
                                        candidateName,
                                        LocalOnlyCandidateScanStatus.Active,
                                    )
                                publish(progress(running = true))
                            }
                            val result =
                                try {
                                    Result.success(readCandidate(candidateName))
                                } catch (exception: CancellationException) {
                                    throw exception
                                } catch (exception: Exception) {
                                    Result.failure(exception)
                                }
                            stateMutex.withLock {
                                items[index] =
                                    result.fold(
                                        onSuccess = { finding ->
                                            if (finding == null) {
                                                failedProgress(candidateName, "Candidate metadata was unavailable.")
                                            } else {
                                                LocalOnlyCandidateScanProgress(
                                                    candidateName,
                                                    LocalOnlyCandidateScanStatus.Completed,
                                                    finding = finding,
                                                )
                                            }
                                        },
                                        onFailure = { failure ->
                                            failedProgress(
                                                candidateName,
                                                failure.message ?: failure::class.simpleName ?: "Candidate read failed.",
                                            )
                                        },
                                    )
                                publish(progress(running = true))
                            }
                        }
                    }
                }
            workers.joinAll()
            val complete = progress(running = false)
            publish(complete)
            complete
        }

    private fun failedProgress(
        candidate: String,
        message: String,
    ) = LocalOnlyCandidateScanProgress(
        candidate = candidate,
        status = LocalOnlyCandidateScanStatus.Failed,
        failure = LocalOnlyScanFailure(candidate, message),
    )

    companion object {
        const val DEFAULT_CONCURRENCY_LIMIT = 3
    }
}
