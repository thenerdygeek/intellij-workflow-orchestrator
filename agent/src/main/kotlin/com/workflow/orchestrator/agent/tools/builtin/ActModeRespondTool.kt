package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Act mode progress response tool — faithful port of Cline's act_mode_respond.
 *
 * Cline source: src/core/prompts/system-prompt/tools/act_mode_respond.ts
 *
 * This tool is only available in ACT MODE. It provides a brief, non-blocking
 * progress update or preamble to the user during execution. Unlike
 * attempt_completion, this does NOT end the loop — the LLM continues working.
 *
 * Critical constraint from Cline: This tool cannot be called consecutively.
 * Each use must be followed by a different tool call or completion. The
 * AgentLoop enforces this by tracking the last tool name.
 *
 * When to use (from Cline):
 * - Explaining analysis before making file edits
 * - Transitioning between work phases
 * - Providing updates during extended operations
 * - Clarifying strategy changes mid-task
 */
class ActModeRespondTool : AgentTool {

    override val name = "act_mode_respond"

    // Ported from Cline's tool description
    override val description = "Provide a progress update or preamble to the user during ACT MODE execution. " +
        "Use this tool to explain your analysis, transition between work phases, or provide updates during " +
        "extended operations. This tool is only available in ACT MODE and CANNOT be used consecutively — " +
        "each use must be followed by a different tool call or attempt_completion. " +
        "Keep responses brief and conversational."

    override val parameters = FunctionParameters(
        properties = mapOf(
            "response" to ParameterProperty(
                type = "string",
                description = "A brief progress update or preamble explaining what you're about to do or " +
                    "what you've found so far. Keep it conversational and concise — this is not a final " +
                    "result, just a status update."
            ),
            "task_progress" to ParameterProperty(
                type = "string",
                description = "A checklist showing task progress after this tool use is completed. " +
                    "Use standard Markdown checklist format."
            )
        ),
        required = listOf("response")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val response = params["response"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Missing required parameter: response",
                summary = "act_mode_respond failed: missing response",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return ToolResult(
            content = response,
            summary = "Progress: ${response.take(200)}",
            tokenEstimate = response.length / 4
        )
    }
}
