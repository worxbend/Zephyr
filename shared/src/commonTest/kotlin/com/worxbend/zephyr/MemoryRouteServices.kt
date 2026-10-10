package com.worxbend.zephyr

import com.worxbend.zephyr.data.*
import com.worxbend.zephyr.domain.*
import com.worxbend.zephyr.settings.*

/** Never opens dialogs, vaults, preferences, terminals or an SDKMAN home. */
internal class MemoryRouteServices {
    var configuration = ProxyConfiguration(host = "memory-proxy")
    var proxyLoadCount = 0
    var proxySaveCount = 0
    var home: String? = "memory-home"
    var clearHomeResult = SdkmanHomeSelectionResult(false, message = "Clear failed")
    var portable = AppSettings(themePreference = ThemePreference.Dark).portablePreferences()
    var project = SdkmanRcDocument("memory.sdkmanrc", listOf(InstallTarget("gradle", "9.0")), emptyList())
    var projectChooseCount = 0
    var projectWrites = emptyList<InstallTarget>()
    var snapshot = EnvironmentSnapshot(capturedAtEpochMillis = 42, candidates = listOf(SnapshotCandidate("gradle", "9.0", listOf("9.0"))))
    var snapshotWrites = emptyList<EnvironmentSnapshot>()
    var proxyLoad: (suspend () -> ProxyConfiguration)? = null
    var chooseProject: (suspend () -> SdkmanRcDocument?)? = null
    var readWorkspace: (suspend (ProjectWorkspaceReference) -> ProjectWorkspaceDocument)? = null

    val proxy = object : ProxyConfigurationService {
        override suspend fun load(): ProxyConfiguration { proxyLoadCount++; return proxyLoad?.invoke() ?: configuration }
        override suspend fun save(configuration: ProxyConfiguration, newPassword: String?): ProxySaveResult {
            proxySaveCount++
            this@MemoryRouteServices.configuration = configuration
            return ProxySaveResult(true, "Proxy saved in memory")
        }
        override suspend fun clearPassword(): ProxySaveResult {
            configuration = configuration.copy(hasStoredPassword = false)
            return ProxySaveResult(true, "Password cleared in memory")
        }
    }
    val sdkmanHome = object : SdkmanHomeConfigurationService {
        override suspend fun configuredPath() = home
        override suspend fun chooseAndSave() = SdkmanHomeSelectionResult(true, "chosen-memory-home", "Selected in memory")
        override suspend fun clear() = clearHomeResult
    }
    val preferences = object : PortablePreferencesService {
        override suspend fun chooseAndRead() = portable
        override suspend fun chooseAndWrite(preferences: PortablePreferences) = "memory-preferences"
    }
    val projects = object : ProjectToolchainService {
        override suspend fun chooseAndRead(): SdkmanRcDocument? {
            projectChooseCount++
            val chooser = chooseProject
            return if (chooser != null) chooser() else project
        }
        override suspend fun chooseAndWrite(targets: List<InstallTarget>): SdkmanRcExportResult {
            projectWrites = targets
            return SdkmanRcExportResult("memory.sdkmanrc", targets.size)
        }
        override suspend fun chooseWorkspace(): ProjectWorkspaceDocument? = null
        override suspend fun readWorkspace(reference: ProjectWorkspaceReference): ProjectWorkspaceDocument =
            readWorkspace?.invoke(reference) ?: ProjectWorkspaceDocument(reference, "memory-project", project)
    }
    val snapshots = object : EnvironmentSnapshotService {
        override suspend fun chooseAndRead() = snapshot
        override suspend fun chooseAndWrite(snapshot: EnvironmentSnapshot): EnvironmentSnapshotExportResult {
            snapshotWrites = snapshotWrites + snapshot
            return EnvironmentSnapshotExportResult("memory-snapshot", snapshot.candidates.size, snapshot.candidates.sumOf { it.installedVersions.size })
        }
    }
    val terminal = object : TerminalLauncher {
        override fun launch(sdkmanHome: String, candidate: String, version: String) = TerminalLaunchResult(true, "Memory terminal")
        override fun launchWorkspace(sdkmanHome: String, workingDirectory: String, targets: List<InstallTarget>) = TerminalLaunchResult(true, "Memory workspace terminal")
    }
    val services = AppServices(
        browserLauncher = object : BrowserLauncher { override fun openHttps(url: String) = true },
        clipboardService = object : ClipboardService { override fun copy(text: String) = true },
        terminalLauncher = terminal,
        projectToolchainService = projects,
        environmentSnapshotService = snapshots,
        proxyConfigurationService = proxy,
        sdkmanHomeConfigurationService = sdkmanHome,
        portablePreferencesService = preferences,
    )
}

internal fun memoryReady(candidates: List<Candidate> = emptyList()) = com.worxbend.zephyr.viewmodel.ZephyrUiState.Ready(
    sdkmanStatus = SdkmanStatus(true, "memory-home"),
    route = com.worxbend.zephyr.viewmodel.ZephyrRoute.Settings,
    previousRoute = null, candidates = candidates, catalog = emptyList(), selectedCandidate = null,
    isRefreshing = false, isCatalogLoading = false, localOnlyScanInProgress = false, errorMessage = null, lastOutcome = null,
)

internal fun memoryLocalOnlyCandidate() = Candidate(
    name = "gradle", displayName = "Gradle", kind = CandidateKind.Sdk,
    installedVersions = listOf("default", "removable", "protected").map { CandidateVersion(it, true, it == "default", RemoteAvailability.LocalOnly) },
    defaultVersion = "default", hasLocalOnlyVersions = true, localOnlyVersionCount = 3,
    localOnlyVersions = listOf("default", "removable", "protected"), remoteEvidence = RemoteEvidenceState.LiveComplete,
)
