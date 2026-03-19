package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.AgentPlan
import com.workflow.orchestrator.agent.runtime.PlanApprovalResult
import com.workflow.orchestrator.agent.runtime.PlanStep
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class CreatePlanTool : AgentTool {
    override val name = "create_plan"
    override val description = "Create an implementation plan before making changes. Use for complex tasks involving 3+ files, refactoring, or new features. The plan will be shown to the user for review and approval before execution begins. Do NOT use for simple questions or single-file fixes."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "goal" to ParameterProperty(type = "string", description = "What the task aims to achieve (1-2 sentences)"),
            "approach" to ParameterProperty(type = "string", description = "High-level strategy for solving the problem"),
            "steps" to ParameterProperty(type = "string", description = "JSON array of steps. Each step: {\"id\":\"1\",\"title\":\"Step title\",\"description\":\"What this step does\",\"files\":[\"path/to/file.kt\"],\"action\":\"read|edit|create|verify\"}"),
            "testing" to ParameterProperty(type = "string", description = "How to verify the implementation works")
        ),
        required = listOf("goal", "steps")
    )
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR)

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val goal = params["goal"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'goal' parameter required", "Error: missing goal", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val stepsJson = params["steps"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'steps' parameter required", "Error: missing steps", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val approach = params["approach"]?.jsonPrimitive?.content ?: ""
        val testing = params["testing"]?.jsonPrimitive?.content ?: ""

        val steps = try {
            json.decodeFromString<List<PlanStep>>(stepsJson)
        } catch (e: Exception) {
            return ToolResult(
                "Error: invalid steps JSON: ${e.message}. Expected format: [{\"id\":\"1\",\"title\":\"...\",\"files\":[\"...\"],\"action\":\"edit\"}]",
                "Error: invalid steps", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
            )
        }

        if (steps.isEmpty()) {
            return ToolResult("Error: plan must have at least one step", "Error: empty plan", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val planManager = try {
            AgentService.getInstance(project).currentPlanManager
        } catch (_: Exception) { null }

        if (planManager == null) {
            return ToolResult("Error: no active session for plan management", "Error: no session", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val plan = AgentPlan(goal = goal, approach = approach, steps = steps, testing = testing)

        // Submit plan and BLOCK until user approves or revises
        // This runs on Dispatchers.IO so blocking is acceptable
        val result = planManager.submitPlan(plan).get()

        return when (result) {
            is PlanApprovalResult.Approved -> {
                ToolResult(
                    content = "Plan approved by user. Execute the plan step by step. As you work on each step, call update_plan_step with the step ID and status ('running' when starting, 'done' when complete, 'failed' if it fails).",
                    summary = "Plan approved (${steps.size} steps)",
                    tokenEstimate = 30
                )
            }
            is PlanApprovalResult.Revised -> {
                val comments = result.comments.entries.joinToString("\n") { "- Step ${it.key}: ${it.value}" }
                ToolResult(
                    content = "User requested revisions to the plan. Their feedback:\n$comments\n\nPlease create a revised plan by calling create_plan again with updated steps incorporating this feedback.",
                    summary = "Plan revision requested",
                    tokenEstimate = TokenEstimator.estimate(comments),
                    isError = true
                )
            }
        }
    }
}
