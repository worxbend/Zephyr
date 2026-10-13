package com.worxbend.zephyr.data

data class SdkmanHomeSelectionResult(
    val success: Boolean,
    val path: String? = null,
    val message: String,
)

interface SdkmanHomeConfigurationService {
    suspend fun configuredPath(): String?

    suspend fun chooseAndSave(): SdkmanHomeSelectionResult?

    suspend fun clear(): SdkmanHomeSelectionResult
}

expect fun createSdkmanHomeConfigurationService(): SdkmanHomeConfigurationService
