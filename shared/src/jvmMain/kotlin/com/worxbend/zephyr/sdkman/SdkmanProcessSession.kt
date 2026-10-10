package com.worxbend.zephyr.sdkman

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.resume
import kotlin.time.Duration
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * One launch, tree, output and cleanup owner. Cancellation only publishes a flag;
 * it never waits on ProcessBuilder.start or a stream-reader lock.
 * Linux uses a private session/process group, including children reparented after
 * Bash exits. A Bash job-control trampoline also owns the command's group on
 * platforms without setsid. Deliberately detached/new-session daemons are not
 * supported SDKMAN children; observed handles add a best-effort backstop.
 */
internal class SdkmanProcessSession(
    private val isolatedGroup: Boolean = Files.isExecutable(Path.of("/usr/bin/setsid")),
    private val launch: (ProcessBuilder) -> Process = ProcessBuilder::start,
) {
    private val stopping = AtomicBoolean()
    private val cleanupStarted = AtomicBoolean()
    private val process = AtomicReference<Process?>()
    private val launchFailure = AtomicReference<Exception?>()
    private val launchFinished = AtomicBoolean()
    private val stopped = CompletableDeferred<Unit>()

    suspend fun execute(arguments: List<String>, environment: Map<String, String>, timeout: Duration): SdkmanCommandResult {
        require(timeout.isPositive() && timeout.isFinite()) { "Command timeout must be positive and finite." }
        val stdout = SessionOutput()
        val stderr = SessionOutput()
        val grouped = isolatedGroup
        val portableArguments = listOf("/bin/bash", "--noprofile", "--norc", "-c", PORTABLE_GROUP_WRAPPER, "zephyr-session") + arguments
        val builder = ProcessBuilder(if (grouped) listOf("/usr/bin/setsid", "--") + arguments else portableArguments)
        builder.environment().apply { clear(); putAll(environment) }
        val started = System.nanoTime()
        try {
            return suspendCancellableCoroutine { continuation ->
                continuation.invokeOnCancellation { stopping.set(true) }
                thread(name = "zephyr-process-session", isDaemon = true) {
                    var timedOut = false
                    var failure: Exception? = null
                    var exitCode = -1
                    val descendants = linkedSetOf<ProcessHandle>()
                    val pumps = mutableListOf<Thread>()
                    try {
                        // Launch has its own worker: even a blocked native start cannot
                        // pin cancellation, the deadline, or the supervising thread.
                        thread(name = "zephyr-process-launch", isDaemon = true) {
                            try {
                                if (!stopping.get()) {
                                    val launched = launch(builder)
                                    process.set(launched)
                                    // Commands are non-interactive; preserve the former
                                    // stream handler's EOF behavior for SDKMAN prompts.
                                    runCatching { launched.outputStream.close() }
                                    if (stopping.get()) runCatching { terminate(launched, grouped, emptySet()) }
                                }
                            } catch (exception: Exception) {
                                launchFailure.set(exception)
                            } finally {
                                launchFinished.set(true)
                            }
                        }
                        var pumping = false
                        while (!stopping.get()) {
                            if (System.nanoTime() - started >= timeout.inWholeNanoseconds) {
                                timedOut = true
                                stopping.set(true)
                                break
                            }
                            val running = process.get()
                            if (running != null) {
                                running.toHandle().descendants().use { handles -> handles.forEach(descendants::add) }
                                if (!pumping) {
                                    pumps += pump(running.inputStream, stdout)
                                    pumps += pump(running.errorStream, stderr)
                                    pumping = true
                                }
                                if (!running.isAlive) {
                                    exitCode = running.exitValue()
                                    break
                                }
                            } else if (launchFinished.get()) {
                                failure = launchFailure.get()
                                break
                            }
                            Thread.sleep(10)
                        }
                    } catch (exception: Exception) {
                        failure = exception
                    } finally {
                        stopping.set(true)
                        process.get()?.let { running ->
                            runCatching { terminate(running, grouped, descendants) }
                            // Closing a pipe may wait for its reader's monitor. Never
                            // do that on the supervisor/cancellation thread.
                            thread(name = "zephyr-process-close", isDaemon = true) {
                                runCatching { running.inputStream.close() }
                                runCatching { running.errorStream.close() }
                                runCatching { running.outputStream.close() }
                            }
                        }
                        val drainDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(100)
                        pumps.forEach { reader ->
                            val remaining = drainDeadline - System.nanoTime()
                            if (remaining > 0) reader.join(TimeUnit.NANOSECONDS.toMillis(remaining).coerceAtLeast(1))
                        }
                        val messages = buildList {
                            stderr.text().takeIf(String::isNotBlank)?.let(::add)
                            if (timedOut) add("Command timed out after $timeout.")
                            if (stdout.truncated || stderr.truncated) add("Command output was truncated.")
                            if (pumps.any(Thread::isAlive)) add("Command output draining exceeded its cleanup deadline.")
                            if (failure != null) add("Command could not be launched or completed.")
                        }
                        stopped.complete(Unit)
                        continuation.resume(SdkmanCommandResult(
                            exitCode = if (timedOut) -1 else exitCode,
                            stdout = stdout.text(),
                            stderr = messages.joinToString("\n"),
                            timedOut = timedOut,
                        ))
                    }
                }
            }
        } finally {
            // Preserve the caller's CancellationException, but give an owned live
            // process a bounded opportunity to finish tree cleanup before join.
            stopping.set(true)
            withContext(NonCancellable) { withTimeoutOrNull(750) { stopped.await() } }
        }
    }

    private fun terminate(running: Process, grouped: Boolean, observed: Set<ProcessHandle>) {
        if (!cleanupStarted.compareAndSet(false, true)) return
        val handles = observed.toMutableSet()
        runCatching { running.toHandle().descendants().use { it.forEach(handles::add) } }
        if (grouped) signalGroup(running.pid(), "TERM")
        handles.forEach { runCatching { it.destroy() } }
        // Process.destroy may close pipes in the calling thread. ProcessHandle
        // signals only; pipe closing belongs to the separate bounded drain path.
        running.toHandle().destroy()
        // Fixed grace, not an unbounded waitFor()/stream-handler stop().
        running.waitFor(75, TimeUnit.MILLISECONDS)
        if (grouped) signalGroup(running.pid(), "KILL")
        handles.forEach { runCatching { if (it.isAlive) it.destroyForcibly() } }
        if (running.isAlive) running.toHandle().destroyForcibly()
        running.waitFor(75, TimeUnit.MILLISECONDS)
    }

    private fun signalGroup(pid: Long, signal: String) {
        val finished = CountDownLatch(1)
        thread(name = "zephyr-process-signal", isDaemon = true) {
            try {
                runCatching {
                    val kill = ProcessBuilder("/bin/kill", "-$signal", "--", "-$pid")
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD).start()
                    if (!kill.waitFor(50, TimeUnit.MILLISECONDS)) kill.destroyForcibly()
                }
            } finally {
                finished.countDown()
            }
        }
        finished.await(50, TimeUnit.MILLISECONDS)
    }

    private fun pump(input: InputStream, output: SessionOutput): Thread =
        thread(name = "zephyr-process-output", isDaemon = true) {
            runCatching {
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    output.append(buffer, count)
                }
            }
        }
}

// Bash's job control starts the command in its own group, so its EXIT trap
// cleans inherited-pipe holders even after the command leader has exited.
// kill is a Bash builtin: this fallback requires no native signal subprocess.
private val PORTABLE_GROUP_WRAPPER = """
    set -m
    child=''
    trap 'exit 143' TERM INT
    trap 'child=${'$'}{child:-${'$'}!}; if [ -n "${'$'}child" ]; then kill -TERM -- "-${'$'}child" 2>/dev/null; kill -KILL -- "-${'$'}child" 2>/dev/null; fi' EXIT
    "${'$'}@" &
    child=${'$'}!
    wait "${'$'}child"
    exit ${'$'}?
""".trimIndent()

private class SessionOutput {
    private val bytes = ByteArrayOutputStream()
    @Volatile var truncated = false
        private set

    @Synchronized fun append(buffer: ByteArray, count: Int) {
        val writable = count.coerceAtMost((1_048_576 - bytes.size()).coerceAtLeast(0))
        bytes.write(buffer, 0, writable)
        if (writable < count) truncated = true
    }

    @Synchronized fun text(): String = bytes.toString(Charsets.UTF_8.name())
}
