package com.worxbend.zephyr.data

import java.util.prefs.AbstractPreferences
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JvmProxyConfigurationServiceTest {
    @Test
    fun passwordUsesSecretStoreAndOnlyEncodedProxyReachesChildEnvironment() = runBlocking {
        val preferences = MemoryProxyPreferences()
        val secrets = RecordingProxySecrets()
        val service = JvmProxyConfigurationService(preferences, secrets)
        assertTrue(service.save(ProxyConfiguration(true, "proxy.example.com", 8443, "build user"), "p@ss word").success)
        assertEquals("http://build%20user:p%40ss%20word@proxy.example.com:8443", service.environment()["HTTPS_PROXY"])
        assertTrue(service.load().hasStoredPassword)
        assertFalse(preferences.values.values.any { it.contains("p@ss word") })
    }

    @Test
    fun changedIdentityNeverReusesPreviousCredentialWithoutReplacement() = runBlocking {
        val original = ProxyConfiguration(true, "proxy.example.com", 8443, "build user")
        for (changed in listOf(original.copy(host = "other.example.com"), original.copy(port = 8080), original.copy(username = "other"))) {
            val service = JvmProxyConfigurationService(MemoryProxyPreferences(), RecordingProxySecrets())
            assertTrue(service.save(original, "synthetic-old-credential").success)
            assertTrue(service.save(changed, null).success)
            assertFalse(service.load().hasStoredPassword)
            assertTrue(service.environment().values.none { "synthetic-old-credential" in it })
        }
    }

    @Test
    fun unavailableSecretServiceRefusesPasswordWithoutSavingCoordinates() = runBlocking {
        val preferences = MemoryProxyPreferences()
        val service = JvmProxyConfigurationService(preferences, RecordingProxySecrets(canWrite = false))
        assertFalse(service.save(ProxyConfiguration(true, "proxy.example.com", 8080, "alex"), "synthetic").success)
        assertEquals("", service.load().host)
    }

    @Test
    fun sameNormalizedIdentityKeepsPasswordAndEnableToggleIsNotIdentity() = runBlocking {
        val service = JvmProxyConfigurationService(MemoryProxyPreferences(), RecordingProxySecrets())
        val original = ProxyConfiguration(true, "PROXY.example.com", 8080, "fixture-user")
        assertTrue(service.save(original, "synthetic").success)
        assertTrue(service.save(original.copy(host = "proxy.example.com", enabled = false), null).success)
        assertTrue(service.load().hasStoredPassword)
        assertTrue(service.environment().isEmpty())
        assertTrue(service.save(original.copy(host = "proxy.example.com"), null).success)
        assertTrue(service.environment().values.all { ":synthetic@" in it })
    }

    @Test
    fun legacyUnboundSecretsAreNotReadOrAttachedToLegacyCoordinates() = runBlocking {
        val preferences = MemoryProxyPreferences()
        preferences.putBoolean("proxy-enabled", true)
        preferences.put("proxy-host", "legacy.example.com")
        preferences.put("proxy-username", "legacy-user")
        val secrets = RecordingProxySecrets()
        val service = JvmProxyConfigurationService(preferences, secrets)
        assertFalse(service.load().hasStoredPassword)
        assertTrue(service.environment().values.none { ":synthetic@" in it })
        assertEquals(0, secrets.reads)
    }

    @Test
    fun failedLookupIsVisibleAndPreventsCommandEnvironmentCreation() = runBlocking {
        val secrets = RecordingProxySecrets()
        val service = JvmProxyConfigurationService(MemoryProxyPreferences(), secrets)
        assertTrue(service.save(ProxyConfiguration(true, "proxy.example.com", 8080, "fixture-user"), "synthetic").success)
        for (lookup in listOf(ProxySecretLookup.Unavailable, ProxySecretLookup.Failed)) {
            secrets.lookupOverride = lookup
            assertEquals(lookup.status(), service.load().credentialStatus)
            assertFalse(service.load().hasStoredPassword)
            kotlin.test.assertFailsWith<IllegalStateException> { service.environment() }
            assertFalse(service.clearPassword().success)
        }
    }

    @Test
    fun successfulClearExitWithoutMissingReadbackIsNotSuccess() = runBlocking {
        val secrets = RecordingProxySecrets()
        val service = JvmProxyConfigurationService(MemoryProxyPreferences(), secrets)
        assertTrue(service.save(ProxyConfiguration(true, "proxy.example.com", 8080, "fixture-user"), "synthetic").success)
        secrets.clearDoesNothing = true
        assertFalse(service.clearPassword().success)
        assertTrue(service.load().hasStoredPassword)
        secrets.clearDoesNothing = false
        assertTrue(service.clearPassword().success)
        assertFalse(service.load().hasStoredPassword)
    }

    @Test
    fun settingsFailureRestoresPreviousCredentialAndDeletesStagedReplacement() = runBlocking {
        val preferences = MemoryProxyPreferences()
        val secrets = RecordingProxySecrets()
        val service = JvmProxyConfigurationService(preferences, secrets)
        val original = ProxyConfiguration(true, "old.example.com", 8080, "fixture-user")
        assertTrue(service.save(original, "synthetic-old").success)
        preferences.failOnFlush = preferences.flushCalls + 2 // Intent persisted; fail the actual settings commit.
        val failed = service.save(original.copy(host = "new.example.com"), "synthetic-new")
        assertFalse(failed.success)
        assertFalse(failed.settingsSaved)
        assertFalse(failed.recoveryRequired)
        assertEquals("old.example.com", service.load().host)
        assertTrue(service.environment().values.all { ":synthetic-old@" in it })
        assertEquals(1, secrets.values.size)
    }

    @Test
    fun committedIdentityChangeWithCleanupFailureIsHonestAndRetryableAfterReload() = runBlocking {
        val preferences = MemoryProxyPreferences()
        val secrets = RecordingProxySecrets()
        val service = JvmProxyConfigurationService(preferences, secrets)
        assertTrue(service.save(ProxyConfiguration(true, "old.example.com", 8080, "fixture-user"), "synthetic-old").success)
        secrets.clearDoesNothing = true
        val changed = ProxyConfiguration(true, "new.example.com", 8080, "fixture-user")
        val result = service.save(changed, null)
        assertFalse(result.success)
        assertTrue(result.settingsSaved)
        assertTrue(result.recoveryRequired)
        assertEquals("new.example.com", service.load().host)
        assertTrue(service.environment().values.none { "synthetic-old" in it })
        secrets.clearDoesNothing = false
        val reloaded = JvmProxyConfigurationService(preferences, secrets)
        assertTrue(reloaded.clearPassword().success)
        assertTrue(secrets.values.isEmpty(), "Obsolete secret must remain addressable for cleanup retry")
    }

    @Test
    fun failedSettingsRollbackAndFailedStagingCleanupRequireRecovery() = runBlocking {
        val preferences = MemoryProxyPreferences()
        val secrets = RecordingProxySecrets()
        val service = JvmProxyConfigurationService(preferences, secrets)
        assertTrue(service.save(ProxyConfiguration(true, "old.example.com", 8080, "fixture-user"), "synthetic-old").success)
        preferences.failFlush = true
        secrets.clearDoesNothing = true
        val result = service.save(ProxyConfiguration(true, "new.example.com", 8080, "fixture-user"), "synthetic-new")
        assertFalse(result.success)
        assertTrue(result.recoveryRequired)
        assertFalse(result.message.contains("synthetic-new"))
    }

    @Test
    fun ambiguousSecretWriteFailureRestoresPreviousActiveCredential() = runBlocking {
        val preferences = MemoryProxyPreferences()
        val secrets = RecordingProxySecrets()
        val service = JvmProxyConfigurationService(preferences, secrets)
        val configuration = ProxyConfiguration(true, "proxy.example.com", 8080, "fixture-user")
        assertTrue(service.save(configuration, "synthetic-old").success)
        secrets.writeAppliedButFailed = true
        val failed = service.save(configuration, "synthetic-new")
        assertFalse(failed.success)
        assertFalse(failed.recoveryRequired)
        assertEquals(1, secrets.values.size)
        assertTrue(service.environment().values.all { ":synthetic-old@" in it })
    }

    @Test
    fun stagingCleanupFailureSurvivesReloadWithoutBecomingActive() = runBlocking {
        val preferences = MemoryProxyPreferences()
        val secrets = RecordingProxySecrets()
        val service = JvmProxyConfigurationService(preferences, secrets)
        val configuration = ProxyConfiguration(true, "proxy.example.com", 8080, "fixture-user")
        assertTrue(service.save(configuration, "synthetic-old").success)
        secrets.writeAppliedButFailed = true
        secrets.clearDoesNothing = true
        val failed = service.save(configuration, "synthetic-new")
        assertFalse(failed.success)
        assertTrue(failed.recoveryRequired)
        val reloaded = JvmProxyConfigurationService(preferences, secrets)
        assertTrue(reloaded.load().pendingCredentialCleanup)
        assertTrue(reloaded.environment().values.all { ":synthetic-old@" in it })
        secrets.clearDoesNothing = false
        assertTrue(reloaded.clearPassword().success)
        assertTrue(secrets.values.isEmpty())
    }

    @Test
    fun cancellationDuringStagingRetainsPreviousCoordinatesAndCleansNewSecret() = runBlocking {
        val preferences = MemoryProxyPreferences()
        val secrets = RecordingProxySecrets()
        val service = JvmProxyConfigurationService(preferences, secrets)
        val configuration = ProxyConfiguration(true, "old.example.com", 8080, "fixture-user")
        assertTrue(service.save(configuration, "synthetic-old").success)
        secrets.writeGate = kotlinx.coroutines.CompletableDeferred()
        val job = launch {
            service.save(configuration.copy(host = "new.example.com"), "synthetic-new")
            error("Cancellation was swallowed")
        }
        secrets.writeEntered.await()
        job.cancel()
        job.join()
        assertEquals(1, secrets.values.size)
        assertEquals("old.example.com", service.load().host)
        assertTrue(service.environment().values.all { ":synthetic-old@" in it })
    }

    @Test
    fun passwordCannotBeStoredWithoutDestinationEvenWhenProxyDisabled() = runBlocking {
        val preferences = MemoryProxyPreferences()
        val secrets = RecordingProxySecrets()
        val service = JvmProxyConfigurationService(preferences, secrets)
        assertFalse(service.save(ProxyConfiguration(), "synthetic").success)
        assertTrue(preferences.values.isEmpty())
        assertTrue(secrets.values.isEmpty())
    }

    @Test
    fun silentSettingsWriteLossIsNotReportedAsSaved() = runBlocking {
        val preferences = MemoryProxyPreferences()
        val secrets = RecordingProxySecrets()
        val service = JvmProxyConfigurationService(preferences, secrets)
        val original = ProxyConfiguration(true, "old.example.com", 8080, "fixture-user")
        assertTrue(service.save(original, "synthetic-old").success)
        preferences.ignoreNextPut = true
        val result = service.save(original.copy(host = "new.example.com"), null)
        assertFalse(result.success)
        assertEquals("old.example.com", service.load().host)
        assertTrue(service.environment().values.all { ":synthetic-old@" in it })
    }

    @Test
    fun oversizedAndCorruptSnapshotsDoNotChangeSecrets() = runBlocking {
        val preferences = MemoryProxyPreferences()
        val secrets = RecordingProxySecrets()
        val service = JvmProxyConfigurationService(preferences, secrets)
        assertFalse(service.save(ProxyConfiguration(true, "x".repeat(9000), 8080, "fixture-user"), "synthetic").success)
        assertTrue(preferences.values.isEmpty())
        assertTrue(secrets.values.isEmpty())
        preferences.put("proxy-snapshot-v1", "corrupt-fixture")
        assertFalse(service.save(ProxyConfiguration(true, "proxy.example.com", 8080), null).success)
        assertEquals("corrupt-fixture", preferences.get("proxy-snapshot-v1", ""))
        kotlin.test.assertFailsWith<IllegalStateException> { service.environment() }
        Unit
    }

    private class RecordingProxySecrets(private val canWrite: Boolean = true) : ProxySecretStore {
        val values = mutableMapOf<ProxyCredentialBinding, String>()
        var lookupOverride: ProxySecretLookup? = null
        var clearDoesNothing = false
        var writeAppliedButFailed = false
        var writeGate: kotlinx.coroutines.CompletableDeferred<Unit>? = null
        val writeEntered = kotlinx.coroutines.CompletableDeferred<Unit>()
        var reads = 0
        override suspend fun read(binding: ProxyCredentialBinding): ProxySecretLookup {
            reads++
            return lookupOverride ?: values[binding]?.let { ProxySecretLookup.Found(it) } ?: ProxySecretLookup.Missing
        }
        override suspend fun write(binding: ProxyCredentialBinding, secret: String): ProxySecretMutation {
            if (!canWrite) return ProxySecretMutation.Unavailable
            values[binding] = secret
            writeGate?.let { writeEntered.complete(Unit); it.await() }
            return if (writeAppliedButFailed) ProxySecretMutation.Failed else ProxySecretMutation.Succeeded
        }
        override suspend fun clear(binding: ProxyCredentialBinding): ProxySecretMutation {
            if (!clearDoesNothing) values.remove(binding)
            return ProxySecretMutation.Succeeded
        }
    }
}

internal class MemoryProxyPreferences : AbstractPreferences(null, "") {
    val values = mutableMapOf<String, String>()
    var failFlush = false
    var failOnFlush = 0
    var flushCalls = 0
    var ignoreNextPut = false
    override fun putSpi(key: String, value: String) {
        if (ignoreNextPut) ignoreNextPut = false else values[key] = value
    }
    override fun getSpi(key: String): String? = values[key]
    override fun removeSpi(key: String) { values.remove(key) }
    override fun removeNodeSpi() { values.clear() }
    override fun keysSpi(): Array<String> = values.keys.toTypedArray()
    override fun childrenNamesSpi(): Array<String> = emptyArray()
    override fun childSpi(name: String): AbstractPreferences = error("No child preferences")
    override fun syncSpi() = Unit
    override fun flushSpi() {
        flushCalls++
        if (failFlush || flushCalls == failOnFlush) {
            throw java.util.prefs.BackingStoreException("synthetic failure")
        }
    }
}
