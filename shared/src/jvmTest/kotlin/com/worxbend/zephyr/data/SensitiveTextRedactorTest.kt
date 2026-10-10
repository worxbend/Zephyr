package com.worxbend.zephyr.data

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

internal fun operationTestDirectory(prefix: String): Path = Files.createTempDirectory(
    Path.of(requireNotNull(System.getenv("TMPDIR")) { "Tests require the configured scratch directory." }), prefix,
)

// Synthetic fixtures shared by direct, persisted-ledger, CSV and support-bundle regressions.
internal val sensitivePathBoundaryFixtures = listOf(
    "/srv/O'Reilly/customer-private/build",
    "/srv/O’Reilly/customer-private/build",
    "/srv/O' Reilly/customer-private folder/build",
    "\"/srv/O'Reilly/customer-private folder/build\"",
    "\"/srv/O'Reilly folder https://example.test/customer-private\"",
    "'/srv/O\"Reilly/customer-private folder/build'",
    "'/srv/O'Reilly/customer-private folder/build'",
    "'/srv/O' Reilly/customer-private folder/build'",
    "'/srv/customer-private O' Reilly'",
    """C:\Customers\O'Reilly\customer-private\build""",
    "C:/Customers/O'Reilly/customer-private/build",
    """\\server\O'Reilly\customer-private\build""",
    "\"C:/Customers/O'Reilly/customer-private folder/build\"",
    "file:///srv/O'Reilly/customer-private/build",
    "path:/srv/customer-private/project",
)

class SensitiveTextRedactorTest {
    @Test
    fun redactsWholeConfiguredSubpathsAndExternalPlatformPaths() {
        val redactor = SensitiveTextRedactor(listOf("/home/fake", "C:\\Users\\fake"))
        val fixtures = listOf(
            "/home/fake/private-token/file", "/srv/private-token/file", "C:\\Customers\\private-token\\file",
            "C:/Customers/private-token/file", "\\\\server\\private-token\\file",
            "\"/srv/private-token folder/build\"", "\"C:\\private-token folder\\build\"",
            "/srv/customer private-token/build", "C:\\Customers\\customer private-token\\build",
            "file:///srv/private-token/build",
        )
        fixtures.forEach { path ->
            val text = redactor.redact("Failure at $path; retry.")
            assertFalse(text.contains("private-token"), text)
            assertTrue(text.contains("<redacted-path>"), text)
            assertTrue(text.contains("retry."), text)
            kotlin.test.assertEquals(text, redactor.redact(text))
        }
    }

    @Test
    fun redactsColonAdjacentUnixPathsWithoutRedactingUrls() {
        val redactor = SensitiveTextRedactor(emptyList())
        listOf("path:", "path=", "path: ", "(", "[").forEach { prefix ->
            kotlin.test.assertEquals(
                "Failure ${prefix}<redacted-path>; retry.",
                redactor.redact("Failure ${prefix}/srv/customer-private/project; retry."),
            )
        }
        val urls = listOf(
            "https://example.test/downloads/java", "http://example.test:8080/api/v1",
            "https://example.test/search?next=/public/project", "ssh://example.test/srv/public/project",
            "https://example.test/'/public/project'", "https://example.test/O'Reilly/downloads",
            "HTTPS://example.test/api?next=unix:/public/project",
        )
        urls.forEach { url ->
            kotlin.test.assertEquals("See $url; retry.", redactor.redact("See $url; retry."))
        }
        kotlin.test.assertEquals(
            "See https://example.test/docs;path:<redacted-path>; retry.",
            redactor.redact("See https://example.test/docs;path:/srv/O'Reilly/customer-private; retry."),
        )
        kotlin.test.assertEquals(
            "URL:https://example.test/docs; path:<redacted-path>; retry.",
            redactor.redact("URL:https://example.test/docs; path:/srv/customer-private; retry."),
        )
    }

    @Test
    fun redactsEmbeddedApostrophesWithoutLeavingFilenameSuffixes() {
        val redactor = SensitiveTextRedactor(emptyList())
        sensitivePathBoundaryFixtures.forEach { path ->
            val text = redactor.redact("Failure at $path; retry.")
            assertFalse(text.contains("customer-private"), "path=$path: $text")
            assertFalse(text.contains("Reilly"), "path=$path: $text")
            assertTrue(text.contains("<redacted-path>"), "path=$path: $text")
            assertTrue(text.endsWith("; retry."), "path=$path: $text")
            kotlin.test.assertEquals(text, redactor.redact(text))
        }
    }

    @Test
    fun preservesQuotedPathDelimitersAndAdjacentProse() {
        val redactor = SensitiveTextRedactor(emptyList())
        listOf('"', '\'').forEach { quote ->
            listOf("/srv/customer-private folder/build", """C:\Customers\customer-private folder\build""").forEach { path ->
                kotlin.test.assertEquals(
                    "Failure at ${quote}<redacted-path>${quote}; retry.",
                    redactor.redact("Failure at $quote$path$quote; retry."),
                )
            }
        }
        kotlin.test.assertEquals(
            "Compare '<redacted-path>' and '<redacted-path>'; retry.",
            redactor.redact("Compare '/srv/customer-private folder' and '/srv/other-private folder'; retry."),
        )
        kotlin.test.assertEquals(
            "Failure at \"<redacted-path>\"; retry.",
            redactor.redact("Failure at \"/srv/O'Reilly/customer-private folder/build\"; retry."),
        )
    }

    @Test
    fun redactsConfiguredSubpathsWithEmbeddedApostrophes() {
        val redactor = SensitiveTextRedactor(listOf("synthetic-worktree"))
        listOf("O'Reilly", "O' Reilly", "O’Reilly").forEach { name ->
            kotlin.test.assertEquals(
                "Failure at <redacted-path>; retry.",
                redactor.redact("Failure at synthetic-worktree/$name/customer-private/build; retry."),
            )
        }
    }

    @Test
    fun leavesOrdinaryCommandIdentifiersAndProseIntact() {
        val text = "Install java 21-tem; SDKMAN 5.20; success 1/2."
        kotlin.test.assertEquals(text, SensitiveTextRedactor(emptyList()).redact(text))
    }
}
