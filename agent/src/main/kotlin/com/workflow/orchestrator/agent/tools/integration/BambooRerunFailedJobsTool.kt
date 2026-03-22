package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class BambooRerunFailedJobsTool : AgentTool {
    override val name = "bamboo_rerun_failed_jobs"
    override val description = "Rerun failed jobs in a Bamboo build. Only reruns the jobs that previously failed."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "plan_key" to ParameterProperty(type = "string", description = "Bamboo plan key (e.g., PROJ-PLAN)"),
            "build_number" to ParameterProperty(type = "string", description = "Build number to rerun failed jobs for")
        ),
        required = listOf("plan_key", "build_number")
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val planKey = params["plan_key"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'plan_key' parameter required", "Error: missing plan_key", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        val buildNumber = params["build_number"]?.jsonPrimitive?.content?.toIntOrNull()
            ?: return ToolResult("Error: 'build_number' must be a valid integer", "Error: invalid build_number", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        ToolValidation.validateBambooPlanKey(planKey)?.let { return it }
        val service = ServiceLookup.bamboo(project) ?: return ServiceLookup.notConfigured("Bamboo")

        return service.rerunFailedJobs(planKey, buildNumber).toAgentToolResult()
    }
}
