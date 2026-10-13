package com.worxbend.zephyr.domain

fun PlannedSdkmanCommand.copyableCommand(): String =
    when (action) {
        SdkmanCommandAction.Install -> "sdk install ${candidate.requireField()} ${version.requireField()}"
        SdkmanCommandAction.Uninstall -> "sdk uninstall ${candidate.requireField()} ${version.requireField()}"
        SdkmanCommandAction.SetDefault -> "sdk default ${candidate.requireField()} ${version.requireField()}"
        SdkmanCommandAction.UpdateMetadata -> "sdk update"
        SdkmanCommandAction.SelfUpdate -> "sdk selfupdate"
    }

private fun String?.requireField(): String = requireNotNull(this) { "The planned SDKMAN command is missing a validated field." }
