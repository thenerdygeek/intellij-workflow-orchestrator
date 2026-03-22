package com.workflow.orchestrator.agent.tools.integration

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject

class BambooGetPlansTool : AgentTool {
    override val name = "bamboo_get_plans"
    override val description = "List all Bamboo build plans visible to the authenticated user. Shows plan key, name, and project."
    override val parameters = FunctionParameters(
        properties = emptyMap(),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val service = ServiceLookup.bamboo(project) ?: return ServiceLookup.notConfigured("Bamboo")
        return service.getPlans().toAgentToolResult()
    }
}
