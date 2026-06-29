package com.workflow.orchestrator.agent.tools.process

import com.intellij.openapi.diagnostic.Logger
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Holds a running process along with its stdin handle, activity timestamps,
 * and buffered output lines for lifecycle management and stdin rate limiting.
 */
data class ManagedProcess(
    val process: Process,
    val stdin: OutputStream = process.outputStream,
    val lastOutputAt: AtomicLong = AtomicLong(0),
    val outputLines: ConcurrentLinkedQueue<String> = ConcurrentLinkedQueue(),
    val toolCallId: String,
    val command: String,
    val startedAt: Long = System.currentTimeMillis(),
    val idleSignaledAt: AtomicLong = AtomicLong(0),
    val stdinCount: AtomicInteger = AtomicInteger(0),
    /** Signaled when the output reader thread finishes draining. */
    val readerDone: java.util.concurrent.CountDownLatch = java.util.concurrent.CountDownLatch(1)
)

/**
 * Tracks running processes by tool call ID for kill/killAll/stdin/reaper support.
 * Used by RunCommandTool for streaming output and process lifecycle management.
 */
object ProcessRegistry {

    private val log = Logger.getInstance(ProcessRegistry::class.java)
    private val running = ConcurrentHashMap<String, ManagedProcess>()

    /**
     * Single-thread pool for the blocking SIGTERM-wait→SIGKILL phase of [gracefulKill].
     * Prevents the EDT (or any caller thread) from blocking up to 5 s on process teardown.
     * Daemon threads so the pool doesn't prevent JVM shutdown.
     */
    private val killExecutor = Executors.newCachedThreadPool { r ->
        Thread(r, "ProcessRegistry-kill").also { it.isDaemon = true }
    }

    fun register(toolCallId: String, process: Process, command: String): ManagedProcess {
        val managed = ManagedProcess(
            process = process,
            toolCallId = toolCallId,
            command = command
        )
        running[toolCallId] = managed
        log.info("[ProcessRegistry] Registered process for tool call: $toolCallId (command: $command)")
        return managed
    }

    fun get(id: String): ManagedProcess? = running[id]

    /**
     * Writes [input] to the stdin of the process registered under [id].
     * Returns true on success, false if the process is not found, is dead, or throws an IOException.
     */
    fun writeStdin(id: String, input: String): Boolean {
        val managed = running[id] ?: return false
        if (!managed.process.isAlive) return false
        return try {
            managed.stdin.write(input.toByteArray())
            managed.stdin.flush()
            managed.stdinCount.incrementAndGet()
            true
        } catch (e: IOException) {
            log.warn("[ProcessRegistry] Failed to write stdin for tool call: $id — ${e.message}")
            false
        }
    }

    fun unregister(toolCallId: String) {
        running.remove(toolCallId)
    }

    /**
     * Two-phase kill: SIGTERM (graceful) → wait up to 5 seconds → SIGKILL (forced).
     * Also kills child processes via recursive PID tree (prevents orphaned children).
     * Pattern from Codex CLI and IntelliJ's KillableProcessHandler.
     */
    fun kill(toolCallId: String): Boolean {
        val managed = running.remove(toolCallId) ?: return false
        log.info("[ProcessRegistry] Killing process for tool call: $toolCallId")
        gracefulKill(managed.process)
        return true
    }

    fun killAll() {
        log.info("[ProcessRegistry] Killing all ${running.size} running processes")
        running.forEach { (id, managed) ->
            gracefulKill(managed.process)
            log.info("[ProcessRegistry] Killed process: $id")
        }
        running.clear()
    }

    /**
     * Returns all processes whose `idleSignaledAt` is non-zero and more than [thresholdMs]
     * milliseconds in the past. Used by the idle-process reaper coroutine.
     */
    fun getIdleProcesses(thresholdMs: Long): List<ManagedProcess> {
        val now = System.currentTimeMillis()
        return running.values.filter { managed ->
            val idleAt = managed.idleSignaledAt.get()
            idleAt > 0 && (now - idleAt) >= thresholdMs
        }
    }

    /**
     * Reaper: kill and unregister every process that has been idle-signaled for more than
     * [maxIdleSinceSignalMs] milliseconds without further user interaction. Call this
     * periodically (every ~10s) from a background coroutine tied to the agent session
     * scope to prevent orphaned interactive processes from leaking.
     */
    fun reapIdleProcesses(maxIdleSinceSignalMs: Long = 60_000) {
        val now = System.currentTimeMillis()
        val toReap = running.entries.filter { (_, managed) ->
            val idleAt = managed.idleSignaledAt.get()
            idleAt > 0 && (now - idleAt) > maxIdleSinceSignalMs
        }
        for ((id, managed) in toReap) {
            running.remove(id)
            gracefulKill(managed.process)
            log.info("[ProcessRegistry] Reaped idle process: $id (idleSignaledAt=${managed.idleSignaledAt.get()})")
        }
    }

    /**
     * Non-blocking two-phase graceful kill of the FULL process tree.
     *
     * Sends SIGTERM immediately on the calling thread (fast, non-blocking), then
     * offloads the blocking SIGTERM-wait → SIGKILL phase to [killExecutor] so the
     * caller (including EDT-affine JCEF callbacks) never blocks for up to 5 s.
     *
     * 1. Snapshot the descendant tree via ProcessHandle **before** killing the parent.
     *    Once the parent dies, children are reparented (to launchd/init on Unix) and
     *    `descendants()` no longer returns them — so the snapshot must be taken first
     *    (BUG-STOP-1 B2: the old code captured nothing extra and only force-killed the
     *    parent, leaving a `grep` child writing into the stdout pipe).
     * 2. `destroy()` — SIGTERM to parent + every descendant (graceful; lets
     *    Maven/Gradle/Docker release locks).
     * 3. On [killExecutor], via [escalateKill]: wait up to [GRACEFUL_KILL_WAIT_MS] for
     *    the parent to exit, then `destroyForcibly()` (SIGKILL) the parent AND every
     *    descendant in the snapshot that is still alive — SIGTERM→SIGKILL escalation
     *    for children, not just the parent.
     */
    private fun gracefulKill(process: Process) {
        // Snapshot descendants BEFORE the parent dies (reparented children vanish from
        // descendants() afterwards). ProcessHandle may be unavailable on some JVMs.
        val descendants: List<ProcessHandle> = try {
            process.toHandle().descendants().toList()
        } catch (_: Exception) {
            emptyList()
        }

        // Phase 1: SIGTERM the whole tree (non-blocking — OS calls return immediately).
        descendants.forEach { child ->
            try { child.destroy() } catch (_: Exception) {}
        }
        process.destroy()

        // Phase 2: blocking wait → SIGKILL escalation offloaded to a background thread
        // (F-15 fix). The calling thread (potentially EDT) must not block here.
        killExecutor.execute {
            try {
                escalateKill(process, descendants)
            } catch (_: InterruptedException) {
                process.destroyForcibly()
                descendants.forEach { child -> try { child.destroyForcibly() } catch (_: Exception) {} }
            }
        }
    }

    /**
     * Blocking SIGTERM-wait → SIGKILL escalation for a process and a pre-captured
     * snapshot of its descendants. Extracted from [gracefulKill] so the escalation
     * logic is unit-testable without offloading to [killExecutor].
     *
     * - Waits up to [gracefulWaitMs] for the parent to exit after its SIGTERM.
     * - If the parent survived, `destroyForcibly()` it (SIGKILL) and wait briefly.
     * - Force-kills every descendant in [descendants] that is still alive, so a child
     *   that outlived (or was reparented away from) the parent does not survive Stop.
     *
     * `internal` for testing; not part of the public registry surface.
     */
    internal fun escalateKill(
        process: Process,
        descendants: List<ProcessHandle>,
        gracefulWaitMs: Long = GRACEFUL_KILL_WAIT_MS,
    ) {
        if (!process.waitFor(gracefulWaitMs, java.util.concurrent.TimeUnit.MILLISECONDS)) {
            log.info("[ProcessRegistry] Process did not exit after ${gracefulWaitMs}ms SIGTERM, sending SIGKILL")
            process.destroyForcibly()
            process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
        }
        // SIGKILL any descendant still alive — covers the reparented `grep` child that
        // a parent-only kill leaves writing into the stdout pipe (BUG-STOP-1 (c)).
        descendants.forEach { child ->
            if (child.isAlive) {
                try { child.destroyForcibly() } catch (_: Exception) {}
            }
        }
    }

    private const val GRACEFUL_KILL_WAIT_MS = 5000L
}
