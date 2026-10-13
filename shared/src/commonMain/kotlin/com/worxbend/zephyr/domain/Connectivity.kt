package com.worxbend.zephyr.domain

enum class ConnectivityState(
    val label: String,
) {
    Unknown("Unknown"),
    Checking("Checking"),
    Online("Online"),
    Offline("Offline"),
}

enum class ConnectivityRouteKind(
    val label: String,
) {
    Direct("Direct"),
    Proxy("Proxy"),
}

enum class ConnectivityOutcome(
    val label: String,
) {
    Online("Online"),
    ProxyAuthentication("Proxy authentication required"),
    Tls("TLS failure"),
    Timeout("Timed out"),
    Service("Service unavailable"),
    Indeterminate("Could not determine"),
}

data class ConnectivityDiagnostic(
    val route: ConnectivityRouteKind,
    val checkedAtEpochMillis: Long,
    val latencyMillis: Long,
    val outcome: ConnectivityOutcome,
) {
    init {
        require(checkedAtEpochMillis >= 0) { "Checked time must not be negative." }
        require(latencyMillis in 0..MAX_CONNECTIVITY_LATENCY_MILLIS) {
            "Connectivity latency must be bounded."
        }
    }

    val isOnline: Boolean get() = outcome == ConnectivityOutcome.Online
}

data class ConnectivityStatus(
    val state: ConnectivityState,
    val diagnostic: ConnectivityDiagnostic? = null,
) {
    init {
        require(
            when (state) {
                ConnectivityState.Online -> diagnostic?.isOnline == true
                ConnectivityState.Offline -> diagnostic?.isOnline == false
                ConnectivityState.Unknown -> diagnostic == null
                ConnectivityState.Checking -> true
            },
        ) { "Connectivity state must match its safe diagnostic." }
    }

    val checkedAtEpochMillis: Long? get() = diagnostic?.checkedAtEpochMillis
    val detail: String? get() = diagnostic?.outcome?.label

    companion object {
        fun from(diagnostic: ConnectivityDiagnostic): ConnectivityStatus =
            ConnectivityStatus(
                state = if (diagnostic.isOnline) ConnectivityState.Online else ConnectivityState.Offline,
                diagnostic = diagnostic,
            )
    }
}

fun boundedConnectivityLatencyMillis(elapsedMillis: Long): Long = elapsedMillis.coerceIn(0, MAX_CONNECTIVITY_LATENCY_MILLIS)

const val MAX_CONNECTIVITY_LATENCY_MILLIS: Long = 120_000

val SdkmanTransaction.requiresNetwork: Boolean
    get() =
        when (this) {
            is SdkmanTransaction.Install,
            is SdkmanTransaction.BatchInstall,
            is SdkmanTransaction.CleanLocalOnly,
            SdkmanTransaction.RefreshMetadata,
            SdkmanTransaction.SelfUpdate,
            -> {
                true
            }

            is SdkmanTransaction.SnapshotRestore -> {
                commands.any { it.action == SdkmanCommandAction.Install }
            }

            is SdkmanTransaction.ToolchainActivation -> {
                commands.any { it.action == SdkmanCommandAction.Install }
            }

            is SdkmanTransaction.UpdateActivation -> {
                commands.any { it.action == SdkmanCommandAction.Install }
            }

            is SdkmanTransaction.Uninstall,
            is SdkmanTransaction.BatchUninstall,
            is SdkmanTransaction.SetDefault,
            -> {
                false
            }
        }
