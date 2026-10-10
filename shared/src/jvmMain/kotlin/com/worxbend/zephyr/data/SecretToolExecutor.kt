package com.worxbend.zephyr.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

internal sealed interface SecretToolResult {
    class Completed(val exitCode: Int, val stdout: String, val stderrPresent: Boolean, val backendUnavailable: Boolean) : SecretToolResult {
        override fun toString(): String = "Completed(exitCode=$exitCode, output=[redacted])"
    }
    data object Unavailable : SecretToolResult
    data object Failed : SecretToolResult
}

/** Owns launch, stdin, independent bounded stdout/stderr readers, deadline and cancellation cleanup. */
internal class SecretToolExecutor(
    private val timeoutMillis: Long = 3_000,
    private val cleanupMillis: Long = 300,
    private val outputLimit: Int = 65_536,
    private val launchProcess: (List<String>) -> Process = { ProcessBuilder(it).start() },
) {
    init { require(timeoutMillis > 0 && cleanupMillis > 0 && outputLimit > 0) }

    suspend fun execute(executable: File, arguments: List<String>, input: String? = null): SecretToolResult =
        withContext(Dispatchers.IO) {
            currentCoroutineContext().ensureActive()
            if (!executable.canExecute()) return@withContext SecretToolResult.Unavailable
            if (input != null && input.toByteArray(StandardCharsets.UTF_8).size > outputLimit) return@withContext SecretToolResult.Failed
            val deadline = System.nanoTime() + timeoutMillis * 1_000_000
            // An isolated session retains a killable group after a fast parent exit/orphaned pipe holder.
            val isolated = File("/usr/bin/setsid").canExecute() && File("/bin/kill").canExecute()
            // Refuse a weaker fallback that could abandon reparented children outside our ownership.
            if (!isolated) return@withContext SecretToolResult.Unavailable
            val command = listOf("/usr/bin/setsid", executable.absolutePath) + arguments
            val launch = LaunchOwner()
            val started = CompletableFuture<Process>()
            daemon("zephyr-secret-launch") {
                if (launch.isAbandoned()) return@daemon
                try {
                    val child = launchProcess(command)
                    if (launch.publish(child)) started.complete(child)
                    else kotlinx.coroutines.runBlocking { stop(child, emptyList(), isolated) }
                } catch (_: Exception) { started.completeExceptionally(IllegalStateException("Secret process launch failed.")) }
            }
            val descendants = mutableMapOf<Long, ProcessHandle>()
            try {
                // Launch may block in the OS. Caller cancellation/deadline never waits on that worker.
                while (!started.isDone) {
                    currentCoroutineContext().ensureActive()
                    if (System.nanoTime() >= deadline) return@withContext SecretToolResult.Failed
                    delay(5)
                }
                if (started.isCompletedExceptionally) return@withContext SecretToolResult.Unavailable
                val process = started.get()
                currentCoroutineContext().ensureActive()
                val overflow = AtomicBoolean(false)
                val stdout = drain(process.inputStream, overflow)
                val stderr = drain(process.errorStream, overflow)
                val stdin = CompletableFuture<Boolean>()
                daemon("zephyr-secret-stdin") {
                    try {
                        process.outputStream.use { stream ->
                            if (input != null) stream.write(input.toByteArray(StandardCharsets.UTF_8))
                        }
                        stdin.complete(true)
                    } catch (_: Exception) { stdin.complete(false) }
                }
                while (process.isAlive || !stdout.isDone || !stderr.isDone || !stdin.isDone) {
                    currentCoroutineContext().ensureActive()
                    process.descendants().use { handles -> handles.forEach { descendants[it.pid()] = it } }
                    if (overflow.get() || System.nanoTime() >= deadline) return@withContext SecretToolResult.Failed
                    delay(10)
                }
                currentCoroutineContext().ensureActive()
                if (overflow.get() || System.nanoTime() >= deadline || !stdin.get() ||
                    stdout.isCompletedExceptionally || stderr.isCompletedExceptionally) return@withContext SecretToolResult.Failed
                val error = String(stderr.get(), StandardCharsets.UTF_8)
                // Only fixed categories escape this adapter; never expose raw stderr (which may echo input).
                val unavailable = error.contains("Cannot autolaunch D-Bus", ignoreCase = true) ||
                    error.contains("org.freedesktop.secrets was not provided", ignoreCase = true) ||
                    error.contains("could not connect", ignoreCase = true)
                SecretToolResult.Completed(
                    process.exitValue(), String(stdout.get(), StandardCharsets.UTF_8), error.isNotEmpty(), unavailable,
                )
            } finally {
                launch.abandon()?.let { process ->
                    withContext(NonCancellable) { stop(process, descendants.values.toList(), isolated) }
                }
            }
        }

    private class LaunchOwner {
        private var abandoned = false
        private var process: Process? = null
        @Synchronized fun isAbandoned(): Boolean = abandoned
        @Synchronized fun publish(child: Process): Boolean {
            if (abandoned) return false
            process = child
            return true
        }
        @Synchronized fun abandon(): Process? {
            abandoned = true
            return process.also { process = null }
        }
    }

    private fun drain(stream: InputStream, overflow: AtomicBoolean): CompletableFuture<ByteArray> {
        val result = CompletableFuture<ByteArray>()
        daemon("zephyr-secret-output") {
            try {
                stream.use {
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(4096)
                    while (true) {
                        val count = it.read(buffer)
                        if (count < 0) break
                        if (count > outputLimit - output.size()) {
                            overflow.set(true)
                            break
                        }
                        output.write(buffer, 0, count)
                    }
                    result.complete(output.toByteArray())
                }
            } catch (_: Exception) { result.completeExceptionally(IllegalStateException("Secret output read failed.")) }
        }
        return result
    }

    private suspend fun stop(process: Process, known: List<ProcessHandle>, isolated: Boolean) {
        val handles = (known + process.descendants().use { it.toList() }).distinctBy { it.pid() }
        val deadline = System.nanoTime() + cleanupMillis * 1_000_000
        val gracefulDeadline = System.nanoTime() + cleanupMillis * 500_000
        if (isolated) signalGroup(process.pid(), "TERM", deadline)
        handles.asReversed().forEach { if (it.isAlive) it.destroy() }
        if (process.isAlive) process.destroy()
        while ((process.isAlive || handles.any { it.isAlive }) && System.nanoTime() < gracefulDeadline) delay(5)
        // Signal the group even when the parent has already exited and descendants were reparented.
        if (isolated) signalGroup(process.pid(), "KILL", deadline)
        handles.asReversed().forEach { if (it.isAlive) it.destroyForcibly() }
        if (process.isAlive) process.destroyForcibly()
        while ((process.isAlive || handles.any { it.isAlive }) && System.nanoTime() < deadline) delay(5)
        // Closing Java pipe streams can contend with a reader lock: never join an unbounded reader/closer.
        daemon("zephyr-secret-close") {
            runCatching { process.outputStream.close() }
            runCatching { process.inputStream.close() }
            runCatching { process.errorStream.close() }
        }
    }

    private fun signalGroup(pid: Long, signal: String, deadline: Long) {
        runCatching {
            val killer = ProcessBuilder("/bin/kill", "-$signal", "--", "-$pid")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val remaining = ((deadline - System.nanoTime()) / 1_000_000).coerceIn(1, 25)
            if (!killer.waitFor(remaining, java.util.concurrent.TimeUnit.MILLISECONDS)) killer.destroyForcibly()
        }
    }

    private fun daemon(name: String, action: () -> Unit) {
        Thread(action, name).apply { isDaemon = true; start() }
    }
}

internal class SecretToolProxyStore(
    private val executable: File = File("/usr/bin/secret-tool"),
    private val executor: SecretToolExecutor = SecretToolExecutor(),
) : ProxySecretStore {
    override suspend fun read(binding: ProxyCredentialBinding): ProxySecretLookup =
        when (val result = executor.execute(executable, listOf("lookup") + attributes(binding))) {
            is SecretToolResult.Completed -> when {
                result.backendUnavailable -> ProxySecretLookup.Unavailable
                result.exitCode == 0 && result.stdout.removeSuffix("\n").isNotEmpty() ->
                    ProxySecretLookup.Found(result.stdout.removeSuffix("\n"))
                result.exitCode == 1 && result.stdout.isEmpty() && !result.stderrPresent -> ProxySecretLookup.Missing
                else -> ProxySecretLookup.Failed
            }
            SecretToolResult.Unavailable -> ProxySecretLookup.Unavailable
            SecretToolResult.Failed -> ProxySecretLookup.Failed
        }

    override suspend fun write(binding: ProxyCredentialBinding, secret: String): ProxySecretMutation =
        mutation(executor.execute(executable, listOf("store", "--label=Zephyr proxy password") + attributes(binding), secret))

    override suspend fun clear(binding: ProxyCredentialBinding): ProxySecretMutation =
        mutation(executor.execute(executable, listOf("clear") + attributes(binding)))

    private fun mutation(result: SecretToolResult): ProxySecretMutation = when (result) {
        is SecretToolResult.Completed -> when {
            result.backendUnavailable -> ProxySecretMutation.Unavailable
            result.exitCode == 0 -> ProxySecretMutation.Succeeded
            else -> ProxySecretMutation.Failed
        }
        SecretToolResult.Unavailable -> ProxySecretMutation.Unavailable
        SecretToolResult.Failed -> ProxySecretMutation.Failed
    }

    private fun attributes(binding: ProxyCredentialBinding): List<String> =
        if (binding == ProxyCredentialBinding.Legacy) listOf("application", "zephyr", "kind", "proxy")
        else listOf(
            "application", "zephyr", "kind", "proxy-v2", "host", binding.identity.host,
            "port", binding.identity.port.toString(), "username", binding.identity.username, "revision", binding.revision,
        )
}
