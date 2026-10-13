package com.worxbend.zephyr.sdkman

import com.worxbend.zephyr.domain.CandidateKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SdkmanListParserTest {
    @Test
    fun ignoresCatalogAndVersionIdentifiersOutsideTheCommandBoundary() {
        val invalidCandidateCatalog =
            """
            Bad Candidate (1.0)                                      https://example.invalid/
            ${'$'} sdk install BAD_CANDIDATE
            --------------------------------------------------------------------------------
            Valid Candidate (1.0)                                    https://example.invalid/
            ${'$'} sdk install valid-candidate
            """.trimIndent()
        val oversizedVersion = "1" + "a".repeat(256)

        assertEquals(listOf("valid-candidate"), SdkmanListParser.parseCatalog(invalidCandidateCatalog, emptySet()).map { it.name })
        assertEquals(listOf("21.0.5-tem"), SdkmanListParser.parseVersions("$oversizedVersion 21.0.5-tem").map { it.version })
    }

    @Test
    fun parsesCatalogBlocksFromSdkmanListOutput() {
        val output =
            """
            ================================================================================
            Available Candidates
            ================================================================================
            q-quit                                  /-search down
            --------------------------------------------------------------------------------
            Ant (1.10.17)                                            https://ant.apache.org/

            Apache Ant is a Java library and command-line tool.

                                                                   ${'$'} sdk install ant
            --------------------------------------------------------------------------------
            Java (25.0.1-tem)                                            https://sdkman.io/jdks

            Java Development Kits from multiple providers.

                                                                  ${'$'} sdk install java
            """.trimIndent()

        val catalog = SdkmanListParser.parseCatalog(output, installedNames = setOf("java"))

        assertEquals(2, catalog.size)
        assertEquals("ant", catalog[1].name)
        assertEquals("Ant", catalog[1].displayName)
        assertEquals("1.10.17", catalog[1].stableVersion)
        assertEquals(CandidateKind.Jdk, catalog[0].kind)
        assertEquals("JDK", catalog[0].displayName)
        assertTrue(catalog[0].isInstalled)
    }

    @Test
    fun parsesMultiColumnVersionRowsWithoutMarkingRemoteVersionsLocalOnly() {
        val output =
            """
            ================================================================================
            Available Scala Versions
            ================================================================================
             > * 3.8.3               3.3.1               2.13.6              2.12.4
                 3.8.2               3.3.0               2.13.5              2.12.3

            ================================================================================
            + - local version
            * - installed
            > - currently in use
            ================================================================================
            """.trimIndent()

        val versions = SdkmanListParser.parseVersions(output)
        val latest = versions.first { it.version == "3.8.3" }
        val remote = versions.first { it.version == "3.3.1" }

        assertEquals(8, versions.size)
        assertTrue(latest.isInstalled)
        assertTrue(latest.isDefault)
        assertTrue(latest.isRemoteAvailable)
        assertFalse(remote.isInstalled)
        assertTrue(remote.isRemoteAvailable)
    }

    @Test
    fun skipsJavaListHeadingsAndInstructions() {
        val output =
            """
            Available Java Versions for Linux 64bit
            ================================================================================
             Vendor        | Use | Version      | Dist    | Status     | Identifier
            --------------------------------------------------------------------------------
             Temurin       |     | 25.0.3       | tem     |            | 25.0.3-tem
             Oracle        |     | 24.0.2       | oracle  |            | 24.0.2-oracle
            ================================================================================
            Omit Identifier to install default version 25.0.3-tem:
                ${'$'} sdk install java
            Use TAB completion to discover available versions
                ${'$'} sdk install java [TAB]
            Or install a specific version by Identifier:
                ${'$'} sdk install java 25.0.3-tem
            Hit Q to exit this list view
            ================================================================================
            """.trimIndent()

        val versions = SdkmanListParser.parseVersions(output)

        assertEquals(listOf("25.0.3-tem", "24.0.2-oracle"), versions.map { it.version })
    }

    @Test
    fun marksPartialTablesAsDegradedEvenWhenSomeRowsParse() {
        val output =
            """
            ================================================================================
            Available Java Versions for Linux 64bit
            ================================================================================
             Vendor        | Use | Version      | Dist    | Status     | Identifier
            --------------------------------------------------------------------------------
             Temurin       |     | 25.0.3       | tem     |            | 25.0.3-tem
             Unknown row   | data that changed upstream 2027
            ================================================================================
            """.trimIndent()

        val report = SdkmanListParser.parseVersionsReport(output)

        assertEquals(listOf("25.0.3-tem"), report.versions.map { it.version })
        assertEquals(VersionParseConfidence.Degraded, report.confidence)
        assertEquals(1, report.rejectedRows.size)
        assertTrue(report.issues.any { "not recognized" in it })
    }

    @Test
    fun requiresCompleteNonTruncatedEvidenceForTrustedReports() {
        val complete =
            """
            ================================================================================
            Available Java Versions for Linux 64bit
            ================================================================================
             Vendor        | Use | Version      | Dist    | Status     | Identifier
            --------------------------------------------------------------------------------
             Temurin       |     | 25.0.3       | tem     |            | 25.0.3-tem
            ================================================================================
            """.trimIndent()

        assertEquals(VersionParseConfidence.Trusted, SdkmanListParser.parseVersionsReport(complete).confidence)
        assertEquals(
            VersionParseConfidence.Degraded,
            SdkmanListParser.parseVersionsReport(complete, outputTruncated = true).confidence,
        )
    }
}
