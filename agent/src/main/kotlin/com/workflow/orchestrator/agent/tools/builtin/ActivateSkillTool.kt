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

class ActivateSkillTool : AgentTool {
    override val name = "activate_skill"
    override val description = "Activate a user-defined skill by name. Skills provide workflow instructions for specific tasks. Check the available skills listed in your context."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "name" to ParameterProperty(type = "string", description = "The skill name to activate"),
            "arguments" to ParameterProperty(type = "string", description = "Optional arguments passed to the skill")
        ),
        required = listOf("name")
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val skillName = params["name"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'name' parameter required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val skillManager = AgentService.getInstance(project).currentSkillManager
            ?: return ToolResult("Error: no skill manager available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val entry = skillManager.registry.getSkill(skillName)
            ?: return ToolResult("Error: skill '$skillName' not found", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        if (entry.disableModelInvocation) {
            return ToolResult(
                "Error: skill '$skillName' has disabled model invocation. It can only be activated by the user.",
                "Error",
                ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        val arguments = params["arguments"]?.jsonPrimitive?.content

        skillManager.activateSkill(skillName, arguments)
            ?: return ToolResult("Error: failed to activate skill '$skillName'", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        return ToolResult(
            content = "Skill '$skillName' activated. Follow the skill instructions in your context.",
            summary = "Activated skill: $skillName",
            tokenEstimate = 5
        )
    }
}
