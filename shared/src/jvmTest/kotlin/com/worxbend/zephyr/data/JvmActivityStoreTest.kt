package com.worxbend.zephyr.data

import com.worxbend.zephyr.domain.ActivityAction
import com.worxbend.zephyr.domain.ActivityEvent
import com.worxbend.zephyr.domain.ActivitySeverity
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmActivityStoreTest {
    @Test
    fun roundTripsAcknowledgementSeverityAndAllowlistedAction() =
        runBlocking {
            val directory = Files.createTempDirectory("zephyr-activity-store-")
            try {
                val destination = directory.resolve("activity.ledger")
                val events =
                    listOf(
                        ActivityEvent(
                            id = 7,
                            timestampEpochMillis = 123,
                            severity = ActivitySeverity.Warning,
                            message = "Review the interrupted task.",
                            action = ActivityAction.OpenTaskCenter,
                            acknowledged = true,
                        ),
                    )
                val store = JvmActivityStore(destination)

                store.save(events)

                assertEquals(events, store.load())
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun redactsPathsAndBoundsDurableRetention() =
        runBlocking {
            val directory = Files.createTempDirectory("zephyr-activity-store-")
            try {
                val destination = directory.resolve("activity.ledger")
                val secretHome = "/private/example/sdkman"
                val events =
                    (1L..4L).map { id ->
                        ActivityEvent(
                            id = id,
                            timestampEpochMillis = id,
                            severity = ActivitySeverity.Error,
                            message = "Failure under $secretHome/candidates/java",
                        )
                    }
                val store =
                    JvmActivityStore(
                        destination = destination,
                        sensitivePaths = { listOf(secretHome) },
                        retentionLimit = 2,
                    )

                store.save(events)

                val content = Files.readString(destination)
                assertFalse(content.contains(secretHome))
                assertEquals(listOf(4L, 3L), store.load().map(ActivityEvent::id))
                assertTrue(store.load().all { "<redacted-path>" in it.message })
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun persistedMessagesUseTheSamePathRedactionAsSupportExports() =
        runBlocking {
            val directory = Files.createTempDirectory("zephyr-activity-redaction-")
            try {
                val destination = directory.resolve("activity.ledger")
                val store = JvmActivityStore(destination, sensitivePaths = { emptyList() })
                val events =
                    sensitivePathBoundaryFixtures.mapIndexed { index, path ->
                        ActivityEvent(index.toLong(), index.toLong(), ActivitySeverity.Error, "Failure at $path; retry.")
                    } + ActivityEvent(100, 100, ActivitySeverity.Info, "See https://example.test/downloads/java; retry.")

                store.save(events)

                val messages = store.load().associate { it.id to it.message }
                sensitivePathBoundaryFixtures.indices.forEach { index ->
                    val message = requireNotNull(messages[index.toLong()])
                    assertFalse("customer-private" in message, message)
                    assertFalse("Reilly" in message, message)
                    assertTrue("<redacted-path>" in message, message)
                    assertTrue(message.endsWith("; retry."), message)
                }
                assertEquals(events.last().message, messages[100L])
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun loadRejectsLeafSymlinksRatherThanReadingExternalHistory() =
        runBlocking {
            val directory = Files.createTempDirectory("zephyr-activity-link-")
            try {
                val external = directory.resolve("external")
                val content = renderActivityLedger(emptyList())
                Files.writeString(external, content)
                val destination = directory.resolve("activity.ledger")
                Files.createSymbolicLink(destination, external)

                assertFailsWith<IllegalStateException> { JvmActivityStore(destination).load() }
                assertEquals(content, Files.readString(external))
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun loadRejectsOversizedHistoryBeforeParsing() =
        runBlocking {
            val directory = Files.createTempDirectory("zephyr-activity-size-")
            try {
                val destination = directory.resolve("activity.ledger")
                Files.writeString(destination, renderActivityLedger(emptyList()) + "\n".repeat(8 * 1_024 * 1_024))
                assertFailsWith<IllegalStateException> { JvmActivityStore(destination).load() }
                Unit
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun saveRejectsLinkedDirectoryWithoutWritingExternalFiles() =
        runBlocking {
            val directory = Files.createTempDirectory("zephyr-activity-parent-")
            try {
                val external = Files.createDirectory(directory.resolve("external"))
                val alias = directory.resolve("alias")
                Files.createSymbolicLink(alias, external)

                assertFailsWith<IllegalStateException> { JvmActivityStore(alias.resolve("activity.ledger")).save(emptyList()) }
                Files.list(external).use { assertEquals(0L, it.count()) }
            } finally {
                directory.toFile().deleteRecursively()
            }
        }

    @Test
    fun rejectsUnknownActivityLedgerVersions() {
        val failure =
            runCatching {
                parseActivityLedger("ZEPHYR_ACTIVITY_CENTER\t99\n")
            }.exceptionOrNull()
        assertTrue(failure is IllegalArgumentException)
    }
}
