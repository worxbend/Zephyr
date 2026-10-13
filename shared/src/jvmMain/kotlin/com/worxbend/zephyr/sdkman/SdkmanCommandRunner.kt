package com.worxbend.zephyr.sdkman

import com.worxbend.zephyr.domain.ConnectivityDiagnostic
import com.worxbend.zephyr.domain.ConnectivityOutcome
import com.worxbend.zephyr.domain.ConnectivityRouteKind
import kotlin.time.Duration

sealed interface SdkmanCommand {
    data object Version : SdkmanCommand

    data object ListCandidates : SdkmanCommand

    data object UpdateCandidateMetadata : SdkmanCommand

    data object SelfUpdate : SdkmanCommand

    data class ListVersions(
        val candidate: String,
    ) : SdkmanCommand

    data class Install(
        val candidate: String,
        val version: String,
    ) : SdkmanCommand

    data class Uninstall(
        val candidate: String,
        val version: String,
    ) : SdkmanCommand

    data class SetDefault(
        val candidate: String,
        val version: String,
    ) : SdkmanCommand

    data class ConnectivityProbe(
        val route: ConnectivityRouteKind,
    ) : SdkmanCommand
}

interface SdkmanCommandRunner {
    suspend fun run(
        command: SdkmanCommand,
        timeout: Duration,
    ): SdkmanCommandResult

    suspend fun diagnoseConnectivity(timeout: Duration): ConnectivityDiagnostic =
        ConnectivityDiagnostic(
            route = ConnectivityRouteKind.Direct,
            checkedAtEpochMillis = System.currentTimeMillis(),
            latencyMillis = 0,
            outcome = ConnectivityOutcome.Indeterminate,
        )
}

data class SdkmanCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val timedOut: Boolean = false,
) {
    val success: Boolean get() = exitCode == 0
    val output: String get() = listOf(stdout, stderr).filter { it.isNotBlank() }.joinToString("\n")
}
