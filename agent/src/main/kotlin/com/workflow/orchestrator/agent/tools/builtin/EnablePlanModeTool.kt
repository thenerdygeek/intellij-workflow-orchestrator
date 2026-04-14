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
 * Allows the LLM to programmatically switch to PLAN MODE.
 *
 * The system prompt says "You CAN suggest entering PLAN MODE" but previously
 * there was no tool for the LLM to actually do it. Skills like writing-plans
 * reference enable_plan_mode but the tool didn't exist.
 *
 * Flow:
 * 1. LLM recognizes a complex task needing planning
 * 2. LLM calls enable_plan_mode with a reason
 * 3. AgentLoop sees enablePlanMode=true on the ToolResult
 * 4. AgentService.planModeActive is set to true
 * 5. Tool definitions are rebuilt (write tools removed, plan_mode_respond added)
 * 6. LLM can now use plan_mode_respond to present plans
 *
 * Note: Only the user can switch BACK to act mode (via UI approve button).
 */
class EnablePlanModeTool : AgentTool {

    override val name = "enable_plan_mode"

    override val description = "Switch to PLAN MODE for structured planning before implementation. " +
        "Use this when a task is complex and would benefit from creating a detailed plan first. " +
        "After enabling plan mode, use plan_mode_respond to present your plan to the user. " +
        "Only the user can switch back to ACT MODE by approving the plan."

    override val parameters = FunctionParameters(
        properties = mapOf(
            "reason" to ParameterProperty(
                type = "string",
                description = "Brief explanation of why plan mode is needed for this task."
            )
        ),
        required = listOf("reason")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR, WorkerType.CODER, WorkerType.ANALYZER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val reason = params["reason"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Missing required parameter: reason",
                summary = "enable_plan_mode failed: missing reason",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return ToolResult.planModeToggle(
            content = "Switched to PLAN MODE. Reason: $reason\n\n" +
                "You are now in PLAN MODE. Use plan_mode_respond to present your plan. " +
                "Read and explore relevant files first, then present a concrete plan.",
            summary = "Switched to plan mode: $reason",
            tokenEstimate = 20
        )
    }
}
