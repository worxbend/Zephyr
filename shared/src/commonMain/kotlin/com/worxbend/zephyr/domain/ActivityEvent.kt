package com.worxbend.zephyr.domain

enum class ActivitySeverity(
    val label: String,
) {
    Info("Info"),
    Success("Success"),
    Warning("Warning"),
    Error("Error"),
}

enum class ActivityAction(
    val label: String,
) {
    OpenTaskCenter("Open Task Center"),
}

data class ActivityEvent(
    val id: Long,
    val timestampEpochMillis: Long,
    val severity: ActivitySeverity,
    val message: String,
    val action: ActivityAction? = null,
    val acknowledged: Boolean = false,
)
