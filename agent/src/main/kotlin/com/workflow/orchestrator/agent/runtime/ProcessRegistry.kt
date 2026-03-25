package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracks running processes by tool call ID for kill/killAll support.
 * Used by RunCommandTool for streaming output and process lifecycle management.
 */
object ProcessRegistry {

    private val log = Logger.getInstance(ProcessRegistry::class.java)
    private val running = ConcurrentHashMap<String, Process>()

    fun register(toolCallId: String, process: Process) {
        running[toolCallId] = process
        log.info("[ProcessRegistry] Registered process for tool call: $toolCallId")
    }

    fun unregister(toolCallId: String) {
        running.remove(toolCallId)
    }

    fun kill(toolCallId: String): Boolean {
        val process = running.remove(toolCallId) ?: return false
        log.info("[ProcessRegistry] Killing process for tool call: $toolCallId")
        process.destroyForcibly()
        return true
    }

    fun killAll() {
        log.info("[ProcessRegistry] Killing all ${running.size} running processes")
        running.forEach { (id, process) ->
            process.destroyForcibly()
            log.info("[ProcessRegistry] Killed process: $id")
        }
        running.clear()
    }

    fun isRunning(toolCallId: String): Boolean = running.containsKey(toolCallId)

    fun runningCount(): Int = running.size
}
