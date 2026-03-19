package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class UpdatePlanStepTool : AgentTool {
    override val name = "update_plan_step"
    override val description = "Update the status of a plan step during execution. Call this as you start and complete each step in an approved plan."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "step_id" to ParameterProperty(type = "string", description = "The step ID from the plan (e.g., '1', '2')"),
            "status" to ParameterProperty(type = "string", description = "New status: 'running' (starting step), 'done' (step complete), or 'failed' (step failed)")
        ),
        required = listOf("step_id", "status")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val stepId = params["step_id"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'step_id' required", "Error: missing step_id", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val status = params["status"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'status' required", "Error: missing status", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        if (status !in setOf("running", "done", "failed")) {
            return ToolResult("Error: status must be 'running', 'done', or 'failed'", "Invalid status", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val planManager = try {
            AgentService.getInstance(project).currentPlanManager
        } catch (_: Exception) { null }

        if (planManager == null || !planManager.hasPlan()) {
            return ToolResult("Error: no active plan", "No plan", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        planManager.updateStepStatus(stepId, status)
        return ToolResult("Step $stepId marked as $status.", "Step $stepId: $status", 5)
    }
}
