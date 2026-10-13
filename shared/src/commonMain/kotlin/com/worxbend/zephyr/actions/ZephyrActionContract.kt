package com.worxbend.zephyr.actions

const val ZEPHYR_ACTION_CONTRACT_VERSION = 1

object ZephyrActionIds {
    const val REFRESH_INSTALLED = "zephyr.read.refresh-installed"
    const val SCAN_LOCAL_ONLY = "zephyr.read.scan-local-only"
    const val REFRESH_CONNECTIVITY = "zephyr.read.refresh-connectivity"
    const val RUN_DIAGNOSTICS = "zephyr.read.run-connection-diagnostics"
    const val REFRESH_METADATA = "zephyr.review.refresh-metadata"
    const val CHECK_SDKMAN_UPDATES = "zephyr.review.check-sdkman-updates"

    // Keep the original version-1 public constant names source-compatible.
    @Suppress("ktlint:standard:property-naming")
    const val RefreshInstalled = REFRESH_INSTALLED

    @Suppress("ktlint:standard:property-naming")
    const val ScanLocalOnly = SCAN_LOCAL_ONLY

    @Suppress("ktlint:standard:property-naming")
    const val RefreshConnectivity = REFRESH_CONNECTIVITY

    @Suppress("ktlint:standard:property-naming")
    const val RunDiagnostics = RUN_DIAGNOSTICS

    @Suppress("ktlint:standard:property-naming")
    const val RefreshMetadata = REFRESH_METADATA

    @Suppress("ktlint:standard:property-naming")
    const val CheckSdkmanUpdates = CHECK_SDKMAN_UPDATES
}

data class ZephyrActionRequest(
    val id: String,
    val contractVersion: Int = ZEPHYR_ACTION_CONTRACT_VERSION,
    val parameters: Map<String, String> = emptyMap(),
)

data class ZephyrActionDescriptor(
    val id: String,
    val title: String,
    val description: String,
    val requiresReview: Boolean,
    val allowedParameters: Set<String> = emptySet(),
)

val ZEPHYR_ACTIONS: List<ZephyrActionDescriptor> =
    listOf(
        ZephyrActionDescriptor(ZephyrActionIds.REFRESH_INSTALLED, "Refresh installed state", "Reload local SDKMAN candidates.", false),
        ZephyrActionDescriptor(
            ZephyrActionIds.SCAN_LOCAL_ONLY,
            "Scan local-only versions",
            "Compare local versions with SDKMAN metadata.",
            false,
        ),
        ZephyrActionDescriptor(ZephyrActionIds.REFRESH_CONNECTIVITY, "Check connectivity", "Refresh SDKMAN service reachability.", false),
        ZephyrActionDescriptor(
            ZephyrActionIds.RUN_DIAGNOSTICS,
            "Run connection diagnostics",
            "Run a read-only route-aware SDKMAN connection diagnostic.",
            false,
        ),
        ZephyrActionDescriptor(ZephyrActionIds.REFRESH_METADATA, "Refresh metadata", "Open review for an SDKMAN metadata update.", true),
        ZephyrActionDescriptor(ZephyrActionIds.CHECK_SDKMAN_UPDATES, "Check SDKMAN updates", "Open review for SDKMAN self-update.", true),
    )

fun ZephyrActionRequest.validationError(): String? {
    if (contractVersion != ZEPHYR_ACTION_CONTRACT_VERSION) return "Unsupported action contract version."
    val descriptor = ZEPHYR_ACTIONS.firstOrNull { it.id == id } ?: return "Unknown action ID."
    if (!parameters.keys.all { it in descriptor.allowedParameters }) return "Action contains unsupported parameters."
    if (parameters.values.any { value -> value.length > 512 || value.any { it.code < 0x20 } }) {
        return "Action parameter contains invalid content."
    }
    return null
}

fun interface ZephyrActionHandler {
    fun handle(request: ZephyrActionRequest): Boolean
}
