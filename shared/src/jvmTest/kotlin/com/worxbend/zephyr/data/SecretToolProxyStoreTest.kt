package com.worxbend.zephyr.data

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class SecretToolProxyStoreTest {
    private val binding = ProxyCredentialBinding(ProxyIdentity("proxy.example.com", 8080, "fixture-user"), "fixture-revision")

    @Test
    fun parsesOnlyStdoutAndPreservesPasswordWhitespace() = runBlocking {
        withExecutable("printf 'fixture-secret  \\n'; printf 'stderr-is-not-a-password' >&2") { executable ->
            val found = assertIs<ProxySecretLookup.Found>(SecretToolProxyStore(executable.toFile()).read(binding))
            assertEquals("fixture-secret  ", found.secret)
            assertFalse(found.toString().contains("fixture-secret"))
        }
    }

    @Test
    fun failedLookupIsNotMissingAndUnavailableBinaryIsDistinct() = runBlocking {
        withExecutable("printf 'synthetic backend failure' >&2; exit 1") {
            assertEquals(ProxySecretLookup.Failed, SecretToolProxyStore(it.toFile()).read(binding))
        }
        withExecutable("exit 0") {
            assertEquals(ProxySecretLookup.Failed, SecretToolProxyStore(it.toFile()).read(binding))
        }
        withExecutable("exit 1") {
            assertEquals(ProxySecretLookup.Missing, SecretToolProxyStore(it.toFile()).read(binding))
        }
        assertEquals(ProxySecretLookup.Unavailable, SecretToolProxyStore(Path.of("/nonexistent/zephyr-fixture-secret-tool").toFile()).read(binding))
    }

    @Test
    fun hangingExecutableHasBoundedDeadlineAndKillsItsChild() = runBlocking {
        withExecutable("sleep 30 &\nchild=\$!\nprintf '%s' \"\$child\" > \"\$0.child\"\nwait") { executable ->
            val started = System.nanoTime()
            val result = SecretToolProxyStore(executable.toFile(), SecretToolExecutor(timeoutMillis = 150, cleanupMillis = 300)).read(binding)
            assertEquals(ProxySecretLookup.Failed, result)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2000, "Secret process exceeded total deadline budget")
            val pid = Files.readString(Path.of("$executable.child")).toLong()
            assertFalse(processRunning(pid), "Scratch child survived deadline")
        }
    }

    @Test
    fun parentExitWithInheritedChildPipesStillHasBoundedCleanup() = runBlocking {
        withExecutable("sleep 30 &\nprintf '%s' \"\$!\" > \"\$0.child\"\nexit 0") { executable ->
            val started = System.nanoTime()
            val result = SecretToolProxyStore(executable.toFile(), SecretToolExecutor(timeoutMillis = 150, cleanupMillis = 300)).read(binding)
            assertEquals(ProxySecretLookup.Failed, result)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2000)
            val pid = Files.readString(Path.of("$executable.child")).toLong()
            assertFalse(processRunning(pid), "Orphaned scratch child survived stream deadline")
        }
    }

    @Test
    fun excessOutputAndBlockedStdinAreBoundedFailures() = runBlocking {
        withExecutable("head -c 8192 /dev/zero") { executable ->
            assertEquals(ProxySecretLookup.Failed, SecretToolProxyStore(executable.toFile(), SecretToolExecutor(outputLimit = 1024)).read(binding))
        }
        withExecutable("exec sleep 30") { executable ->
            val started = System.nanoTime()
            val store = SecretToolProxyStore(executable.toFile(), SecretToolExecutor(timeoutMillis = 150, cleanupMillis = 300))
            assertEquals(ProxySecretMutation.Failed, store.write(binding, "synthetic".repeat(7000)))
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2000)
        }
    }

    @Test
    fun dbusUnavailableDoesNotMasqueradeAsMissing() = runBlocking {
        withExecutable("printf 'Cannot autolaunch D-Bus without X11 display' >&2; exit 1") {
            assertEquals(ProxySecretLookup.Unavailable, SecretToolProxyStore(it.toFile()).read(binding))
        }
    }

    @Test
    fun storeSendsPasswordOnStdinNotArguments() = runBlocking {
        withExecutable("printf '%s\\n' \"\$@\" > \"\$0.args\"\ncat > \"\$0.input\"") { executable ->
            val store = SecretToolProxyStore(executable.toFile())
            assertEquals(ProxySecretMutation.Succeeded, store.write(binding, "synthetic-stdin-only"))
            val arguments = Files.readString(Path.of("$executable.args"))
            assertFalse(arguments.contains("synthetic-stdin-only"))
            assertTrue(arguments.contains("proxy.example.com"))
            assertTrue(arguments.contains("8080"))
            assertTrue(arguments.contains("fixture-user"))
            assertEquals("synthetic-stdin-only", Files.readString(Path.of("$executable.input")))
        }
    }

    @Test
    fun deadlineAndCancellationDoNotWaitForBlockedLaunchAndLateChildIsStopped() = runBlocking {
        for (cancel in listOf(false, true)) {
            withExecutable("exec sleep 30") { executable ->
                val entered = kotlinx.coroutines.CompletableDeferred<Unit>()
                val release = java.util.concurrent.CountDownLatch(1)
                val child = java.util.concurrent.atomic.AtomicReference<Process>()
                val executor = SecretToolExecutor(
                    timeoutMillis = if (cancel) 20_000 else 100,
                    cleanupMillis = 300,
                    launchProcess = { command ->
                        entered.complete(Unit)
                        check(release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        ProcessBuilder(command).start().also { child.set(it) }
                    },
                )
                try {
                    val result = kotlinx.coroutines.CompletableDeferred<SecretToolResult>()
                    val job = launch { result.complete(executor.execute(executable.toFile(), listOf("lookup"))) }
                    entered.await()
                    val started = System.nanoTime()
                    if (cancel) job.cancelAndJoin() else {
                        assertEquals(SecretToolResult.Failed, result.await())
                        job.join()
                    }
                    assertTrue((System.nanoTime() - started) / 1_000_000 < 1500, "Blocked OS launch delayed completion")
                    release.countDown()
                    repeat(200) { if (child.get() == null || child.get().isAlive) delay(5) }
                    assertTrue(child.get() != null)
                    assertFalse(child.get().isAlive, "Child launched after cancellation/deadline survived")
                } finally {
                    release.countDown()
                    child.get()?.destroyForcibly()
                }
            }
        }
    }

    @Test
    fun cancellationKillsLaunchedProcessWithoutWaitingForCommandDeadline() = runBlocking {
        withExecutable("printf '%s' \"\$\$\" > \"\$0.pid\"; exec sleep 30") { executable ->
            val store = SecretToolProxyStore(executable.toFile(), SecretToolExecutor(timeoutMillis = 20_000, cleanupMillis = 300))
            val job = launch { store.read(binding); error("Cancellation was swallowed") }
            val pidFile = Path.of("$executable.pid")
            repeat(200) { if (!Files.exists(pidFile)) delay(5) }
            assertTrue(Files.exists(pidFile))
            val pid = Files.readString(pidFile).toLong()
            val started = System.nanoTime()
            job.cancelAndJoin()
            assertTrue((System.nanoTime() - started) / 1_000_000 < 2000)
            assertFalse(processRunning(pid), "Scratch process survived cancellation")
        }
    }

    private fun processRunning(pid: Long): Boolean {
        val stat = Path.of("/proc/$pid/stat")
        if (Files.exists(stat) && Files.readString(stat).substringAfterLast(") ").startsWith("Z")) return false
        return ProcessHandle.of(pid).map { it.isAlive }.orElse(false)
    }

    private suspend fun withExecutable(body: String, test: suspend (Path) -> Unit) {
        val scratch = Path.of(System.getenv("TMPDIR") ?: System.getProperty("java.io.tmpdir"))
        val directory = Files.createTempDirectory(scratch, "zephyr-secret-tool-")
        val executable = directory.resolve("secret-tool-fixture")
        Files.writeString(executable, "#!/bin/sh\n$body\n")
        check(executable.toFile().setExecutable(true))
        try { test(executable) } finally {
            // Keep failing regressions hermetic too: terminate only PIDs recorded by this scratch fixture.
            Files.newDirectoryStream(directory).use { paths ->
                paths.filter { it.toString().endsWith(".pid") || it.toString().endsWith(".child") }.forEach { file ->
                    Files.readString(file).toLongOrNull()?.let { pid ->
                        if (processRunning(pid)) ProcessHandle.of(pid).ifPresent { it.destroyForcibly() }
                    }
                }
            }
            directory.toFile().deleteRecursively()
        }
    }
}
