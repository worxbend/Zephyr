package com.worxbend.zephyr.domain

enum class SdkmanCommandAction(val label: String) {
    Install("Install"),
    Uninstall("Uninstall"),
    SetDefault("Set default"),
    UpdateMetadata("Update metadata"),
    SelfUpdate("Self-update"),
}

data class PlannedSdkmanCommand(
    val action: SdkmanCommandAction,
    val candidate: String? = null,
    val version: String? = null,
) {
    init {
        when (action) {
            SdkmanCommandAction.Install,
            SdkmanCommandAction.Uninstall,
            SdkmanCommandAction.SetDefault,
            -> requireValidTarget(requireNotNull(candidate), requireNotNull(version))
            SdkmanCommandAction.UpdateMetadata,
            SdkmanCommandAction.SelfUpdate,
            -> require(candidate == null && version == null) { "This command does not accept a candidate or version." }
        }
    }
}

sealed interface SdkmanTransaction {
    val title: String
    val description: String
    val confirmationLabel: String
    val destructive: Boolean
    val commands: List<PlannedSdkmanCommand>

    data class Install(
        val candidate: String,
        val version: String,
    ) : SdkmanTransaction {
        init {
            requireValidTarget(candidate, version)
        }

        override val title = "Install $version?"
        override val description = "SDKMAN will download and install $version for ${displayNameFor(candidate)}."
        override val confirmationLabel = "Install"
        override val destructive = false
        override val commands = listOf(PlannedSdkmanCommand(SdkmanCommandAction.Install, candidate, version))
    }

    data class BatchInstall(
        val targets: List<InstallTarget>,
    ) : SdkmanTransaction {
        init {
            require(targets.isNotEmpty()) { "A batch install requires at least one target." }
            targets.forEach { requireValidTarget(it.candidate, it.version) }
            require(targets.distinct().size == targets.size) { "Batch install targets must be unique." }
        }

        override val title = "Install ${targets.size} toolchain items?"
        override val description =
            "Zephyr will install the selected SDKMAN targets sequentially and report each result independently."
        override val confirmationLabel = "Install selected"
        override val destructive = false
        override val commands = targets.map {
            PlannedSdkmanCommand(SdkmanCommandAction.Install, it.candidate, it.version)
        }
    }

    data class SnapshotRestore(
        override val commands: List<PlannedSdkmanCommand>,
    ) : SdkmanTransaction {
        init {
            require(commands.isNotEmpty()) { "A snapshot restore requires at least one step." }
            require(commands.all {
                it.action == SdkmanCommandAction.Install || it.action == SdkmanCommandAction.SetDefault
            }) { "Snapshot restore supports only install and default steps." }
            commands.forEach {
                requireValidTarget(
                    requireNotNull(it.candidate) { "Snapshot restore candidate is required." },
                    requireNotNull(it.version) { "Snapshot restore version is required." },
                )
            }
            require(commands.distinct().size == commands.size) { "Snapshot restore steps must be unique." }
        }

        override val title = "Restore ${commands.size} snapshot step(s)?"
        override val description =
            "Zephyr will run missing installs first, then restore persisted defaults. Completed steps remain applied if a later step fails."
        override val confirmationLabel = "Restore snapshot"
        override val destructive = false
    }

    data class ToolchainActivation(
        val profileName: String,
        override val commands: List<PlannedSdkmanCommand>,
    ) : SdkmanTransaction {
        init {
            require(profileName.isNotBlank()) { "A toolchain activation requires a profile name." }
            require(commands.isNotEmpty()) { "A toolchain activation requires at least one step." }
            require(commands.all {
                it.action == SdkmanCommandAction.Install || it.action == SdkmanCommandAction.SetDefault
            }) { "Toolchain activation supports only install and default steps." }
            commands.forEach {
                requireValidTarget(
                    requireNotNull(it.candidate) { "Toolchain activation candidate is required." },
                    requireNotNull(it.version) { "Toolchain activation version is required." },
                )
            }
            require(commands.distinct().size == commands.size) { "Toolchain activation steps must be unique." }
            val firstDefault = commands.indexOfFirst { it.action == SdkmanCommandAction.SetDefault }
            require(
                firstDefault < 0 ||
                    commands.drop(firstDefault).all { it.action == SdkmanCommandAction.SetDefault },
            ) { "Toolchain activation must run every install before changing defaults." }
        }

        override val title = "Activate “${profileName.trim()}”?"
        override val description =
            "Zephyr will install missing targets first, then align persisted defaults. No installed version will be removed."
        override val confirmationLabel = "Activate profile"
        override val destructive = false
    }

    data class UpdateActivation(
        val targets: List<UpdateActivationTarget>,
    ) : SdkmanTransaction {
        init {
            require(targets.isNotEmpty()) { "An update activation requires at least one target." }
            targets.forEach { requireValidTarget(it.candidate, it.version) }
            require(targets.distinctBy(UpdateActivationTarget::candidate).size == targets.size) {
                "Update activation candidates must be unique."
            }
        }

        override val title = "Activate ${targets.size} stable update(s)?"
        override val description = if (targets.any(UpdateActivationTarget::requiresInstall)) {
            "Zephyr will install missing stable targets first, then make every selected target the persisted SDKMAN default."
        } else {
            "Zephyr will make every selected installed stable target the persisted SDKMAN default. No download is required."
        }
        override val confirmationLabel =
            if (targets.any(UpdateActivationTarget::requiresInstall)) "Install and activate" else "Activate selected"
        override val destructive = false
        override val commands =
            targets
                .filter(UpdateActivationTarget::requiresInstall)
                .distinctBy { it.candidate to it.version }
                .map { PlannedSdkmanCommand(SdkmanCommandAction.Install, it.candidate, it.version) } +
                targets.map {
                    PlannedSdkmanCommand(SdkmanCommandAction.SetDefault, it.candidate, it.version)
                }
    }

    data class Uninstall(
        val candidate: String,
        val version: String,
    ) : SdkmanTransaction {
        init {
            requireValidTarget(candidate, version)
        }

        override val title = "Uninstall $version?"
        override val description = "SDKMAN will remove $version from ${displayNameFor(candidate)}. Reinstalling it may require another download."
        override val confirmationLabel = "Uninstall"
        override val destructive = true
        override val commands = listOf(PlannedSdkmanCommand(SdkmanCommandAction.Uninstall, candidate, version))
    }

    data class BatchUninstall(
        val targets: List<UninstallTarget>,
    ) : SdkmanTransaction {
        init {
            require(targets.isNotEmpty()) { "A batch uninstall requires at least one target." }
            targets.forEach { requireValidTarget(it.candidate, it.version) }
            require(targets.distinct().size == targets.size) { "Batch uninstall targets must be unique." }
        }

        override val title = "Uninstall ${targets.size} versions?"
        override val description =
            "Zephyr will remove the selected non-default, unprotected versions sequentially and report every result."
        override val confirmationLabel = "Uninstall selected"
        override val destructive = true
        override val commands = targets.map {
            PlannedSdkmanCommand(SdkmanCommandAction.Uninstall, it.candidate, it.version)
        }
    }

    data class SetDefault(
        val candidate: String,
        val version: String,
    ) : SdkmanTransaction {
        init {
            requireValidTarget(candidate, version)
        }

        override val title = "Change the default version?"
        override val description = "$version will become the persisted SDKMAN default for ${displayNameFor(candidate)}."
        override val confirmationLabel = "Make default"
        override val destructive = false
        override val commands = listOf(PlannedSdkmanCommand(SdkmanCommandAction.SetDefault, candidate, version))
    }

    data class CleanLocalOnly(
        val candidate: String,
        val versions: List<String>,
    ) : SdkmanTransaction {
        init {
            require(versions.isNotEmpty()) { "A cleanup transaction requires at least one version." }
            require(isValidSdkmanCandidateName(candidate)) { "Invalid SDKMAN candidate name." }
            require(versions.all(::isValidSdkmanVersion)) { "Invalid SDKMAN version identifier." }
            require(versions.distinct().size == versions.size) { "Cleanup versions must be unique." }
        }

        override val title = "Clean local-only versions?"
        override val description =
            "Zephyr will re-verify and remove ${versions.size} local-only version(s) from ${displayNameFor(candidate)}. " +
                "If every installed version is removed, the package may disappear from Installed."
        override val confirmationLabel = "Clean"
        override val destructive = true
        override val commands = versions.map {
            PlannedSdkmanCommand(SdkmanCommandAction.Uninstall, candidate, it)
        }
    }

    data object RefreshMetadata : SdkmanTransaction {
        override val title = "Refresh SDKMAN metadata?"
        override val description = "SDKMAN will update its local candidate metadata before Zephyr reloads the catalog."
        override val confirmationLabel = "Refresh metadata"
        override val destructive = false
        override val commands = listOf(PlannedSdkmanCommand(SdkmanCommandAction.UpdateMetadata))
    }

    data object SelfUpdate : SdkmanTransaction {
        override val title = "Check for SDKMAN updates?"
        override val description = "SDKMAN may replace its own CLI files when a newer version is available."
        override val confirmationLabel = "Check for updates"
        override val destructive = false
        override val commands = listOf(PlannedSdkmanCommand(SdkmanCommandAction.SelfUpdate))
    }
}

data class InstallTarget(
    val candidate: String,
    val version: String,
)

data class UpdateActivationTarget(
    val candidate: String,
    val version: String,
    val requiresInstall: Boolean,
)

data class UninstallTarget(
    val candidate: String,
    val version: String,
)

private fun requireValidTarget(candidate: String, version: String) {
    require(isValidSdkmanCandidateName(candidate)) { "Invalid SDKMAN candidate name." }
    require(isValidSdkmanVersion(version)) { "Invalid SDKMAN version identifier." }
}
