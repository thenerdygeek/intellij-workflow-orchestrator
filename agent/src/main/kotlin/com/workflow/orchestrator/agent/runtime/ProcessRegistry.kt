package com.workflow.orchestrator.agent.runtime

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
    val stdinCount: AtomicInteger = AtomicInteger(0)
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

    fun kill(toolCallId: String): Boolean {
        val managed = running.remove(toolCallId) ?: return false
        log.info("[ProcessRegistry] Killing process for tool call: $toolCallId")
        managed.process.destroyForcibly()
        return true
    }

    fun killAll() {
        log.info("[ProcessRegistry] Killing all ${running.size} running processes")
        running.forEach { (id, managed) ->
            managed.process.destroyForcibly()
            log.info("[ProcessRegistry] Killed process: $id")
        }
        running.clear()
    }

    /**
     * Finds all processes where [ManagedProcess.idleSignaledAt] > 0 and
     * the time since idle signal exceeds [maxIdleSinceSignalMs], then kills and removes them.
     */
    fun reapIdleProcesses(maxIdleSinceSignalMs: Long = 60_000) {
        val now = System.currentTimeMillis()
        val toReap = running.entries.filter { (_, managed) ->
            val idleAt = managed.idleSignaledAt.get()
            idleAt > 0 && (now - idleAt) > maxIdleSinceSignalMs
        }
        for ((id, managed) in toReap) {
            running.remove(id)
            managed.process.destroyForcibly()
            log.info("[ProcessRegistry] Reaped idle process: $id (idleSignaledAt=${managed.idleSignaledAt.get()})")
        }
    }

    /**
     * Returns all processes whose [ManagedProcess.lastOutputAt] is non-zero and
     * at least [thresholdMs] milliseconds in the past (i.e. idle based on output activity).
     */
    fun getIdleProcesses(thresholdMs: Long): List<ManagedProcess> {
        val now = System.currentTimeMillis()
        return running.values.filter { managed ->
            val lastOutput = managed.lastOutputAt.get()
            lastOutput > 0 && (now - lastOutput) >= thresholdMs
        }
    }

    fun isRunning(toolCallId: String): Boolean {
        val managed = running[toolCallId] ?: return false
        return managed.process.isAlive
    }

    fun runningCount(): Int = running.size
}
