package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import kotlinx.serialization.json.JsonObject

/**
 * Allows the LLM to discard a previously presented plan without replacing it.
 *
 * This tool is only available in PLAN MODE. Use it when:
 * - The user pushes back on the current plan and the LLM has no replacement ready.
 * - Exploration revealed the plan approach was wrong.
 * - The scope or requirements changed materially after the plan was presented.
 *
 * After calling this tool, continue the conversation with plain text or further
 * exploration. To present a new plan, call plan_mode_respond when ready.
 *
 * Contrast with plan_mode_respond (which overwrites the plan card) — this tool
 * clears the card without presenting a replacement.
 */
class DiscardPlanTool : AgentTool {

    override val name = "discard_plan"

    override val description = "Discard the currently presented plan without presenting a replacement. " +
        "Use this in PLAN MODE when the prior plan is no longer valid — the user pushed back, " +
        "exploration revealed the approach is wrong, or scope changed — and you do not have a new plan ready. " +
        "After calling this tool, continue the conversation with plain text or further exploration. " +
        "To present a new plan, use plan_mode_respond when you are ready."

    override val parameters = FunctionParameters(properties = emptyMap(), required = emptyList())

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        return ToolResult.planDiscarded(
            content = "Plan discarded. Continue the conversation with plain text or explore further before presenting a new plan.",
            summary = "Plan discarded by LLM"
        )
    }
}
