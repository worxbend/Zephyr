package com.worxbend.zephyr.sdkman

import com.worxbend.zephyr.domain.ConnectivityOutcome
import com.worxbend.zephyr.domain.ConnectivityRouteKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.Path
import okio.Path.Companion.toPath

class ApacheCommonsSdkmanCommandRunnerTest {
    @Test
    fun sourcesTheConfiguredSdkmanHomeWithoutReadingUserShellConfiguration() = runBlocking {
        withFakeSdkmanHome("""
            sdk() {
                if [ "${'$'}1" = "version" ]; then
                    printf '%s\n' 'SDKMAN! test version'
                fi
            }
        """) { home ->
            val result = ApacheCommonsSdkmanCommandRunner(home).run(SdkmanCommand.Version, 2.seconds)

            assertEquals(0, result.exitCode, result.stderr)
            assertEquals("SDKMAN! test version", result.stdout.trim())
        }
    }

    @Test
    fun forcesAStableLocaleForSdkmanOutputParsing() = runBlocking {
        withFakeSdkmanHome("""
            sdk() {
                printf '%s|%s\n' "${'$'}LC_ALL" "${'$'}LANG"
            }
        """) { home ->
            val result = ApacheCommonsSdkmanCommandRunner(home).run(SdkmanCommand.Version, 2.seconds)

            assertEquals(0, result.exitCode, result.stderr)
            assertEquals("C|C", result.stdout.trim())
        }
    }

    @Test
    fun cancellationReturnsPromptlyAfterTheSdkmanCommandStarts() = runBlocking {
        withFakeSdkmanHome("""
            sdk() {
                touch "${'$'}SDKMAN_DIR/started"
                sleep 30
            }
        """) { home ->
            val command = async(Dispatchers.Default) {
                ApacheCommonsSdkmanCommandRunner(home).run(SdkmanCommand.Version, 1.minutes)
            }
            val started = java.io.File(home.toString(), "started")
            withTimeout(2.seconds) {
                while (!started.exists()) {
                    kotlinx.coroutines.yield()
                }
            }

            command.cancelAndJoin()
            assertTrue(command.isCancelled)
        }
    }

    @Test
    fun deadlineIncludesChildrenHoldingInheritedOutputPipes() = runBlocking {
        withFakeSdkmanHome("""
            sdk() {
                sleep 2 &
                wait
            }
        """) { home ->
            val started = System.nanoTime()
            val result = ApacheCommonsSdkmanCommandRunner(home).run(
                SdkmanCommand.Version, kotlin.time.Duration.parse("100ms"),
            )
            val elapsedMillis = (System.nanoTime() - started) / 1_000_000
            assertTrue(result.timedOut)
            assertTrue(elapsedMillis < 1_000, "Cleanup exceeded its bound: $elapsedMillis ms")
        }
    }

    @Test
    fun diagnosticsNormalizeEverySupportedProxyKeyWithoutPuttingCredentialsInArgv() = runBlocking {
        listOf("HTTPS_PROXY", "https_proxy", "HTTP_PROXY", "http_proxy").forEach { proxyKey ->
            withFakeSdkmanHome("""
                curl() {
                    printf '%s\n' "${'$'}@" > "${'$'}SDKMAN_DIR/curl-arguments"
                    printf '%s' "${'$'}HTTPS_PROXY" > "${'$'}SDKMAN_DIR/proxy-environment"
                    printf '204'
                }
            """) { home ->
                val proxy = "http://proxy-user:proxy-password@proxy.internal:8080"
                val diagnostic = ApacheCommonsSdkmanCommandRunner(
                    sdkmanHome = home,
                    proxyEnvironment = { mapOf(proxyKey to proxy) },
                ).diagnoseConnectivity(2.seconds)

                assertEquals(ConnectivityRouteKind.Proxy, diagnostic.route, proxyKey)
                assertEquals(ConnectivityOutcome.Online, diagnostic.outcome, proxyKey)
                val arguments = FileSystem.SYSTEM.read(home / "curl-arguments") { readUtf8() }
                val environment = FileSystem.SYSTEM.read(home / "proxy-environment") { readUtf8() }
                assertEquals(proxy, environment, proxyKey)
                assertTrue(!arguments.contains(proxy), "Proxy credentials must remain outside curl argv.")
                assertTrue(arguments.startsWith("--disable\n"), "curl configuration must be disabled first.")
                assertTrue(arguments.contains("--noproxy\n\n"), "Proxy mode must explicitly clear no-proxy routing.")
            }
        }
    }

    @Test
    fun diagnosticsExplicitlyUseTheDirectRouteWhenNoProxyIsConfigured() = runBlocking {
        withFakeSdkmanHome("""
            curl() {
                printf '%s\n' "${'$'}@" > "${'$'}SDKMAN_DIR/curl-arguments"
                printf '204'
            }
        """) { home ->
            val diagnostic = ApacheCommonsSdkmanCommandRunner(
                sdkmanHome = home,
                proxyEnvironment = ::emptyMap,
            ).diagnoseConnectivity(2.seconds)

            assertEquals(ConnectivityRouteKind.Direct, diagnostic.route)
            assertEquals(ConnectivityOutcome.Online, diagnostic.outcome)
            val arguments = FileSystem.SYSTEM.read(home / "curl-arguments") { readUtf8() }
            assertTrue(arguments.startsWith("--disable\n"), "curl configuration must be disabled first.")
            assertTrue(arguments.contains("--proxy\n\n"), "Direct mode must explicitly disable proxy routing.")
            assertTrue(arguments.contains("--noproxy\n*"), "Direct mode must explicitly bypass inherited proxy routing.")
        }
    }

    @Test
    fun classifiesEverySafeConnectivityOutcomeIncludingWatchdogTimeouts() {
        assertEquals(ConnectivityOutcome.Online, classifyConnectivity(SdkmanCommandResult(0, "204", "")))
        assertEquals(ConnectivityOutcome.ProxyAuthentication, classifyConnectivity(SdkmanCommandResult(0, "407", "")))
        assertEquals(ConnectivityOutcome.Tls, classifyConnectivity(SdkmanCommandResult(60, "", "certificate problem")))
        assertEquals(
            ConnectivityOutcome.Timeout,
            classifyConnectivity(SdkmanCommandResult(-1, "", "", timedOut = true)),
        )
        assertEquals(ConnectivityOutcome.Timeout, classifyConnectivity(SdkmanCommandResult(28, "", "operation timed out")))
        assertEquals(ConnectivityOutcome.Service, classifyConnectivity(SdkmanCommandResult(7, "", "connection refused")))
        assertEquals(ConnectivityOutcome.Indeterminate, classifyConnectivity(SdkmanCommandResult(-1, "", "")))
    }

    private suspend fun withFakeSdkmanHome(script: String, block: suspend (Path) -> Unit) {
        val home = sdkmanTestDirectory("zephyr-runner-test-").toString().toPath()
        try {
            FileSystem.SYSTEM.createDirectories(home / "bin")
            FileSystem.SYSTEM.write(home / "bin" / "sdkman-init.sh") { writeUtf8(script.trimIndent()) }
            block(home)
        } finally {
            FileSystem.SYSTEM.deleteRecursively(home, mustExist = false)
        }
    }
}
