package com.worxbend.zephyr.logging

object ZephyrLogger {
    fun warn(
        message: String,
        throwable: Throwable? = null,
    ) = logWarning(message, throwable)

    fun error(
        message: String,
        throwable: Throwable? = null,
    ) = logError(message, throwable)
}

internal expect fun logWarning(
    message: String,
    throwable: Throwable?,
)

internal expect fun logError(
    message: String,
    throwable: Throwable?,
)
