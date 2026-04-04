package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonPrimitive

/**
 * Plan mode response tool — faithful port of Cline's plan_mode_respond.
 *
 * Cline source: src/core/prompts/system-prompt/tools/plan_mode_respond.ts
 *
 * This tool is only available in PLAN MODE. The LLM uses it to present a
 * concrete plan after exploring relevant files. The plan is returned to the
 * user for review. If needs_more_exploration is true, the loop continues
 * to let the LLM explore more before presenting a final plan.
 *
 * Flow:
 * 1. LLM explores code (reads, searches) in plan mode
 * 2. LLM calls plan_mode_respond with its plan
 * 3. If needs_more_exploration=true, loop continues for more exploration
 * 4. If needs_more_exploration=false (default), loop pauses for user review
 * 5. User reviews, gives feedback or approves
 * 6. On approval, switches to act mode to implement
 */
class PlanModeRespondTool : AgentTool {

    override val name = "plan_mode_respond"

    // Ported from Cline's generic variant description
    override val description = "Respond to the user's inquiry in an effort to plan a solution to the user's task. " +
        "This tool should ONLY be used when you have already explored the relevant files and are ready to present " +
        "a concrete plan. DO NOT use this tool to announce what files you're going to read - just read them first. " +
        "This tool is only available in PLAN MODE. The environment_details will specify the current mode; if it is " +
        "not PLAN_MODE then you should not use this tool.\n" +
        "However, if while writing your response you realize you actually need to do more exploration before " +
        "providing a complete plan, you can add the optional needs_more_exploration parameter to indicate this. " +
        "This allows you to acknowledge that you should have done more exploration first, and signals that your " +
        "next message will use exploration tools instead."

    override val parameters = FunctionParameters(
        properties = mapOf(
            "response" to ParameterProperty(
                type = "string",
                description = "The response to provide to the user. Do not try to use tools in this parameter, " +
                    "this is simply a chat response. (You MUST use the response parameter, do not simply place " +
                    "the response text directly within plan_mode_respond.)"
            ),
            "needs_more_exploration" to ParameterProperty(
                type = "boolean",
                description = "Set to true if while formulating your response you found you need to do more " +
                    "exploration with tools, for example reading files. (Remember, you can explore the project " +
                    "with tools like read_file in PLAN MODE without the user having to toggle to ACT MODE.) " +
                    "Defaults to false if not specified."
            ),
            "task_progress" to ParameterProperty(
                type = "string",
                description = "A checklist showing task progress after this tool use is completed. If you have " +
                    "presented the user with concrete steps or requirements, you can optionally include a todo " +
                    "list outlining these steps."
            )
        ),
        required = listOf("response")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val response = params["response"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Missing required parameter: response",
                summary = "plan_mode_respond failed: missing response",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val needsMoreExploration = try {
            params["needs_more_exploration"]?.jsonPrimitive?.boolean ?: false
        } catch (_: Exception) {
            // Handle string "true"/"false" since some models send booleans as strings
            params["needs_more_exploration"]?.jsonPrimitive?.content?.equals("true", ignoreCase = true) ?: false
        }

        return ToolResult(
            content = response,
            summary = if (needsMoreExploration) {
                "Plan draft (needs more exploration): ${response.take(200)}"
            } else {
                "Plan presented: ${response.take(200)}"
            },
            tokenEstimate = response.length / 4,
            isPlanResponse = true,
            needsMoreExploration = needsMoreExploration
        )
    }
}
