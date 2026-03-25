package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class BambooTriggerStageTool : AgentTool {
    override val name = "bamboo_trigger_stage"
    override val description = "Trigger a specific stage in a Bamboo plan build, optionally with custom variables."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "plan_key" to ParameterProperty(type = "string", description = "Bamboo plan key (e.g., PROJ-PLAN)"),
            "stage" to ParameterProperty(type = "string", description = "Stage name to trigger (optional — triggers from beginning if omitted)"),
            "variables" to ParameterProperty(type = "string", description = "Optional JSON object of build variables (e.g., '{\"key\":\"value\"}')"),
            "description" to ParameterProperty(type = "string", description = "Brief description of what this action does and why (shown to user in approval dialog)")
        ),
        required = listOf("plan_key", "description")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val planKey = params["plan_key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'plan_key' parameter required", "Error: missing plan_key", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val stage = params["stage"]?.jsonPrimitive?.content

        ToolValidation.validateBambooPlanKey(planKey)?.let { return it }
        val service = ServiceLookup.bamboo(project) ?: return ServiceLookup.notConfigured("Bamboo")

        val variablesStr = params["variables"]?.jsonPrimitive?.content
        val variables = if (!variablesStr.isNullOrBlank()) {
            try {
                val obj = kotlinx.serialization.json.Json.parseToJsonElement(variablesStr).jsonObject
                obj.mapValues { it.value.jsonPrimitive.content }
            } catch (_: Exception) {
                return ToolResult(
                    content = "Invalid variables JSON: '$variablesStr'. Expected format: {\"key\":\"value\"}",
                    summary = "Invalid variables", tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE, isError = true
                )
            }
        } else emptyMap()

        return service.triggerStage(planKey, variables, stage).toAgentToolResult()
    }
}
