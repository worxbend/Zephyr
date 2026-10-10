package com.worxbend.zephyr.data

data class ProxyConfiguration(
    val enabled: Boolean = false,
    val host: String = "",
    val port: Int = 8080,
    val username: String = "",
    val hasStoredPassword: Boolean = false,
    val credentialStatus: ProxyCredentialStatus = if (hasStoredPassword) ProxyCredentialStatus.Found else ProxyCredentialStatus.Missing,
    /** Includes obsolete identity-bound or legacy unbound entries awaiting verified deletion. */
    val pendingCredentialCleanup: Boolean = false,
)

enum class ProxyCredentialStatus { Found, Missing, Unavailable, Failed }

data class ProxySaveResult(
    val success: Boolean,
    val message: String,
    /** False success with settingsSaved means configuration committed but secret cleanup failed. */
    val settingsSaved: Boolean = success,
    /** A secret or settings write may have applied; retry/reload rather than assuming rollback. */
    val recoveryRequired: Boolean = false,
)

interface ProxyConfigurationService {
    suspend fun load(): ProxyConfiguration
    suspend fun save(configuration: ProxyConfiguration, newPassword: String?): ProxySaveResult
    suspend fun clearPassword(): ProxySaveResult
}

expect fun createProxyConfigurationService(): ProxyConfigurationService

fun ProxyConfiguration.validationError(): String? = when {
    host.isBlank() && enabled -> "Proxy host is required."
    host.any { it.isWhitespace() || it.code < 0x20 } -> "Proxy host cannot contain whitespace or control characters."
    host.contains('/') || host.contains('@') || host.contains(':') -> "Enter a hostname without a scheme, path, or credentials."
    port !in 1..65_535 -> "Proxy port must be between 1 and 65535."
    username.any { it.code < 0x20 || it == '\n' || it == '\r' } -> "Proxy username contains invalid characters."
    else -> null
}
