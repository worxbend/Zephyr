package com.worxbend.zephyr.data

import com.worxbend.zephyr.domain.InstallTarget
import com.worxbend.zephyr.domain.isValidSdkmanCandidateName
import com.worxbend.zephyr.domain.isValidSdkmanVersion
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path

internal class JvmTerminalLauncher(
    private val terminalCandidates: List<File> = DEFAULT_TERMINALS.map(::File),
    private val bash: File = File("/usr/bin/bash"),
    private val processStarter: (ProcessBuilder) -> Unit = { it.start() },
) : TerminalLauncher {
    override fun launch(
        sdkmanHome: String,
        candidate: String,
        version: String,
    ): TerminalLaunchResult {
        if (sdkmanHome.isBlank() || candidate.isBlank() || version.isBlank()) {
            return TerminalLaunchResult(false, "SDKMAN home, candidate, and version are required.")
        }
        if (listOf(sdkmanHome, candidate, version).any(::containsControlCharacters)) {
            return TerminalLaunchResult(false, "Terminal activation values contain unsupported control characters.")
        }
        if (!isValidSdkmanCandidateName(candidate) || !isValidSdkmanVersion(version)) {
            return TerminalLaunchResult(false, "Candidate or version is not a valid SDKMAN identifier.")
        }
        if (!bash.canExecute()) {
            return TerminalLaunchResult(false, "Bash is unavailable.")
        }
        val terminal =
            terminalCandidates.firstOrNull(File::canExecute)
                ?: return TerminalLaunchResult(false, "No supported terminal emulator was found.")
        return runCatching {
            val process = ProcessBuilder(terminalCommand(terminal, bash))
            process.environment()["SDKMAN_DIR"] = sdkmanHome
            process.environment()["ZEPHYR_SDKMAN_CANDIDATE"] = candidate
            process.environment()["ZEPHYR_SDKMAN_VERSION"] = version
            processStarter(process)
            TerminalLaunchResult(true, "Opened an activated terminal.")
        }.getOrElse { failure ->
            TerminalLaunchResult(false, failure.message ?: "Unable to open a terminal.")
        }
    }

    override fun launchWorkspace(
        sdkmanHome: String,
        workingDirectory: String,
        targets: List<InstallTarget>,
    ): TerminalLaunchResult {
        if (sdkmanHome.isBlank() || workingDirectory.isBlank() || targets.isEmpty()) {
            return TerminalLaunchResult(false, "SDKMAN home, project directory, and toolchain targets are required.")
        }
        if (
            containsControlCharacters(sdkmanHome) ||
            containsControlCharacters(workingDirectory) ||
            targets.any {
                !isValidSdkmanCandidateName(it.candidate) ||
                    !isValidSdkmanVersion(it.version)
            }
        ) {
            return TerminalLaunchResult(false, "Project terminal values are not valid SDKMAN identifiers or paths.")
        }
        val directory =
            safeWorkingDirectory(workingDirectory)
                ?: return TerminalLaunchResult(false, "The project directory is missing, unsafe, or contains a symbolic link.")
        if (!bash.canExecute()) {
            return TerminalLaunchResult(false, "Bash is unavailable.")
        }
        val terminal =
            terminalCandidates.firstOrNull(File::canExecute)
                ?: return TerminalLaunchResult(false, "No supported terminal emulator was found.")
        return runCatching {
            val process = ProcessBuilder(workspaceTerminalCommand(terminal, bash))
            process.directory(directory)
            process.environment()["SDKMAN_DIR"] = sdkmanHome
            process.environment()["ZEPHYR_SDKMAN_TARGET_COUNT"] = targets.size.toString()
            targets.forEachIndexed { index, target ->
                process.environment()["ZEPHYR_SDKMAN_CANDIDATE_$index"] = target.candidate
                process.environment()["ZEPHYR_SDKMAN_VERSION_$index"] = target.version
            }
            processStarter(process)
            TerminalLaunchResult(true, "Opened a project-scoped toolchain terminal.")
        }.getOrElse { failure ->
            TerminalLaunchResult(false, failure.message ?: "Unable to open a project terminal.")
        }
    }
}

internal fun terminalCommand(
    terminal: File,
    bash: File,
): List<String> {
    val separator =
        when (terminal.name) {
            "gnome-terminal", "ptyxis" -> listOf("--")
            "kitty" -> emptyList()
            else -> listOf("-e")
        }
    return listOf(terminal.absolutePath) +
        separator +
        listOf(bash.absolutePath, "-lc", ACTIVATION_SCRIPT)
}

internal fun workspaceTerminalCommand(
    terminal: File,
    bash: File,
): List<String> {
    val separator =
        when (terminal.name) {
            "gnome-terminal", "ptyxis" -> listOf("--")
            "kitty" -> emptyList()
            else -> listOf("-e")
        }
    return listOf(terminal.absolutePath) +
        separator +
        listOf(bash.absolutePath, "-lc", WORKSPACE_ACTIVATION_SCRIPT)
}

private fun containsControlCharacters(value: String): Boolean = value.any { it.code < 32 || it.code == 127 }

private const val ACTIVATION_SCRIPT =
    """source "${'$'}SDKMAN_DIR/bin/sdkman-init.sh" && sdk use "${'$'}ZEPHYR_SDKMAN_CANDIDATE" "${'$'}ZEPHYR_SDKMAN_VERSION" && exec "${'$'}{SHELL:-/bin/bash}" -i"""

private const val WORKSPACE_ACTIVATION_SCRIPT =
    """source "${'$'}SDKMAN_DIR/bin/sdkman-init.sh" || exit 1
i=0
while [ "${'$'}i" -lt "${'$'}ZEPHYR_SDKMAN_TARGET_COUNT" ]; do
  candidate_var="ZEPHYR_SDKMAN_CANDIDATE_${'$'}i"
  version_var="ZEPHYR_SDKMAN_VERSION_${'$'}i"
  sdk use "${'$'}{!candidate_var}" "${'$'}{!version_var}" || exit 1
  i=${'$'}((i + 1))
done
exec "${'$'}{SHELL:-/bin/bash}" -i"""

private fun safeWorkingDirectory(value: String): File? =
    runCatching {
        val path = Path.of(value).toAbsolutePath().normalize()
        var current = requireNotNull(path.root)
        path.forEach { segment ->
            current = current.resolve(segment)
            require(!Files.isSymbolicLink(current))
        }
        require(Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
        path.toRealPath(LinkOption.NOFOLLOW_LINKS).toFile()
    }.getOrNull()

private val DEFAULT_TERMINALS =
    listOf(
        "/usr/bin/xdg-terminal-exec",
        "/usr/bin/ptyxis",
        "/usr/bin/gnome-terminal",
        "/usr/bin/konsole",
        "/usr/bin/alacritty",
        "/usr/bin/kitty",
        "/usr/bin/x-terminal-emulator",
        "/usr/bin/xterm",
    )

actual fun createTerminalLauncher(): TerminalLauncher = JvmTerminalLauncher()
