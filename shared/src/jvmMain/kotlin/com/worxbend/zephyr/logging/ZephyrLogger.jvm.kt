package com.worxbend.zephyr.logging

internal actual fun logWarning(
    message: String,
    throwable: Throwable?,
) {
    System.err.println("[Zephyr][WARN] $message")
    throwable?.printStackTrace(System.err)
}

internal actual fun logError(
    message: String,
    throwable: Throwable?,
) {
    System.err.println("[Zephyr][ERROR] $message")
    throwable?.printStackTrace(System.err)
}
