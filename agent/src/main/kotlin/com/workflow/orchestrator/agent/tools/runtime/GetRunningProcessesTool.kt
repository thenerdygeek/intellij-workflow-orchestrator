package com.workflow.orchestrator.agent.tools.runtime

import com.intellij.execution.ExecutionManager
import com.intellij.execution.process.ProcessHandler
import com.intellij.openapi.project.Project
import com.intellij.xdebugger.XDebuggerManager
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject

class GetRunningProcessesTool : AgentTool {
    override val name = "get_running_processes"
    override val description = "List currently active run/debug sessions in the IDE"
    override val parameters = FunctionParameters(
        properties = emptyMap(),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        return try {
            val entries = mutableListOf<ProcessEntry>()

            // Get running processes from ExecutionManager
            val runningProcesses = ExecutionManager.getInstance(project).getRunningProcesses()
            for (handler in runningProcesses) {
                val name = extractProcessName(handler)
                val isDestroyed = handler.isProcessTerminated || handler.isProcessTerminating
                if (!isDestroyed) {
                    entries.add(ProcessEntry(
                        name = name,
                        type = "Running",
                        status = "Active",
                        pid = extractPid(handler)
                    ))
                }
            }

            // Get debug sessions
            val debugSessions = XDebuggerManager.getInstance(project).debugSessions
            for (session in debugSessions) {
                val sessionName = session.sessionName
                val isStopped = session.isStopped
                // Avoid duplicates if already listed as a running process
                if (!isStopped && entries.none { it.name == sessionName && it.type == "Debug" }) {
                    val isPaused = session.isPaused
                    val status = when {
                        isPaused -> "Paused (at breakpoint)"
                        else -> "Active"
                    }
                    entries.add(ProcessEntry(
                        name = sessionName,
                        type = "Debug",
                        status = status,
                        pid = null
                    ))
                }
            }

            if (entries.isEmpty()) {
                return ToolResult(
                    "No active run/debug sessions.",
                    "No processes",
                    10
                )
            }

            val sb = StringBuilder()
            sb.appendLine("Active Sessions (${entries.size}):")
            sb.appendLine()

            for (entry in entries) {
                sb.appendLine("${entry.name}")
                sb.appendLine("  Type: ${entry.type}")
                sb.appendLine("  Status: ${entry.status}")
                entry.pid?.let { sb.appendLine("  PID: $it") }
                sb.appendLine()
            }

            val content = sb.toString().trimEnd()
            ToolResult(content, "${entries.size} active sessions", content.length / 4)
        } catch (e: Exception) {
            ToolResult("Error listing processes: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }

    private fun extractProcessName(handler: ProcessHandler): String {
        return try {
            // Try to get the command line or a meaningful name
            handler.toString()
        } catch (_: Exception) {
            "Unknown process"
        }
    }

    private fun extractPid(handler: ProcessHandler): Long? {
        return try {
            val method = handler.javaClass.methods.find { it.name == "getProcess" }
            val process = method?.invoke(handler) as? Process
            process?.pid()
        } catch (_: Exception) {
            null
        }
    }

    private data class ProcessEntry(
        val name: String,
        val type: String,
        val status: String,
        val pid: Long?
    )
}
