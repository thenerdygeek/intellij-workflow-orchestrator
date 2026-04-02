package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.orchestrator.PromptAssembler
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class EnablePlanModeTool : AgentTool {
    override val name = "enable_plan_mode"
    override val description = """
        Switch to plan mode when you determine a task requires thorough planning.
        Call this BEFORE create_plan when you realize the task involves 3+ files,
        architectural decisions, cross-module changes, or complex multi-step work.
        This enforces mandatory planning for the rest of the session and highlights
        the plan button in the UI so the user knows planning is active.
        Do NOT call this for simple questions, single-file fixes, or status checks.
    """.trimIndent()

    override val parameters = FunctionParameters(
        properties = mapOf(
            "reason" to ParameterProperty(
                type = "string",
                description = "Why this task requires plan mode (e.g., 'Refactoring spans 6 files across 3 modules with DB migration')"
            )
        ),
        required = listOf("reason")
    )

    // Only the orchestrator (main agent) should toggle plan mode — not subagents.
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val reason = params["reason"]?.jsonPrimitive?.content?.trim()
            ?: return ToolResult(
                content = "Error: 'reason' parameter is required.",
                summary = "enable_plan_mode failed: missing reason",
                tokenEstimate = TokenEstimator.estimate("Error: 'reason' parameter is required."),
                isError = true
            )

        val agentService = try {
            AgentService.getInstance(project)
        } catch (_: Exception) {
            return ToolResult(
                content = "Error: could not access AgentService.",
                summary = "enable_plan_mode failed: no AgentService",
                tokenEstimate = 20,
                isError = true
            )
        }

        // Inject FORCED_PLANNING_RULES into the live conversation context.
        // Same pattern as LoopGuard / BudgetEnforcer mid-loop system message injection.
        agentService.currentContextBridge?.addSystemMessage(PromptAssembler.FORCED_PLANNING_RULES)

        // Set the mechanical enforcement flag — SingleAgentSession will filter tools on next iteration
        AgentService.planModeActive.set(true)

        // Auto-activate the planning skill (compression-proof anchor with methodology)
        agentService.currentSkillManager?.activateSkill("planning")

        // Notify the controller → sets planModeEnabled = true + highlights the UI button.
        agentService.onPlanModeEnabled?.invoke(true)

        val result = "Plan mode enabled. Planning skill loaded — follow the structured planning workflow. Reason: $reason"
        return ToolResult(
            content = result,
            summary = result,
            tokenEstimate = TokenEstimator.estimate(result)
        )
    }

}
