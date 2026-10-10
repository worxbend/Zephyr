package com.worxbend.zephyr.data

/** One policy for free-text persistence and exports; identifiers stay structured/validated.
 * Unquoted paths may contain spaces: prefer redacting the remaining clause over leaking a suffix.
 */
internal class SensitiveTextRedactor(sensitivePaths: List<String>) {
    private val configuredPaths = sensitivePaths.filter(String::isNotBlank).distinct().sortedByDescending(String::length)
        .map { path ->
            Regex(Regex.escape(path) + """(?:[/\\]$UNQUOTED_PATH_TAIL)?""", RegexOption.IGNORE_CASE)
        }

    fun redact(text: String): String {
        // A colon can introduce a local path as well as a URL. Scan opaque quoted
        // paths and non-file URLs in source order: a URL inside a quoted filename is
        // private, while quotes inside an actual URL must not turn it into a path.
        val platformRedacted = buildString {
            var start = 0
            QUOTED_PATH_OR_URL.findAll(text).forEach { token ->
                append(redactPlatformPaths(text.substring(start, token.range.first)))
                val quote = token.groupValues[1]
                append(if (quote.isEmpty()) token.value else "$quote<redacted-path>$quote")
                start = token.range.last + 1
            }
            append(redactPlatformPaths(text.substring(start)))
        }
        return configuredPaths.fold(platformRedacted) { redacted, path -> path.replace(redacted, "<redacted-path>") }
    }

    private fun redactPlatformPaths(text: String): String = text
        .replace(FILE_URI, "<redacted-path>")
        .replace(WINDOWS_ABSOLUTE_PATH, "<redacted-path>")
        .replace(UNC_ABSOLUTE_PATH, "<redacted-path>")
        .replace(UNIX_ABSOLUTE_PATH, "<redacted-path>")
}

private val QUOTED_ABSOLUTE_PATH = Regex("""(["'])((?:[A-Za-z]:[\\/]|\\\\|/)(?:(?!\1)[^\r\n]|'(?=[\p{L}\p{N}/\\_.-])|'(?=[^'\r\n;|]*'(?![A-Za-z]:[\\/]|\\\\|/)))*)\1""")
// Apostrophes are valid filename characters, not unquoted-path terminators. Quoted
// paths above use their matching delimiter (and allow embedded O'Reilly-style names).
private const val UNQUOTED_PATH_TAIL = """[^\r\n\t"<>;,|)\]}“”]*"""
private val WINDOWS_ABSOLUTE_PATH = Regex("""(?i)(?<![A-Za-z0-9])[A-Z]:[\\/]$UNQUOTED_PATH_TAIL""")
private val UNC_ABSOLUTE_PATH = Regex("""(?<!\\)\\\\$UNQUOTED_PATH_TAIL""")
private val NON_FILE_URL = Regex("""(?i)(?<![\p{L}\p{N}_./\\-])(?!file:)[A-Z][A-Z0-9+.-]*://[^\s"<>;,|)\]}“”]+""")
private val QUOTED_PATH_OR_URL = Regex("(?:${QUOTED_ABSOLUTE_PATH.pattern})|(?:${NON_FILE_URL.pattern})")
private val UNIX_ABSOLUTE_PATH = Regex("""(?<![A-Za-z0-9/\\])/$UNQUOTED_PATH_TAIL""")
private val FILE_URI = Regex("""(?i)file:/+$UNQUOTED_PATH_TAIL""")

internal fun defaultSensitiveExportPaths(
    userHome: String? = System.getProperty("user.home"),
    environmentSdkmanHome: String? = System.getenv("SDKMAN_DIR"),
    configuredSdkmanHome: String? = JvmSdkmanHomeConfigurationService().resolveHome().toString(),
): List<String> =
    listOfNotNull(userHome, environmentSdkmanHome, configuredSdkmanHome)
        .filter(String::isNotBlank)
        .distinct()
