package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Lightweight completion signal for worker sessions (subagents).
 *
 * Analogous to [AttemptCompletionTool] for the orchestrator, but without
 * CompletionGatekeeper dependency — workers have no plan/self-correction gates.
 *
 * Available to ALL worker types including ORCHESTRATOR (used by general-purpose
 * and custom agents). [AttemptCompletionTool] is NOT in the ToolRegistry —
 * it is injected directly into [SingleAgentSession]'s tool set — so there is
 * no collision risk.
 */
class WorkerCompleteTool : AgentTool {

    override val name = "worker_complete"

    override val description = "Signal that you have finished your assigned task. " +
        "The result parameter is the ONLY output the orchestrator receives from you — " +
        "your tool call history is not visible to it. Include everything the orchestrator " +
        "needs: full findings, file paths with line numbers, code changes made, errors " +
        "encountered and resolutions. Do not truncate or summarize.\n\n" +
        "Call this when ALL work is done. Do not call if you have more tool calls to make.\n\n" +
        "Example: After editing 2 files and running diagnostics, call worker_complete with " +
        "the complete report of what was changed, where, and the diagnostics results."

    override val parameters = FunctionParameters(
        properties = mapOf(
            "result" to ParameterProperty(
                type = "string",
                description = "The complete output of your task — returned directly to the " +
                    "orchestrator as your only deliverable. Must contain everything it needs: " +
                    "all file paths with line numbers, code written or changed, all findings, " +
                    "errors found, decisions made, and verification results. " +
                    "Do NOT truncate or summarize — provide the full detail."
            )
        ),
        required = listOf("result")
    )

    // ALL worker types, including ORCHESTRATOR (used by general-purpose and custom agents).
    // No collision with attempt_completion — that tool is injected directly into
    // SingleAgentSession's tool set, NOT registered in ToolRegistry.
    override val allowedWorkers = setOf(
        WorkerType.ORCHESTRATOR,
        WorkerType.ANALYZER,
        WorkerType.CODER,
        WorkerType.REVIEWER,
        WorkerType.TOOLER
    )

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val result = params["result"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Missing required parameter: result",
                summary = "worker_complete failed: missing result",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        if (result.isBlank()) {
            return ToolResult(
                content = "result parameter must not be empty — include the complete output of your task",
                summary = "worker_complete failed: empty result",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        return ToolResult(
            content = result,
            summary = "Worker completed: ${result.take(200)}",
            tokenEstimate = result.length / 4,
            isCompletion = true
        )
    }
}
