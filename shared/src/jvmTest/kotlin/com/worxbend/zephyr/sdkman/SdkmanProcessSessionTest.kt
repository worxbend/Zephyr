package com.worxbend.zephyr.sdkman

import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class SdkmanProcessSessionTest {
    @Test
    fun cancellationBeforeExecutionNeverLaunches() = runBlocking {
        var launched = false
        val job = async(start = CoroutineStart.LAZY) {
            SdkmanProcessSession { launched = true; it.start() }.execute(listOf("/bin/bash", "-c", "exit 0"), emptyMap(), 2.seconds)
        }
        job.cancelAndJoin()
        assertTrue(!launched)
    }

    @Test
    fun cancellationDuringBlockedLaunchDoesNotBlockAndKillsLateProcess() = runBlocking {
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val launched = AtomicReference<Process?>()
        val session = SdkmanProcessSession { builder ->
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
            builder.start().also { launched.set(it) }
        }
        val job = async(Dispatchers.Default) { session.execute(listOf("/bin/bash", "-c", "sleep 5"), emptyMap(), 10.seconds) }
        try {
            assertTrue(entered.await(2, TimeUnit.SECONDS))
            val started = System.nanoTime()
            job.cancel()
            assertTrue((System.nanoTime() - started) / 1_000_000 < 100, "Cancellation notification must not wait for launch")
            withTimeout(1.seconds) { job.join() }
            assertTrue(job.isCancelled)
            release.countDown()
            withTimeout(2.seconds) { while (launched.get()?.isAlive != false) delay(10) }
        } finally {
            release.countDown()
            launched.get()?.destroyForcibly()
            job.cancelAndJoin()
        }
    }

    @Test
    fun deadlineIncludesBlockedLaunchAndLateProcessCleanup() = runBlocking {
        val release = CountDownLatch(1)
        val entered = CountDownLatch(1)
        val launched = AtomicReference<Process?>()
        try {
            val started = System.nanoTime()
            val result = SdkmanProcessSession { builder ->
                entered.countDown()
                check(release.await(5, TimeUnit.SECONDS))
                builder.start().also { launched.set(it) }
            }.execute(listOf("/bin/bash", "-c", "sleep 5"), emptyMap(), 100.milliseconds)
            assertTrue(entered.count == 0L)
            assertTrue(result.timedOut)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000)
            release.countDown()
            withTimeout(2.seconds) { while (launched.get()?.isAlive != false) delay(10) }
        } finally {
            release.countDown()
            launched.get()?.destroyForcibly()
        }
    }

    @Test
    fun cancellationAndTimeoutTerminateTermIgnoringDescendants() = runBlocking {
        for (cancel in listOf(true, false)) {
            val root = sdkmanTestDirectory("sdkman-process-tree-")
            val pidFile = root.resolve("child.pid")
            val child = AtomicReference<ProcessHandle?>()
            try {
                val job = async(Dispatchers.Default) {
                    SdkmanProcessSession().execute(listOf("/bin/bash", "-c", """
                        /bin/bash -c 'trap "" TERM; while true; do sleep 0.1; done' &
                        echo ${'$'}! > '${pidFile}'
                        wait
                    """.trimIndent()), emptyMap(), if (cancel) 10.seconds else 500.milliseconds)
                }
                withTimeout(2.seconds) { while (!Files.exists(pidFile) || Files.size(pidFile) == 0L) delay(10) }
                val pid = Files.readString(pidFile).trim().toLong()
                child.set(ProcessHandle.of(pid).orElseThrow())
                if (cancel) job.cancelAndJoin() else assertTrue(job.await().timedOut)
                withTimeout(2.seconds) { while (child.get()?.isAlive == true && !isZombie(pid)) delay(10) }
                assertTrue(child.get()?.isAlive == false || isZombie(pid), "Descendant must no longer execute")
            } finally {
                child.get()?.destroyForcibly()
                Files.deleteIfExists(pidFile)
                Files.delete(root)
            }
        }
    }

    @Test
    fun inheritedPipesCannotBlockCleanupEvenWhenAChildStartsANewSession() = runBlocking {
        // This specific escape fixture needs Linux setsid; the portable owner
        // has a separate, platform-independent Bash regression below.
        if (!Files.isExecutable(Path.of("/usr/bin/setsid"))) return@runBlocking
        val root = sdkmanTestDirectory("sdkman-detached-pipes-")
        val pidFile = root.resolve("detached.pid")
        try {
            val detachedScript = root.resolve("detached.sh")
            Files.writeString(detachedScript, "echo ${'$'}${'$'} > '$pidFile'\nexec sleep 2\n")
            val started = System.nanoTime()
            SdkmanProcessSession().execute(listOf("/bin/bash", "-c", """
                /usr/bin/setsid /bin/bash -c '/bin/bash "$detachedScript" & exit 0' &
                sleep 0.05
                exit 0
            """.trimIndent()), emptyMap(), 100.milliseconds)
            assertTrue((System.nanoTime() - started) / 1_000_000 < 1_000, "Process.destroy stream closing must not block the supervisor")
        } finally {
            if (Files.exists(pidFile)) {
                ProcessHandle.of(Files.readString(pidFile).trim().toLong()).ifPresent { it.destroyForcibly() }
            }
            Files.deleteIfExists(pidFile)
            Files.deleteIfExists(root.resolve("detached.sh"))
            Files.delete(root)
        }
    }

    @Test
    fun portableSessionKillsOrphansHoldingInheritedPipesAfterLeaderExit() = runBlocking {
        val root = sdkmanTestDirectory("sdkman-portable-session-")
        val pidFile = root.resolve("orphan.pid")
        var pid: Long? = null
        try {
            val result = SdkmanProcessSession(isolatedGroup = false).execute(listOf("/bin/bash", "-c", """
                /bin/bash -c 'trap "" TERM; while true; do sleep 0.1; done' &
                echo ${'$'}! > '${pidFile}'
                sleep 0.05
                exit 0
            """.trimIndent()), emptyMap(), 2.seconds)
            assertEquals(0, result.exitCode, result.stderr)
            val childPid = Files.readString(pidFile).trim().toLong()
            pid = childPid
            withTimeout(2.seconds) { while (ProcessHandle.of(childPid).map { it.isAlive }.orElse(false) && !isZombie(childPid)) delay(10) }
        } finally {
            pid?.let { ProcessHandle.of(it).ifPresent { handle -> handle.destroyForcibly() } }
            Files.deleteIfExists(pidFile)
            Files.delete(root)
        }
    }

    @Test
    fun unusedStandardInputIsClosedRatherThanLeavingCommandsWaitingForInput() = runBlocking {
        val result = SdkmanProcessSession().execute(
            listOf("/bin/bash", "-c", "if read -r value; then exit 9; else echo eof; fi"),
            emptyMap(), 300.milliseconds,
        )
        assertEquals(0, result.exitCode, result.stderr)
        assertEquals("eof", result.stdout.trim())
    }

    @Test
    fun boundsOutputWhileDrainingBothStreams() = runBlocking {
        val result = SdkmanProcessSession().execute(
            listOf("/bin/bash", "-c", "for ((i=0;i<20000;i++)); do printf '%0100d' 0; printf '%0100d' 0 >&2; done"),
            emptyMap(), 5.seconds,
        )
        assertEquals(0, result.exitCode)
        assertEquals(1_048_576, result.stdout.length)
        assertTrue(result.stderr.contains("Command output was truncated."))
    }

    private fun isZombie(pid: Long): Boolean = runCatching {
        Files.readString(Path.of("/proc/$pid/stat")).substringAfterLast(") ").startsWith("Z ")
    }.getOrDefault(false)
}
