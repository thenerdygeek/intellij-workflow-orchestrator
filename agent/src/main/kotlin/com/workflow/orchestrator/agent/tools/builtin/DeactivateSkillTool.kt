package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.serialization.json.JsonObject

class DeactivateSkillTool : AgentTool {
    override val name = "deactivate_skill"
    override val description = "Deactivate the current active skill when the workflow is complete."
    override val parameters = FunctionParameters(
        properties = emptyMap(),
        required = emptyList()
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val skillManager = AgentService.getInstance(project).currentSkillManager
            ?: return ToolResult("Error: no skill manager available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        skillManager.deactivateSkill()

        return ToolResult(
            content = "Skill deactivated.",
            summary = "Skill deactivated",
            tokenEstimate = 2
        )
    }
}
