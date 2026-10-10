package com.worxbend.zephyr.data

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Base64
import java.util.UUID
import java.util.prefs.Preferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Coordinates a single settings snapshot and identity-bound staged secrets. Not a cross-process transaction. */
internal class JvmProxyConfigurationService(
    private val preferences: Preferences = Preferences.userNodeForPackage(JvmProxyConfigurationService::class.java),
    private val secrets: ProxySecretStore = SecretToolProxyStore(),
) : ProxyConfigurationService {
    override suspend fun load(): ProxyConfiguration = withContext(Dispatchers.IO) {
        lock.withLock {
            val snapshot = readSnapshot()
            val lookup = snapshot.binding?.let { secrets.read(it) } ?: ProxySecretLookup.Missing
            snapshot.configuration.copy(
                hasStoredPassword = lookup is ProxySecretLookup.Found, credentialStatus = lookup.status(),
                pendingCredentialCleanup = snapshot.pendingCleanup.isNotEmpty(),
            )
        }
    }

    override suspend fun save(configuration: ProxyConfiguration, newPassword: String?): ProxySaveResult =
        withContext(Dispatchers.IO) { lock.withLock { saveLocked(configuration, newPassword) } }

    private suspend fun saveLocked(configuration: ProxyConfiguration, newPassword: String?): ProxySaveResult {
        configuration.validationError()?.let { return ProxySaveResult(false, it) }
        if (!newPassword.isNullOrEmpty() && configuration.host.isBlank()) {
            return ProxySaveResult(false, "Proxy host is required to bind a password to its destination.")
        }
        val normalized = configuration.copy(host = configuration.host.trim(), username = configuration.username.trim())
        val previous = try { readSnapshot() } catch (_: Exception) {
            return ProxySaveResult(false, "Proxy settings could not be read; no changes were made.")
        }
        val previousEncoded = preferences.get(SNAPSHOT, null)
        val identity = ProxyIdentity.of(normalized)
        val replacement = newPassword?.takeIf { it.isNotEmpty() }
        val binding = if (replacement != null) ProxyCredentialBinding(identity, UUID.randomUUID().toString())
            else previous.binding?.takeIf { it.identity == identity }
        val obsolete = previous.binding?.takeIf { it != binding }
        val next = Snapshot(normalized, binding, (previous.pendingCleanup + listOfNotNull(obsolete)).distinct())
        val intent = if (replacement != null && binding != null)
            previous.copy(pendingCleanup = (previous.pendingCleanup + binding).distinct()) else previous
        // Validate both complete records before a setting or secret is changed.
        try { encode(next); encode(intent) } catch (_: Exception) {
            return ProxySaveResult(false, "Proxy settings exceed the supported storage limit.")
        }
        var staged: ProxyCredentialBinding? = null
        var committed = false
        try {
            if (replacement != null && binding != null) {
                // Persist a cleanup-only address first so crashes/ambiguous writes remain recoverable.
                persist(intent)
                staged = binding
                val written = secrets.write(binding, replacement)
                val verified = if (written == ProxySecretMutation.Succeeded) secrets.read(binding) else null
                check(verified is ProxySecretLookup.Found && verified.secret == replacement)
            }
            persist(next)
            committed = true
            val remaining = next.pendingCleanup.filterNot { deleteVerified(it) }
            if (remaining.isNotEmpty()) {
                // Initial commit retains every cleanup address until absence is verified.
                return ProxySaveResult(false, "Proxy settings saved, but previous password clearance could not be verified.", settingsSaved = true, recoveryRequired = true)
            }
            if (next.pendingCleanup.isNotEmpty()) persist(next.copy(pendingCleanup = emptyList()))
            return ProxySaveResult(true, "Proxy configuration saved.")
        } catch (cancelled: CancellationException) {
            if (!committed) withContext(NonCancellable) { rollback(previous, previousEncoded, staged) }
            throw cancelled
        } catch (_: Exception) {
            val restored = if (!committed) rollback(previous, previousEncoded, staged) else false
            return ProxySaveResult(
                false, if (committed) "Proxy settings saved, but secret cleanup needs retry."
                else "Proxy update could not be verified; settings were restored where possible. Reload before retrying.",
                settingsSaved = committed, recoveryRequired = !restored,
            )
        }
    }

    private suspend fun rollback(previous: Snapshot, previousEncoded: String?, staged: ProxyCredentialBinding?): Boolean {
        val cleaned = staged == null || deleteVerified(staged)
        val restored = try {
            if (cleaned) {
                if (previousEncoded == null) preferences.remove(SNAPSHOT) else preferences.put(SNAPSHOT, previousEncoded)
                preferences.flush()
                check(preferences.get(SNAPSHOT, null) == previousEncoded)
            } else {
                // Keep the previous active identity and a cleanup-only staging address.
                persist(previous.copy(pendingCleanup = (previous.pendingCleanup + listOfNotNull(staged)).distinct()))
            }
            true
        } catch (_: Exception) { false }
        return cleaned && restored
    }

    private fun persist(snapshot: Snapshot) {
        val encoded = encode(snapshot)
        preferences.put(SNAPSHOT, encoded)
        preferences.flush()
        check(preferences.get(SNAPSHOT, null) == encoded)
    }

    override suspend fun clearPassword(): ProxySaveResult = withContext(Dispatchers.IO) {
        lock.withLock {
            val snapshot = try { readSnapshot() } catch (_: Exception) {
                return@withLock ProxySaveResult(false, "Proxy settings could not be read; password clearance was not verified.")
            }
            val bindings = (snapshot.pendingCleanup + listOfNotNull(snapshot.binding)).distinct()
            val remaining = bindings.filterNot { deleteVerified(it) }
            if (remaining.isNotEmpty()) {
                return@withLock ProxySaveResult(false, "Stored proxy password clearance could not be verified.", recoveryRequired = true)
            }
            try {
                if (bindings.isNotEmpty()) persist(snapshot.copy(binding = null, pendingCleanup = emptyList()))
                ProxySaveResult(true, "Stored proxy password cleared.")
            } catch (_: Exception) {
                ProxySaveResult(false, "Password absence was verified, but settings cleanup needs retry.", recoveryRequired = true)
            }
        }
    }

    private suspend fun deleteVerified(binding: ProxyCredentialBinding): Boolean = try {
        when (secrets.read(binding)) {
            ProxySecretLookup.Missing -> true
            is ProxySecretLookup.Found -> secrets.clear(binding) == ProxySecretMutation.Succeeded &&
                secrets.read(binding) == ProxySecretLookup.Missing
            ProxySecretLookup.Unavailable, ProxySecretLookup.Failed -> false
        }
    } catch (cancelled: CancellationException) { throw cancelled }
    catch (_: Exception) { false }

    /** Synchronous factory callback stays source compatible, but backend work remains deadline-bound. */
    internal fun environment(): Map<String, String> = runBlocking(Dispatchers.IO) {
        lock.withLock {
            val snapshot = readSnapshot()
            val configuration = snapshot.configuration
            if (!configuration.enabled || configuration.validationError() != null) return@withLock emptyMap()
            val lookup = snapshot.binding?.let { secrets.read(it) } ?: ProxySecretLookup.Missing
            val password = when (lookup) {
                is ProxySecretLookup.Found -> lookup.secret
                ProxySecretLookup.Missing -> null
                ProxySecretLookup.Unavailable, ProxySecretLookup.Failed ->
                    throw IllegalStateException("Proxy credential lookup failed; command environment was not created.")
            }
            val credentials = when {
                configuration.username.isBlank() -> ""
                password == null -> "${configuration.username.urlEncoded()}@"
                else -> "${configuration.username.urlEncoded()}:${password.urlEncoded()}@"
            }
            val proxy = "http://$credentials${configuration.host}:${configuration.port}"
            mapOf("HTTP_PROXY" to proxy, "HTTPS_PROXY" to proxy, "http_proxy" to proxy, "https_proxy" to proxy)
        }
    }

    private data class Snapshot(
        val configuration: ProxyConfiguration,
        val binding: ProxyCredentialBinding?,
        val pendingCleanup: List<ProxyCredentialBinding> = emptyList(),
    )

    private fun readSnapshot(): Snapshot {
        val encoded = preferences.get(SNAPSHOT, null)
        if (encoded == null) {
            // Legacy global vault entries have no trustworthy identity. Never attach them to these coordinates.
            return Snapshot(ProxyConfiguration(
                enabled = preferences.getBoolean("proxy-enabled", false), host = preferences.get("proxy-host", ""),
                port = preferences.getInt("proxy-port", 8080), username = preferences.get("proxy-username", ""),
            ), null, if (preferences.get("proxy-host", null) != null) listOf(ProxyCredentialBinding.Legacy) else emptyList())
        }
        try {
            return DataInputStream(ByteArrayInputStream(Base64.getDecoder().decode(encoded))).use { input ->
                check(input.readInt() == 1)
                val configuration = ProxyConfiguration(input.readBoolean(), input.readUTF(), input.readInt(), input.readUTF())
                val revision = input.readUTF()
                val cleanupCount = input.readInt()
                check(cleanupCount in 0..64)
                val cleanup = List(cleanupCount) {
                    ProxyCredentialBinding(ProxyIdentity(input.readUTF(), input.readInt(), input.readUTF()), input.readUTF())
                }
                check(input.available() == 0)
                check(configuration.validationError() == null)
                if (revision.isNotEmpty()) UUID.fromString(revision)
                cleanup.forEach {
                    if (it != ProxyCredentialBinding.Legacy) {
                        UUID.fromString(it.revision)
                        check(ProxyConfiguration(true, it.identity.host, it.identity.port, it.identity.username).validationError() == null)
                    }
                }
                val binding = revision.takeIf { it.isNotEmpty() }?.let { ProxyCredentialBinding(ProxyIdentity.of(configuration), it) }
                check(binding !in cleanup)
                check(cleanup.distinct().size == cleanup.size)
                Snapshot(configuration, binding, cleanup)
            }
        } catch (_: Exception) { throw IllegalStateException("Proxy settings snapshot is invalid.") }
    }

    private fun encode(snapshot: Snapshot): String {
        val output = ByteArrayOutputStream()
        DataOutputStream(output).use {
            it.writeInt(1)
            it.writeBoolean(snapshot.configuration.enabled)
            it.writeUTF(snapshot.configuration.host)
            it.writeInt(snapshot.configuration.port)
            it.writeUTF(snapshot.configuration.username)
            it.writeUTF(snapshot.binding?.revision.orEmpty())
            check(snapshot.pendingCleanup.size <= 64)
            it.writeInt(snapshot.pendingCleanup.size)
            snapshot.pendingCleanup.forEach { binding ->
                it.writeUTF(binding.identity.host)
                it.writeInt(binding.identity.port)
                it.writeUTF(binding.identity.username)
                it.writeUTF(binding.revision)
            }
        }
        return Base64.getEncoder().encodeToString(output.toByteArray()).also {
            check(it.length <= Preferences.MAX_VALUE_LENGTH)
        }
    }

    private fun String.urlEncoded(): String = URLEncoder.encode(this, StandardCharsets.UTF_8).replace("+", "%20")

    private companion object {
        const val SNAPSHOT = "proxy-snapshot-v1"
        // Serializes app-local instances (settings presenter and repository factory). Multi-process writers unsupported.
        val lock = Mutex()
    }
}

actual fun createProxyConfigurationService(): ProxyConfigurationService = JvmProxyConfigurationService()
