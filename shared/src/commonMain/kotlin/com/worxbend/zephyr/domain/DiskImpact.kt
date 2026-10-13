package com.worxbend.zephyr.domain

enum class DiskImpactKind {
    Required,
    Reclaimable,
    None,
    Unknown,
}

enum class EstimateConfidence(
    val label: String,
) {
    Exact("Exact"),
    Estimated("Estimated"),
    Unknown("Unknown"),
}

data class DiskImpactEstimate(
    val kind: DiskImpactKind,
    val bytes: Long? = null,
    val availableBytes: Long? = null,
    val confidence: EstimateConfidence,
    val explanation: String,
) {
    init {
        require(bytes == null || bytes >= 0) { "Disk impact bytes cannot be negative." }
        require(availableBytes == null || availableBytes >= 0) { "Available disk bytes cannot be negative." }
    }
}

fun formatByteSize(bytes: Long): String {
    if (bytes < 1_024) return "$bytes B"
    val units = listOf("KiB", "MiB", "GiB", "TiB")
    var value = bytes.toDouble()
    var unit = -1
    while (value >= 1_024 && unit < units.lastIndex) {
        value /= 1_024
        unit += 1
    }
    val rounded =
        if (value >= 100) {
            value.toLong().toString()
        } else {
            val tenths = (value * 10).toLong()
            "${tenths / 10}.${tenths % 10}"
        }
    return "$rounded ${units[unit]}"
}
