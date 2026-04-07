package com.workflow.orchestrator.agent.tools.process

import com.intellij.openapi.diagnostic.Logger
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
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
     * Two-phase graceful kill:
     * 1. destroy() sends SIGTERM (allows Maven/Gradle/Docker to release locks and clean up)
     * 2. Wait up to GRACEFUL_KILL_WAIT_MS for process to exit
     * 3. destroyForcibly() sends SIGKILL if still alive
     * Also attempts to kill child process tree via ProcessHandle API.
     */
    private fun gracefulKill(process: Process) {
        // Kill child processes first (prevents orphans)
        try {
            process.toHandle().descendants().forEach { child ->
                try { child.destroy() } catch (_: Exception) {}
            }
        } catch (_: Exception) {
            // ProcessHandle may not be available on all JVMs
        }

        // Phase 1: SIGTERM (graceful)
        process.destroy()

        // Phase 2: wait, then SIGKILL if needed
        try {
            if (!process.waitFor(GRACEFUL_KILL_WAIT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                log.info("[ProcessRegistry] Process did not exit after ${GRACEFUL_KILL_WAIT_MS}ms SIGTERM, sending SIGKILL")
                process.destroyForcibly()
                process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)
            }
        } catch (_: InterruptedException) {
            process.destroyForcibly()
        }
    }

    private const val GRACEFUL_KILL_WAIT_MS = 5000L
}
