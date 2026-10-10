package com.worxbend.zephyr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.worxbend.zephyr.features.settings.SettingsPresenter
import com.worxbend.zephyr.settings.AppSettings
import com.worxbend.zephyr.settings.CleanupGracePeriod
import com.worxbend.zephyr.settings.MetadataRefreshSchedule
import com.worxbend.zephyr.settings.MotionPreference
import com.worxbend.zephyr.settings.OperationNotificationPolicy
import com.worxbend.zephyr.settings.TextScale
import com.worxbend.zephyr.settings.ThemePreference
import com.worxbend.zephyr.settings.UiDensity
import com.worxbend.zephyr.settings.UpdateNotificationPolicy

@Composable
internal fun SettingsScreen(
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
    settingsSaveStatus: com.worxbend.zephyr.settings.SettingsSaveStatus? = null,
    onRetrySettingsSave: () -> Unit = {},
) {
    val services = LocalAppServices.current
    val scope = rememberCoroutineScope()
    val presenter = remember(services) {
        SettingsPresenter(services.proxyConfigurationService, services.sdkmanHomeConfigurationService, services.portablePreferencesService, scope)
    }
    DisposableEffect(presenter) { onDispose(presenter::close) }
    val workflow by presenter.state.collectAsState()
    val proxyConfiguration = workflow.proxy
    val proxyPort = workflow.port
    val proxyMessage = workflow.proxyMessage
    val customSdkmanHome = workflow.customSdkmanHome
    val sdkmanHomeMessage = workflow.sdkmanHomeMessage
    val portablePreferencesMessage = workflow.portablePreferencesMessage
    var proxyPassword by remember { mutableStateOf("") }
    ZephyrScrollPane(
        modifier = Modifier
            .fillMaxHeight()
            .widthIn(max = 920.dp)
            .fillMaxWidth(),
        spacing = 28.dp,
    ) {
        PageTitle("Settings", "Personalize Zephyr. Save status tracks persistence for this desktop user.")
        settingsSaveStatus?.let { status ->
            val description = when (status) {
                com.worxbend.zephyr.settings.SettingsSaveStatus.Loading -> "Loading saved settings…"
                is com.worxbend.zephyr.settings.SettingsSaveStatus.Dirty -> "Settings have unsaved changes."
                is com.worxbend.zephyr.settings.SettingsSaveStatus.Saving -> "Saving settings…"
                is com.worxbend.zephyr.settings.SettingsSaveStatus.Saved -> "Settings saved."
                is com.worxbend.zephyr.settings.SettingsSaveStatus.Failed -> when (status.reason) {
                    com.worxbend.zephyr.settings.SettingsFailureReason.Load -> "Saved settings could not be loaded. Existing persisted settings have not been replaced."
                    com.worxbend.zephyr.settings.SettingsFailureReason.Save -> "Settings have unsaved changes. Saving failed; changes are not durably confirmed."
                    com.worxbend.zephyr.settings.SettingsFailureReason.Transform -> "The settings change could not be applied."
                }
            }
            Text(description, style = MaterialTheme.typography.bodySmall)
            if (status is com.worxbend.zephyr.settings.SettingsSaveStatus.Failed) {
                ZephyrToolbarButton("Retry settings save", onClick = onRetrySettingsSave)
            }
        }
        if (!workflow.loaded && !workflow.busy) {
            ZephyrToolbarButton("Retry loading service settings", onClick = presenter::load)
        }
        SettingsGroup("Appearance", "Workbench colors and information density") {
            ZephyrSettingsRow(
                title = "Theme",
                description = "Follow the Linux desktop or use an explicit light or dark theme.",
            ) {
                ZephyrSegmentedControl(
                    options = ThemePreference.entries,
                    selected = settings.themePreference,
                    label = ThemePreference::label,
                    onSelected = { selected -> onSettingsChange { it.copy(themePreference = selected) } },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ZephyrSettingsRow(
                title = "UI density",
                description = "Compact fits more information; Comfortable adds spacing and larger controls.",
            ) {
                ZephyrSegmentedControl(
                    options = UiDensity.entries,
                    selected = settings.uiDensity,
                    label = UiDensity::label,
                    onSelected = { selected -> onSettingsChange { it.copy(uiDensity = selected) } },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ZephyrSettingsRow(
                title = "Text scale",
                description = "Scale all application text and fixed-height controls from 100% to 200%.",
            ) {
                ZephyrSegmentedControl(
                    options = TextScale.entries,
                    selected = settings.textScale,
                    label = TextScale::label,
                    onSelected = { selected -> onSettingsChange { it.copy(textScale = selected) } },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ZephyrSettingsRow(
                title = "Motion",
                description = "Follow the desktop preference or explicitly use full or reduced motion.",
            ) {
                ZephyrSegmentedControl(
                    options = MotionPreference.entries,
                    selected = settings.motionPreference,
                    label = MotionPreference::label,
                    onSelected = { selected -> onSettingsChange { it.copy(motionPreference = selected) } },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ZephyrSettingsRow(
                title = "Navigation width",
                description = if (settings.navigationWidthDp == 0) {
                    "Using the density-aware default. Drag the sidebar divider to resize."
                } else {
                    "${settings.navigationWidthDp} dp. Drag the sidebar divider to resize."
                },
            ) {
                ZephyrToolbarButton(
                    label = "Reset width",
                    onClick = { onSettingsChange { it.copy(navigationWidthDp = 0) } },
                    enabled = settings.navigationWidthDp != 0,
                )
            }
        }
        SettingsGroup("Automation", "Opt-in background metadata maintenance") {
            ZephyrSettingsRow(
                title = "Metadata refresh",
                description = "Refresh the SDKMAN catalog only while Zephyr is open and idle.",
            ) {
                ZephyrSegmentedControl(
                    options = MetadataRefreshSchedule.entries,
                    selected = settings.metadataRefreshSchedule,
                    label = MetadataRefreshSchedule::label,
                    onSelected = { selected ->
                        onSettingsChange { it.copy(metadataRefreshSchedule = selected) }
                    },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ZephyrSettingsRow(
                title = "Update notifications",
                description = "Show path-free desktop notices for available updates or every completed check.",
            ) {
                ZephyrSegmentedControl(
                    options = UpdateNotificationPolicy.entries,
                    selected = settings.updateNotificationPolicy,
                    label = UpdateNotificationPolicy::label,
                    onSelected = { selected ->
                        onSettingsChange { it.copy(updateNotificationPolicy = selected) }
                    },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ZephyrSettingsRow(
                title = "Operation notifications",
                description = "Show path-free desktop notices when reviewed operations finish.",
            ) {
                ZephyrSegmentedControl(
                    options = OperationNotificationPolicy.entries,
                    selected = settings.operationNotificationPolicy,
                    label = OperationNotificationPolicy::label,
                    onSelected = { selected ->
                        onSettingsChange { it.copy(operationNotificationPolicy = selected) }
                    },
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            ZephyrSettingsRow(
                title = "Local-only grace period",
                description = "Flag versions for review after this age; Zephyr never deletes them automatically.",
            ) {
                ZephyrSegmentedControl(
                    options = CleanupGracePeriod.entries,
                    selected = settings.cleanupGracePeriod,
                    label = CleanupGracePeriod::label,
                    onSelected = { selected ->
                        onSettingsChange { it.copy(cleanupGracePeriod = selected) }
                    },
                )
            }
        }
        SettingsGroup("Privacy", "Control machine-specific information in the application chrome") {
            ZephyrSettingsRow(
                title = "Show SDKMAN home path",
                description = "Display the local SDKMAN path in the toolbar and status bar.",
            ) {
                ZephyrToggle(
                    checked = settings.showSdkmanHome,
                    onCheckedChange = { visible -> onSettingsChange { it.copy(showSdkmanHome = visible) } },
                )
            }
        }
        SettingsGroup(
            "SDKMAN installation",
            "Choose an explicit SDKMAN home only when automatic discovery is not appropriate.",
        ) {
            KeyValueRow("Active after restart", customSdkmanHome ?: "Automatic discovery")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ZephyrToolbarButton(
                    label = "Choose SDKMAN home…",
                    enabled = !workflow.busy,
                    onClick = { presenter.chooseHome() },
                )
                ZephyrToolbarButton(
                    label = "Use automatic discovery",
                    onClick = { presenter.clearHome() },
                    enabled = !workflow.busy && customSdkmanHome != null,
                )
            }
            sdkmanHomeMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                "Selection is accepted only when bin/sdkman-init.sh and candidates/ are present.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        SettingsGroup(
            "Enterprise proxy",
            "Coordinates stay in preferences; passwords use Linux Secret Service and never enter Zephyr settings.",
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = proxyConfiguration.enabled,
                    onCheckedChange = { presenter.editProxy { proxy -> proxy.copy(enabled = it) } },
                )
                Text("Use proxy for SDKMAN network commands", modifier = Modifier.weight(1f))
            }
            if (proxyConfiguration.hasStoredPassword) {
                Badge("Password stored securely", BadgeTone.Success)
            }
            when (proxyConfiguration.credentialStatus) {
                com.worxbend.zephyr.data.ProxyCredentialStatus.Unavailable -> Badge("Credential service unavailable", BadgeTone.Warning)
                com.worxbend.zephyr.data.ProxyCredentialStatus.Failed -> Badge("Credential lookup failed", BadgeTone.Warning)
                else -> Unit
            }
            if (proxyConfiguration.pendingCredentialCleanup) {
                Badge("Credential cleanup requires retry", BadgeTone.Warning)
            }
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = proxyConfiguration.host,
                    onValueChange = { presenter.editProxy { proxy -> proxy.copy(host = it.take(255)) } },
                    label = { Text("Host") },
                    singleLine = true,
                    modifier = Modifier.width(280.dp * zephyrContentScale()),
                )
                OutlinedTextField(
                    value = proxyPort,
                    onValueChange = { presenter.editPort(it) },
                    label = { Text("Port") },
                    singleLine = true,
                    modifier = Modifier.width(120.dp * zephyrContentScale()),
                )
                OutlinedTextField(
                    value = proxyConfiguration.username,
                    onValueChange = { presenter.editProxy { proxy -> proxy.copy(username = it.take(128)) } },
                    label = { Text("Username (optional)") },
                    singleLine = true,
                    modifier = Modifier.width(280.dp * zephyrContentScale()),
                )
                OutlinedTextField(
                    value = proxyPassword,
                    onValueChange = { proxyPassword = it.take(512) },
                    label = { Text(if (proxyConfiguration.hasStoredPassword) "New password (optional)" else "Password (optional)") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.width(280.dp * zephyrContentScale()),
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ZephyrToolbarButton(
                    label = "Save proxy",
                    enabled = workflow.loaded && !workflow.busy,
                    onClick = { presenter.saveProxy(proxyPassword.takeIf(String::isNotEmpty)); proxyPassword = "" },
                )
                ZephyrToolbarButton(
                    label = "Clear password",
                    onClick = { presenter.clearPassword() },
                    enabled = !workflow.busy && (proxyConfiguration.hasStoredPassword || proxyConfiguration.pendingCredentialCleanup || proxyConfiguration.credentialStatus == com.worxbend.zephyr.data.ProxyCredentialStatus.Failed),
                )
            }
            proxyMessage?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        SettingsGroup(
            "Portable preferences",
            "Move non-sensitive appearance, workflow, favorites, profiles, and filter choices between Zephyr installations.",
        ) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ZephyrToolbarButton(
                    label = "Export preferences…",
                    enabled = !workflow.busy,
                    onClick = { presenter.exportPreferences(settings) },
                )
                ZephyrToolbarButton(
                    label = "Import preferences…",
                    enabled = !workflow.busy,
                    onClick = { presenter.importPreferences(onSettingsChange) },
                )
            }
            Text(
                "Excluded by design: machine paths, proxy settings and passwords, local observations, caches, and operation history.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            portablePreferencesMessage?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SettingsGroup("Keyboard shortcuts", "Use Zephyr without leaving the keyboard") {
            keyboardShortcutHelp.forEach { shortcut ->
                ZephyrRecordLayout(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    content = { Text(shortcut.description, style = MaterialTheme.typography.bodyMedium) },
                    actions = { ZephyrKeycap(shortcut.keys) },
                )
            }
        }
    }
}

@Composable
private fun SettingsGroup(
    title: String,
    description: String,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PanelHeading(title, description)
        ZephyrPanel(Modifier.fillMaxWidth()) {
            Column(
                Modifier.fillMaxWidth().padding(LocalZephyrMetrics.current.panelPadding),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                content = content,
            )
        }
    }
}
