package com.worxbend.zephyr

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.worxbend.zephyr.domain.InstallTarget
import com.worxbend.zephyr.domain.SdkmanCommandAction
import com.worxbend.zephyr.domain.SdkmanTransaction
import com.worxbend.zephyr.settings.AppSettings
import com.worxbend.zephyr.settings.ToolchainProfile
import com.worxbend.zephyr.viewmodel.ZephyrRoute
import com.worxbend.zephyr.viewmodel.ZephyrUiState
import com.worxbend.zephyr.viewmodel.ZephyrViewModel

@Composable
internal fun ToolchainProfilesScreen(
    state: ZephyrUiState.Ready,
    viewModel: ZephyrViewModel,
    settings: AppSettings,
    onSettingsChange: ((AppSettings) -> AppSettings) -> Unit,
) {
    val metrics = LocalZephyrMetrics.current
    var profileName by remember { mutableStateOf("") }
    val currentDefaults = state.candidates.mapNotNull { candidate ->
        candidate.defaultVersion?.let { InstallTarget(candidate.name, it) }
    }
    val saveProfile: (String) -> Unit = { name ->
        val profile = ToolchainProfile(name.trim(), currentDefaults)
        onSettingsChange {
            it.copy(
                toolchainProfiles = (
                    it.toolchainProfiles.filterNot { existing ->
                        existing.name.equals(profile.name, ignoreCase = true)
                    } + profile
                    ).sortedBy { saved -> saved.name.lowercase() },
            )
        }
    }

    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(metrics.spacing * 2),
    ) {
        PageTitle(
            "Toolchain Profiles",
            "Save named default-version sets, compare them with this machine, and activate the complete environment.",
        )
        ZephyrPanel(Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(metrics.panelPadding),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = profileName,
                    onValueChange = { profileName = it.take(60) },
                    modifier = Modifier.width(300.dp),
                    singleLine = true,
                    label = { Text("Profile name") },
                    placeholder = { Text("Backend, Android, Data…") },
                )
                Column(Modifier.weight(1f)) {
                    Text("Capture current defaults", fontWeight = FontWeight.SemiBold)
                    Text(
                        "${currentDefaults.size} candidate default(s) will be saved.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ZephyrToolbarButton(
                    label = "Save profile",
                    onClick = {
                        saveProfile(profileName)
                        profileName = ""
                    },
                    enabled = profileName.isNotBlank() && currentDefaults.isNotEmpty(),
                )
            }
        }
        if (settings.toolchainProfiles.isEmpty()) {
            EmptyState(
                "No profiles saved",
                if (currentDefaults.isEmpty()) {
                    "Set at least one SDKMAN default before capturing a reusable toolchain profile."
                } else {
                    "Capture the ${currentDefaults.size} current default(s) as a reusable starting point."
                },
                if (currentDefaults.isEmpty()) "Browse SDKs" else "Save Current toolchain",
            ) {
                if (currentDefaults.isEmpty()) {
                    viewModel.navigate(ZephyrRoute.BrowseSdks)
                } else {
                    saveProfile("Current toolchain")
                }
            }
            return@Column
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.spacedBy(metrics.spacing),
        ) {
            items(settings.toolchainProfiles, key = ToolchainProfile::name) { profile ->
                val activationPlan = planToolchainActivation(profile.targets, state.candidates)
                val profileDesiredState = desiredStateFromProfile(profile)
                val isDesiredState = settings.desiredToolchainState == profileDesiredState
                val missingCount = activationPlan.count { it.action == SdkmanCommandAction.Install }
                val defaultChangeCount = activationPlan.count { it.action == SdkmanCommandAction.SetDefault }
                ZephyrPanel(Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(metrics.panelPadding),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(profile.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Text(
                                    when {
                                        activationPlan.isEmpty() -> "${profile.targets.size} target(s) active"
                                        else -> "$missingCount missing • $defaultChangeCount default change(s)"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            if (activationPlan.isNotEmpty()) {
                                ZephyrToolbarButton(
                                    label = "Review activation (${activationPlan.size})",
                                    onClick = {
                                        viewModel.requestTransaction(
                                            SdkmanTransaction.ToolchainActivation(profile.name, activationPlan),
                                        )
                                    },
                                )
                            } else {
                                Badge("Active", BadgeTone.Success)
                            }
                            if (isDesiredState) {
                                Badge("Desired state", BadgeTone.Primary)
                            } else {
                                ZephyrToolbarButton(
                                    label = "Use as desired",
                                    onClick = {
                                        onSettingsChange {
                                            it.copy(desiredToolchainState = profileDesiredState)
                                        }
                                    },
                                )
                            }
                            ZephyrToolbarButton(
                                label = "Delete profile",
                                onClick = {
                                    onSettingsChange {
                                        it.copy(toolchainProfiles = it.toolchainProfiles - profile)
                                    }
                                },
                            )
                        }
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(7.dp),
                            verticalArrangement = Arrangement.spacedBy(7.dp),
                        ) {
                            profile.targets.forEach { target ->
                                val current = state.candidates.firstOrNull { it.name == target.candidate }
                                val installed = current
                                    ?.installedVersions
                                    .orEmpty()
                                    .any { it.isInstalled && it.version == target.version }
                                val isDefault = current?.defaultVersion == target.version
                                Badge(
                                    "${target.candidate} ${target.version}",
                                    when {
                                        isDefault -> BadgeTone.Success
                                        !installed -> BadgeTone.Warning
                                        else -> BadgeTone.Primary
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
