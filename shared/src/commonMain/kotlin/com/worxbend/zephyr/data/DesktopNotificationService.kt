package com.worxbend.zephyr.data

interface DesktopNotificationService {
    fun show(
        title: String,
        message: String,
    ): Boolean
}

expect fun createDesktopNotificationService(): DesktopNotificationService
