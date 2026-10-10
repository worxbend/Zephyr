package com.worxbend.zephyr

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.worxbend.zephyr.domain.CandidateMetadataStatus
import com.worxbend.zephyr.domain.SdkmanSelfUpdateStatus
import com.worxbend.zephyr.domain.displayNameFor
import com.worxbend.zephyr.settings.AppSettingsStore
import com.worxbend.zephyr.settings.ThemePreference
import com.worxbend.zephyr.settings.reducesMotion
import com.worxbend.zephyr.viewmodel.ZephyrRoute
import com.worxbend.zephyr.viewmodel.ZephyrUiState
import com.worxbend.zephyr.viewmodel.ZephyrViewModel

@Composable
fun App(viewModel: ZephyrViewModel, settingsStore: AppSettingsStore) {
    val state by viewModel.state.collectAsState()
    val settings by settingsStore.state.collectAsState()
    val settingsSaveStatus by settingsStore.saveStatus.collectAsState()
    var systemDarkTheme by remember { mutableStateOf(false) }
    var systemReducedMotion by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        systemDarkTheme = isSystemDarkMode()
        systemReducedMotion = isSystemReducedMotion()
    }
    val darkTheme = when (settings.themePreference) {
        ThemePreference.System -> systemDarkTheme
        ThemePreference.Light -> false
        ThemePreference.Dark -> true
    }

    ZephyrTheme(
        darkTheme = darkTheme,
        density = settings.uiDensity,
        textScale = settings.textScale,
        reducedMotion = settings.motionPreference.reducesMotion(systemReducedMotion),
    ) {
        Surface(Modifier.fillMaxSize()) {
            when (val current = state) {
                ZephyrUiState.Loading -> LoadingScreen()
                is ZephyrUiState.SdkmanMissing -> SdkmanMissingScreen(current.message, viewModel::refreshAll)
                is ZephyrUiState.Ready -> ZephyrScreen(
                    state = current,
                    viewModel = viewModel,
                    settings = settings,
                    onSettingsChange = settingsStore::update,
                    settingsSaveStatus = settingsSaveStatus,
                    onRetrySettingsSave = settingsStore::retry,
                    darkTheme = darkTheme,
                    onToggleTheme = {
                        settingsStore.update {
                            it.copy(themePreference = if (darkTheme) ThemePreference.Light else ThemePreference.Dark)
                        }
                    },
                )
            }
        }
    }
}

@Preview
@Composable
private fun SdkmanMissingPreview() {
    ZephyrTheme(darkTheme = false) {
        Surface(Modifier.fillMaxSize()) {
            SdkmanMissingScreen("SDKMAN was not found.") {}
        }
    }
}

@Composable
private fun LoadingScreen() {
    Box(Modifier.fillMaxSize().safeContentPadding(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            ZephyrProgressIndicator()
            Text("Loading SDKMAN state")
        }
    }
}

@Composable
private fun SdkmanMissingScreen(message: String, onRetry: () -> Unit) {
    Column(
        Modifier.fillMaxSize().safeContentPadding().padding(48.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("SDKMAN Required", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(12.dp))
        Text(
            "Zephyr is a GUI wrapper around SDKMAN and cannot manage JDKs or SDKs until SDKMAN is installed.",
            style = MaterialTheme.typography.bodyLarge,
        )
        Spacer(Modifier.height(12.dp))
        Text(message, color = MaterialTheme.colorScheme.error)
        Spacer(Modifier.height(24.dp))
        CodeBlock("""curl -s "https://get.sdkman.io" | bash""")
        Spacer(Modifier.height(12.dp))
        Text("Restart your terminal or desktop session after installation, then retry.")
        Spacer(Modifier.height(24.dp))
        Button(onClick = onRetry) { Text("Retry") }
    }
}

internal fun headerTitle(state: ZephyrUiState.Ready): String =
    when (val route = state.route) {
        ZephyrRoute.Overview -> "Overview"
        ZephyrRoute.Diagnostics -> "Diagnostics"
        ZephyrRoute.History -> "Task Center"
        ZephyrRoute.Settings -> "Settings"
        ZephyrRoute.About -> "About"
        ZephyrRoute.InstalledJdk -> "Installed JDK"
        ZephyrRoute.InstalledSdks -> "Installed SDKs"
        ZephyrRoute.BrowseJdks -> "Browse JDKs"
        ZephyrRoute.BrowseSdks -> "Browse SDKs"
        ZephyrRoute.LocalOnly -> "Local-only Versions"
        ZephyrRoute.Storage -> "Storage Center"
        ZephyrRoute.UpdateCenter -> "Update Center"
        ZephyrRoute.BatchUninstall -> "Batch Uninstall"
        ZephyrRoute.Profiles -> "Toolchain Profiles"
        ZephyrRoute.ProjectWorkspaces -> "Project Workspaces"
        ZephyrRoute.ProjectImport -> "Project Toolchain Import"
        ZephyrRoute.ProjectExport -> "Project Toolchain Export"
        ZephyrRoute.EnvironmentSnapshot -> "Environment Snapshot"
        ZephyrRoute.Comparison -> "Candidate Comparison"
        is ZephyrRoute.JdkDetail -> state.selectedCandidate?.displayName ?: "JDK"
        is ZephyrRoute.SdkDetail -> state.selectedCandidate?.displayName ?: displayNameFor(route.candidate)
    }

internal fun jdkSubtitle(state: ZephyrUiState.Ready): String {
    val default = state.candidates.firstOrNull { it.name == "java" }?.defaultVersion
    return default?.let { "Default JDK: $it" } ?: "No JDK Installed"
}

internal fun sdkmanVersionLabel(state: ZephyrUiState.Ready): String =
    state.sdkmanStatus.cliVersion ?: "SDKMAN version unknown"

internal fun selfUpdateShortLabel(status: SdkmanSelfUpdateStatus): String =
    when (status) {
        SdkmanSelfUpdateStatus.NotChecked -> "not checked"
        SdkmanSelfUpdateStatus.UpToDate -> "up to date"
        SdkmanSelfUpdateStatus.Updated -> "updated"
        is SdkmanSelfUpdateStatus.Failed -> "failed"
    }

internal fun metadataShortLabel(status: CandidateMetadataStatus): String =
    when (status) {
        CandidateMetadataStatus.NotChecked -> "not checked"
        CandidateMetadataStatus.Refreshing -> "refreshing"
        CandidateMetadataStatus.Refreshed -> "refreshed"
        is CandidateMetadataStatus.Failed -> "failed"
    }
