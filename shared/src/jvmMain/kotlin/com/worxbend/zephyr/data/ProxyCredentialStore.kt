package com.worxbend.zephyr.data

import java.util.Locale

/** Identity includes destination and case-sensitive account, never the enable toggle. */
internal data class ProxyIdentity(val host: String, val port: Int, val username: String) {
    companion object {
        fun of(configuration: ProxyConfiguration): ProxyIdentity = ProxyIdentity(
            configuration.host.trim().lowercase(Locale.ROOT), configuration.port, configuration.username.trim(),
        )
    }
}

/** A fresh binding stages replacement secrets without overwriting the committed credential. */
internal data class ProxyCredentialBinding(val identity: ProxyIdentity, val revision: String) {
    companion object {
        /** Cleanup-only address for pre-identity-binding installs; never an active credential. */
        val Legacy = ProxyCredentialBinding(ProxyIdentity("", 0, ""), "legacy-unbound")
    }
}

internal sealed interface ProxySecretLookup {
    class Found(val secret: String) : ProxySecretLookup {
        override fun toString(): String = "Found([redacted])"
    }
    data object Missing : ProxySecretLookup
    data object Unavailable : ProxySecretLookup
    data object Failed : ProxySecretLookup
}

internal enum class ProxySecretMutation { Succeeded, Unavailable, Failed }

internal interface ProxySecretStore {
    suspend fun read(binding: ProxyCredentialBinding): ProxySecretLookup
    suspend fun write(binding: ProxyCredentialBinding, secret: String): ProxySecretMutation
    suspend fun clear(binding: ProxyCredentialBinding): ProxySecretMutation
}

internal fun ProxySecretLookup.status(): ProxyCredentialStatus = when (this) {
    is ProxySecretLookup.Found -> ProxyCredentialStatus.Found
    ProxySecretLookup.Missing -> ProxyCredentialStatus.Missing
    ProxySecretLookup.Unavailable -> ProxyCredentialStatus.Unavailable
    ProxySecretLookup.Failed -> ProxyCredentialStatus.Failed
}
