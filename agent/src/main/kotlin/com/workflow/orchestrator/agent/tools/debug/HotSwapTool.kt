package com.workflow.orchestrator.agent.tools.debug

import com.intellij.debugger.DebuggerManagerEx
import com.intellij.debugger.impl.DebuggerSession
import com.intellij.debugger.ui.HotSwapUI
import com.intellij.debugger.ui.HotSwapUIImpl
import com.intellij.debugger.ui.HotSwapStatusListener
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlin.coroutines.resume

/**
 * Apply code changes to a running debug session without restarting. Compiles
 * changed files and reloads classes. Only method body changes are supported —
 * adding/removing methods, fields, or changing signatures will fail.
 */
class HotSwapTool(
    private val controller: AgentDebugController
) : AgentTool {
    override val name = "hotswap"
    override val description = "Apply code changes to a running debug session without restarting. " +
        "Compiles changed files and reloads classes. Only method body changes are supported — " +
        "adding/removing methods, fields, or changing signatures will fail."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "session_id" to ParameterProperty(
                type = "string",
                description = "Debug session ID (optional — uses active session if omitted)"
            ),
            "compile_first" to ParameterProperty(
                type = "boolean",
                description = "Whether to compile changed files before reloading (default true)"
            )
        ),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.CODER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val sessionId = params["session_id"]?.jsonPrimitive?.content
        val compileFirst = params["compile_first"]?.jsonPrimitive?.booleanOrNull ?: true

        // Verify a debug session exists
        controller.getSession(sessionId)
            ?: return ToolResult(
                "No debug session found${sessionId?.let { ": $it" } ?: ""}. Start one with start_debug_session.",
                "No session",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return try {
            val hotSwapUI = HotSwapUI.getInstance(project)
            val debuggerManager = DebuggerManagerEx.getInstanceEx(project)
            val debuggerSession = debuggerManager.sessions.firstOrNull()
                ?: return ToolResult(
                    "No active debugger session found in DebuggerManagerEx.",
                    "No debugger session",
                    ToolResult.ERROR_TOKEN_ESTIMATE,
                    isError = true
                )

            // Wait up to 60s for hot swap to complete (compilation + class reload can be slow)
            val status = withTimeoutOrNull(60_000L) {
                suspendCancellableCoroutine { cont ->
                    (hotSwapUI as HotSwapUIImpl).reloadChangedClasses(
                        debuggerSession, compileFirst,
                        object : HotSwapStatusListener {
                            override fun onSuccess(sessions: MutableList<DebuggerSession>) {
                                cont.resume("success")
                            }

                            override fun onFailure(sessions: MutableList<DebuggerSession>) {
                                cont.resume("failure")
                            }

                            override fun onCancel(sessions: MutableList<DebuggerSession>) {
                                cont.resume("cancelled")
                            }

                            override fun onNothingToReload(sessions: MutableList<DebuggerSession>) {
                                cont.resume("nothing_to_reload")
                            }
                        }
                    )
                }
            } ?: "timeout"

            val resolvedId = sessionId ?: controller.getActiveSessionId() ?: "unknown"
            val content = buildString {
                append("Hot swap result: $status\n")
                append("Session: $resolvedId\n")
                append("Compile first: $compileFirst\n")
                when (status) {
                    "success" -> append("Classes reloaded successfully. Execution continues with new code.")
                    "failure" -> append("Hot swap failed. Check for structural changes (new/removed methods, fields, or signature changes).")
                    "cancelled" -> append("Hot swap was cancelled by the user or IDE.")
                    "nothing_to_reload" -> append("No changed classes detected. Make code changes first.")
                    "timeout" -> append("Hot swap timed out after 60 seconds. Check compilation and IDE status.")
                }
            }

            val isError = status == "failure" || status == "timeout"
            ToolResult(content, "Hot swap: $status", TokenEstimator.estimate(content), isError = isError)
        } catch (e: Exception) {
            ToolResult(
                "Error during hot swap: ${e.message}",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }
    }
}
