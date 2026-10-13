package com.worxbend.zephyr.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class LocalOnlyAuditTest {
    @Test
    fun boundsConcurrentReadsAndPublishesDeterministicPartialProgress() =
        runBlocking {
            val active = AtomicInteger()
            val maximumActive = AtomicInteger()
            val publications = mutableListOf<LocalOnlyScanProgress>()
            val snapshot = listOf("java", "gradle", "kotlin", "maven", "ant").map(::candidate)

            val complete =
                LocalOnlyAudit(concurrencyLimit = 2).scan(
                    installedSnapshot = snapshot,
                    readCandidate = { name ->
                        val current = active.incrementAndGet()
                        maximumActive.accumulateAndGet(current, ::maxOf)
                        delay(20)
                        active.decrementAndGet()
                        candidate(name).copy(
                            hasLocalOnlyVersions = true,
                            localOnlyVersionCount = 1,
                            localOnlyVersions = listOf("$name-local"),
                            remoteEvidence = RemoteEvidenceState.LiveComplete,
                        )
                    },
                    publish = publications::add,
                )

            assertTrue(maximumActive.get() <= 2)
            assertTrue(publications.any { it.running && it.completed in 1 until snapshot.size })
            assertEquals(snapshot.map(Candidate::name), complete.candidates.map { it.candidate })
            assertEquals(snapshot.map(Candidate::name), complete.trustedFindings.map(Candidate::name))
            assertEquals(snapshot.size, complete.completed)
            assertTrue(!complete.running)
        }

    @Test
    fun failureDoesNotDiscardSuccessAndRetryTargetsOnlyFailedCandidate() =
        runBlocking {
            val snapshot = listOf(candidate("java"), candidate("gradle"), candidate("kotlin"))
            val firstReads = mutableListOf<String>()
            val first =
                LocalOnlyAudit(concurrencyLimit = 2).scan(
                    installedSnapshot = snapshot,
                    readCandidate = { name ->
                        firstReads += name
                        if (name == "gradle") error("temporary read failure")
                        candidate(name).copy(remoteEvidence = RemoteEvidenceState.LiveComplete)
                    },
                    publish = {},
                )

            assertEquals(listOf("gradle"), first.failures.map(LocalOnlyScanFailure::candidate))
            assertEquals(listOf("java", "kotlin"), first.trustedFindings.map(Candidate::name))
            assertEquals(snapshot.map(Candidate::name).toSet(), firstReads.toSet())

            val retryReads = mutableListOf<String>()
            val retried =
                LocalOnlyAudit(concurrencyLimit = 2).scan(
                    installedSnapshot = snapshot,
                    initialProgress = first,
                    targetCandidates = setOf("gradle"),
                    readCandidate = { name ->
                        retryReads += name
                        candidate(name).copy(remoteEvidence = RemoteEvidenceState.LiveComplete)
                    },
                    publish = {},
                )

            assertEquals(listOf("gradle"), retryReads)
            assertTrue(retried.failures.isEmpty())
            assertEquals(snapshot.map(Candidate::name), retried.trustedFindings.map(Candidate::name))
        }

    @Test
    fun cancellationStopsTheAuditInsteadOfPublishingAFalseCompletion() =
        runBlocking {
            val readStarted = CompletableDeferred<Unit>()
            val releaseRead = CompletableDeferred<Unit>()
            val publications = mutableListOf<LocalOnlyScanProgress>()
            val audit =
                async {
                    LocalOnlyAudit(concurrencyLimit = 1).scan(
                        installedSnapshot = listOf(candidate("java")),
                        readCandidate = {
                            readStarted.complete(Unit)
                            releaseRead.await()
                            candidate(it).copy(remoteEvidence = RemoteEvidenceState.LiveComplete)
                        },
                        publish = publications::add,
                    )
                }
            readStarted.await()

            audit.cancelAndJoin()

            assertTrue(audit.isCancelled)
            assertFailsWith<CancellationException> { audit.await() }
            assertTrue(publications.none { !it.running && it.completed == it.total })
        }

    private fun candidate(name: String) =
        Candidate(
            name = name,
            displayName = name,
            kind = if (name == "java") CandidateKind.Jdk else CandidateKind.Sdk,
            installedVersions = emptyList(),
            defaultVersion = null,
            hasLocalOnlyVersions = false,
            localOnlyVersionCount = 0,
            localOnlyVersions = emptyList(),
        )
}
