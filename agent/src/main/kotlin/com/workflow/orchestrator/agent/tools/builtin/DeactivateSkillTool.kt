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
    override val description = "Deactivate the currently active skill, removing its instructions from the conversation context and freeing the token budget it occupied. Use this after completing a skill's workflow or when the skill is no longer relevant to the current task. Do NOT use this to switch skills — simply activate the new skill directly and the previous one will be replaced. Has no effect if no skill is currently active."
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
