package com.worxbend.zephyr.sdkman

import com.worxbend.zephyr.domain.CandidateCatalogItem
import com.worxbend.zephyr.domain.CandidateKind
import com.worxbend.zephyr.domain.CandidateVersion
import com.worxbend.zephyr.domain.RemoteAvailability
import com.worxbend.zephyr.domain.candidateKindFor
import com.worxbend.zephyr.domain.displayNameFor
import com.worxbend.zephyr.domain.isValidSdkmanCandidateName
import com.worxbend.zephyr.domain.isValidSdkmanVersion

object SdkmanListParser {
    private val installLine = Regex("""sdk\s+install\s+([a-zA-Z0-9._-]+)""")
    private val url = Regex("""https?://\S+""")
    private val parens = Regex("""\(([^)]+)\)""")
    private val candidateHeader = Regex("""^(.+?)\s{2,}(https?://\S+)\s*$""")
    private val versionToken = Regex("""^[A-Za-z0-9][A-Za-z0-9._+-]*(-[A-Za-z0-9._+-]+)?$""")

    fun parseCatalog(
        output: String,
        installedNames: Set<String>,
    ): List<CandidateCatalogItem> {
        val lines = output.lines()
        val items = mutableListOf<CandidateCatalogItem>()
        for (index in lines.indices) {
            val match = installLine.find(lines[index]) ?: continue
            val name = match.groupValues[1]
            if (!isValidSdkmanCandidateName(name)) continue
            val block = candidateBlock(lines, index)
            val header = block.firstOrNull()?.trim()
            val headerMatch = header?.let { candidateHeader.find(it) }
            val title = headerMatch?.groupValues?.get(1)?.trim() ?: header
            val description =
                block
                    .drop(1)
                    .filterNot { it.trim().startsWith("$") }
                    .joinToString(" ") { it.trim() }
                    .replace(Regex("""\s+"""), " ")
                    .ifBlank { null }

            val kind = candidateKindFor(name)
            val fallbackDisplay = displayNameFor(name)
            val stable =
                title?.let { line ->
                    parens
                        .findAll(line)
                        .map { it.groupValues[1] }
                        .firstOrNull { value -> value.any(Char::isDigit) && !value.startsWith("http") }
                }
            val website = headerMatch?.groupValues?.get(2)?.trimEnd('.') ?: header?.let { url.find(it)?.value?.trimEnd('.') }
            val display =
                if (kind == CandidateKind.Jdk) {
                    "JDK"
                } else {
                    title
                        ?.substringBefore(" (")
                        ?.substringBefore(" https://")
                        ?.substringBefore(" http://")
                        ?.takeIf { it.length in 2..40 }
                        ?: fallbackDisplay
                }

            items +=
                CandidateCatalogItem(
                    name = name,
                    displayName = display,
                    stableVersion = stable,
                    description = description,
                    websiteUrl = website,
                    kind = kind,
                    isInstalled = name in installedNames,
                )
        }
        return items.distinctBy { it.name }.sortedWith(
            compareBy<CandidateCatalogItem> { it.kind != CandidateKind.Jdk }.thenBy { it.displayName },
        )
    }

    private fun candidateBlock(
        lines: List<String>,
        installLineIndex: Int,
    ): List<String> {
        val start =
            lines
                .subList(0, installLineIndex)
                .indexOfLast { it.trim().startsWith("---") }
                .let { if (it < 0) 0 else it + 1 }
        return lines
            .subList(start, installLineIndex)
            .map { it.trimEnd() }
            .dropWhile { line ->
                val trimmed = line.trim()
                trimmed.isBlank() ||
                    trimmed.startsWith("=") ||
                    trimmed.startsWith("-") ||
                    trimmed.startsWith("q-") ||
                    trimmed.startsWith("j-") ||
                    trimmed.startsWith("k-")
            }.dropLastWhile { it.trim().isBlank() }
    }

    fun parseVersions(output: String): List<CandidateVersion> = parseVersionsReport(output).versions

    fun parseVersionsReport(
        output: String,
        outputTruncated: Boolean = false,
    ): VersionParseReport {
        val versions = mutableMapOf<String, CandidateVersion>()
        val rejectedRows = mutableListOf<String>()
        var hasVersionSection = false
        var hasPipeHeader = false
        var hasLooseLegend = false
        var structuralBoundaries = 0
        output.lines().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("Available ", ignoreCase = true) && line.contains(" Versions", ignoreCase = true)) {
                hasVersionSection = true
            }
            if (line.startsWith("=") && line.length >= MIN_STRUCTURAL_SEPARATOR_LENGTH) {
                structuralBoundaries += 1
            }
            if (isPipeHeader(line)) {
                hasPipeHeader = true
                return@forEach
            }
            if (isLooseLegendLine(line)) {
                hasLooseLegend = true
                return@forEach
            }
            if (line.isBlank() || line.startsWith("=") || line.startsWith("-") || isVersionInstructionLine(line)) {
                return@forEach
            }
            val parsed = parsePipeRow(line)?.let(::listOf) ?: parseLooseRow(line)
            if (looksLikeRejectedVersionRow(line, parsed)) {
                rejectedRows += line.take(MAX_REJECTED_ROW_LENGTH)
            }
            parsed.forEach { version ->
                versions[version.version] = version
            }
        }
        val issues =
            buildList {
                if (outputTruncated) add("SDKMAN output was truncated.")
                if (!hasVersionSection) add("Version section header was not recognized.")
                if (!hasPipeHeader && !hasLooseLegend) add("Version table structure was not recognized.")
                if (structuralBoundaries < MIN_STRUCTURAL_BOUNDARIES) add("Version table boundary was incomplete.")
                if (versions.isEmpty()) add("No version rows were recognized.")
                if (rejectedRows.isNotEmpty()) add("${rejectedRows.size} version-like row(s) were not recognized.")
            }
        return VersionParseReport(
            versions = versions.values.sortedWith(versionComparator()),
            confidence = if (issues.isEmpty()) VersionParseConfidence.Trusted else VersionParseConfidence.Degraded,
            issues = issues,
            rejectedRows = rejectedRows,
        )
    }

    private fun parsePipeRow(line: String): CandidateVersion? {
        if (!line.contains("|")) return null
        val cells = line.split("|").map { it.trim() }
        if (cells.size < 2) return null
        val identifier =
            cells.lastOrNull {
                it.matches(versionToken) &&
                    isValidSdkmanVersion(it) &&
                    it.any(Char::isDigit) &&
                    it.lowercase() !in nonVersionTokens
            } ?: return null
        val joined = cells.joinToString(" ").lowercase()
        return CandidateVersion(
            version = identifier,
            isInstalled = "installed" in joined || "local only" in joined || "local" in joined,
            isDefault = ">>>" in joined || "current" in joined,
            remoteAvailability =
                if ("local only" in joined) {
                    RemoteAvailability.LocalOnly
                } else {
                    RemoteAvailability.Available
                },
        )
    }

    private fun parseLooseRow(line: String): List<CandidateVersion> {
        if (line.contains("|")) return emptyList()
        val tokens = line.split(Regex("""\s+""")).filter { it.isNotBlank() }
        val versions = mutableListOf<CandidateVersion>()
        var nextIsInstalled = false
        var nextIsDefault = false
        var nextIsLocalOnly = false

        tokens.forEach { token ->
            when (token) {
                "*" -> {
                    nextIsInstalled = true
                }

                ">" -> {
                    nextIsDefault = true
                }

                "+" -> {
                    nextIsInstalled = true
                    nextIsLocalOnly = true
                }

                else -> {
                    if (isLooseVersionToken(token)) {
                        versions +=
                            CandidateVersion(
                                version = token,
                                isInstalled = nextIsInstalled,
                                isDefault = nextIsDefault,
                                remoteAvailability =
                                    if (nextIsLocalOnly) {
                                        RemoteAvailability.LocalOnly
                                    } else {
                                        RemoteAvailability.Available
                                    },
                            )
                        nextIsInstalled = false
                        nextIsDefault = false
                        nextIsLocalOnly = false
                    }
                }
            }
        }

        return versions
    }

    private fun isVersionInstructionLine(line: String): Boolean {
        val lowered = line.lowercase()
        return lowered.startsWith("available ") ||
            lowered.startsWith("vendor ") ||
            lowered.startsWith("omit identifier") ||
            lowered.startsWith("use tab") ||
            lowered.startsWith("or install") ||
            lowered.startsWith("hit q") ||
            lowered.startsWith("$") ||
            lowered.startsWith("+ -") ||
            lowered.startsWith("* -") ||
            lowered.startsWith("> -")
    }

    private fun isPipeHeader(line: String): Boolean {
        if (!line.contains('|')) return false
        val cells = line.split('|').map { it.trim().lowercase() }
        return "identifier" in cells && "version" in cells
    }

    private fun isLooseLegendLine(line: String): Boolean {
        val lowered = line.lowercase()
        return lowered.startsWith("+ -") ||
            lowered.startsWith("* -") ||
            lowered.startsWith("> -")
    }

    private fun looksLikeRejectedVersionRow(
        line: String,
        parsed: List<CandidateVersion>,
    ): Boolean {
        if (parsed.isNotEmpty() || !line.any(Char::isDigit)) return false
        return line.contains('|') ||
            line.firstOrNull()?.let { it.isDigit() || it in setOf('+', '*', '>') } == true
    }

    private fun isLooseVersionToken(token: String): Boolean {
        val normalized = token.trim()
        val lowered = normalized.lowercase()
        return normalized.matches(versionToken) &&
            isValidSdkmanVersion(normalized) &&
            normalized.any(Char::isDigit) &&
            lowered !in nonVersionTokens &&
            normalized.firstOrNull()?.isDigit() == true &&
            !lowered.endsWith("bit")
    }

    private val nonVersionTokens =
        setOf(
            "vendor",
            "use",
            "version",
            "dist",
            "status",
            "identifier",
            "linux",
            "64bit",
            "32bit",
        )

    private const val MIN_STRUCTURAL_SEPARATOR_LENGTH = 8
    private const val MIN_STRUCTURAL_BOUNDARIES = 2
    private const val MAX_REJECTED_ROW_LENGTH = 160
}

enum class VersionParseConfidence {
    Trusted,
    Degraded,
}

data class VersionParseReport(
    val versions: List<CandidateVersion>,
    val confidence: VersionParseConfidence,
    val issues: List<String>,
    val rejectedRows: List<String>,
) {
    val isTrusted: Boolean
        get() = confidence == VersionParseConfidence.Trusted
}

fun versionComparator(): Comparator<CandidateVersion> =
    compareByDescending<CandidateVersion> { it.isDefault }
        .thenByDescending { it.isInstalled }
        .thenByDescending { semanticPrefix(it.version) }
        .thenBy { it.version }

private fun semanticPrefix(version: String): String =
    version
        .substringBefore('-')
        .split('.', '_')
        .mapNotNull { it.toIntOrNull() }
        .joinToString(".") { it.toString().padStart(4, '0') }
