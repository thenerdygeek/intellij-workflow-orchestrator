package com.workflow.orchestrator.agent.tools.debug

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Gets the current state of a debug session including pause status,
 * position, and thread information. Read-only inspection tool.
 */
class GetDebugStateTool(
    private val controller: AgentDebugController
) : AgentTool {
    override val name = "get_debug_state"
    override val description = "Get the current state of a debug session including pause status, position, and thread information"
    override val parameters = FunctionParameters(
        properties = mapOf(
            "session_id" to ParameterProperty(
                type = "string",
                description = "Debug session ID (optional — uses active session if omitted)"
            )
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        val resolvedId = sessionId ?: controller.getActiveSessionId()

        val session = controller.getSession(sessionId)
            ?: return ToolResult(
                "No debug session found${sessionId?.let { ": $it" } ?: ""}. Start one with start_debug_session.",
                "No session",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return try {
            val sb = StringBuilder()
            sb.append("Session: ${resolvedId ?: "unknown"}\n")

            // Determine status
            val isStopped = session.isStopped
            val isSuspended = session.isSuspended
            val pos = session.currentPosition

            val status = when {
                isStopped -> "STOPPED"
                isSuspended && pos != null -> {
                    val file = pos.file.name
                    val line = pos.line + 1 // 0-based to 1-based
                    "PAUSED at $file:$line"
                }
                isSuspended -> "PAUSED"
                else -> "RUNNING"
            }
            sb.append("Status: $status\n")

            // Add pause reason if suspended
            if (isSuspended) {
                sb.append("Reason: breakpoint\n")
            }

            // Thread information from suspend context
            val suspendContext = session.suspendContext
            if (suspendContext != null && isSuspended) {
                val activeStack = suspendContext.activeExecutionStack
                val allStacks = suspendContext.executionStacks

                val totalThreads = allStacks.size
                // Count suspended threads (all visible stacks in a suspend context are suspended)
                val suspendedCount = allStacks.size.coerceAtLeast(1)

                sb.append("Suspended threads: $suspendedCount of $totalThreads\n")

                // Show active thread details
                if (activeStack != null) {
                    val threadName = activeStack.displayName
                    val frameDesc = if (pos != null) {
                        val currentFrame = session.currentStackFrame
                        val file = pos.file.name
                        val line = pos.line + 1
                        "$currentFrame".takeIf { it != "null" }
                            ?: "$file:$line"
                    } else {
                        "unknown position"
                    }
                    sb.append("  $threadName (SUSPENDED) at $frameDesc\n")
                }

                // Show other threads briefly
                allStacks.filter { it != activeStack }.take(5).forEach { stack ->
                    sb.append("  ${stack.displayName}\n")
                }
            }

            val content = sb.toString().trimEnd()
            ToolResult(content, "Debug state: $status", TokenEstimator.estimate(content))
        } catch (e: Exception) {
            ToolResult(
                "Error getting debug state: ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}
