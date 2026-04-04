package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.prompt.InstructionLoader
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import com.workflow.orchestrator.agent.tools.WorkerType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Skill activation tool — faithful port of Cline's use_skill.
 *
 * Cline source: src/core/prompts/system-prompt/tools/use_skill.ts
 *
 * When the user's request matches a skill description shown in the SKILLS
 * section of the system prompt, the LLM calls this tool ONCE with the exact
 * skill name. The tool loads the full skill content and returns it as the
 * tool result. The LLM then follows the skill instructions without
 * re-invoking the tool.
 *
 * The skill content is also stored as the active skill in the ContextManager
 * so it survives compaction (re-injected after compaction).
 *
 * From Cline's description: "This tool should be invoked ONCE when a user's
 * request matches one of the available skill descriptions shown in the SKILLS
 * section of your system prompt."
 */
class UseSkillTool : AgentTool {

    override val name = "use_skill"

    // Ported from Cline's use_skill tool description
    override val description = "Load and activate a specialized skill by name. This tool should be invoked ONCE " +
        "when a user's request matches one of the available skill descriptions shown in the SKILLS section of " +
        "your system prompt. The skill_name MUST match one of the available skill names exactly. After invoking " +
        "this tool, follow the instructions returned without re-invoking the tool."

    override val parameters = FunctionParameters(
        properties = mapOf(
            "skill_name" to ParameterProperty(
                type = "string",
                description = "The exact name of the skill to activate. Must match one of the available " +
                    "skill names listed in the SKILLS section of the system prompt."
            )
        ),
        required = listOf("skill_name")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val skillName = params["skill_name"]?.jsonPrimitive?.content
            ?: return ToolResult(
                content = "Missing required parameter: skill_name",
                summary = "use_skill failed: missing skill_name",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        val skillContent = InstructionLoader.loadSkillContent(skillName)
            ?: return ToolResult(
                content = "Unknown skill: '$skillName'. Check the SKILLS section of the system prompt " +
                    "for available skill names.",
                summary = "use_skill failed: unknown skill '$skillName'",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )

        return ToolResult(
            content = "Skill '$skillName' activated. Follow these instructions:\n\n$skillContent",
            summary = "Activated skill: $skillName",
            tokenEstimate = skillContent.length / 4,
            isSkillActivation = true,
            activatedSkillName = skillName,
            activatedSkillContent = skillContent
        )
    }
}
