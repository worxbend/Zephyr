package com.worxbend.zephyr.data

import java.io.File

internal class JvmDesktopNotificationService(
    private val executable: File = File("/usr/bin/notify-send"),
) : DesktopNotificationService {
    override fun show(
        title: String,
        message: String,
    ): Boolean {
        if (!executable.canExecute()) return false
        return runCatching {
            ProcessBuilder(
                executable.absolutePath,
                "--app-name=Zephyr",
                "--icon=dialog-information",
                title,
                message,
            ).redirectErrorStream(true)
                .start()
            true
        }.getOrDefault(false)
    }
}

actual fun createDesktopNotificationService(): DesktopNotificationService = JvmDesktopNotificationService()
