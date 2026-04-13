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
 * Cline source:
 *   Tool spec: src/core/prompts/system-prompt/tools/use_skill.ts
 *   Handler:   src/core/task/tools/handlers/UseSkillToolHandler.ts
 *
 * On invocation:
 *   1. Re-discovers all skills (lazy loading, matches Cline's handler)
 *   2. Resolves available skills with override precedence
 *   3. Loads full skill content on demand
 *   4. Returns Cline's response format with instructions + skill directory path
 *
 * The isSkillActivation flag on ToolResult is our addition for compaction
 * survival — Cline does not have this.
 */
class UseSkillTool : AgentTool {

    override val name = "use_skill"

    // Port of Cline's use_skill tool description (src/core/prompts/system-prompt/tools/use_skill.ts)
    override val description = "Load and activate a skill by name. Skills provide specialized instructions " +
        "for specific tasks. Use this tool ONCE when a user's request matches one of the available skill " +
        "descriptions shown in the SKILLS section of your system prompt. After activation, follow the " +
        "skill's instructions directly - do not call use_skill again."

    override val parameters = FunctionParameters(
        properties = mapOf(
            "skill_name" to ParameterProperty(
                type = "string",
                description = "The name of the skill to activate (must match exactly one of the available skill names)"
            )
        ),
        required = listOf("skill_name")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val skillName = params["skill_name"]?.jsonPrimitive?.content

        // Port of Cline's handler: missing param increments consecutiveMistakeCount
        if (skillName.isNullOrBlank()) {
            return ToolResult(
                content = "Error: Missing required parameter 'skill_name'. Please provide the name of the skill to activate.",
                summary = "use_skill failed: missing skill_name",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // Discover skills on-demand (lazy loading, matches Cline's UseSkillToolHandler.execute)
        val projectPath = project.basePath ?: ""
        val allSkills = InstructionLoader.discoverSkills(projectPath)
        val availableSkills = InstructionLoader.getAvailableSkills(allSkills)

        if (availableSkills.isEmpty()) {
            return ToolResult(
                content = "Error: No skills are available. Skills may be disabled or not configured.",
                summary = "use_skill failed: no skills available",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // Block loading the meta-skill via use_skill — it's auto-injected into the system prompt
        if (skillName == InstructionLoader.META_SKILL_NAME) {
            return ToolResult(
                content = "The '${InstructionLoader.META_SKILL_NAME}' skill is already active — it's auto-injected into your system prompt. You don't need to load it.",
                summary = "Meta-skill already active",
                tokenEstimate = 10
            )
        }

        // Get IdeContext for skill variant selection
        val ideContext = try {
            project.getService(com.workflow.orchestrator.agent.AgentService::class.java)?.ideContext
        } catch (_: Exception) { null }
        val skillContent = InstructionLoader.getSkillContent(skillName, availableSkills, ideContext)

        if (skillContent == null) {
            // Port of Cline: list available skill names in error message
            val availableNames = availableSkills.filter { it.name != InstructionLoader.META_SKILL_NAME }.joinToString(", ") { it.name }
            return ToolResult(
                content = "Error: Skill \"$skillName\" not found. Available skills: $availableNames",
                summary = "use_skill failed: skill '$skillName' not found",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        }

        // Port of Cline's response format (UseSkillToolHandler.ts lines 92-97)
        val skillDirPath = skillContent.path.replace(Regex("SKILL\\.md$"), "")
        val response = """# Skill "${skillContent.name}" is now active

${skillContent.instructions}

---
IMPORTANT: The skill is now loaded. Do NOT call use_skill again for this task. Simply follow the instructions above to complete the user's request. You may access other files in the skill directory at: $skillDirPath"""

        return ToolResult(
            content = response,
            summary = "Activated skill: $skillName",
            tokenEstimate = response.length / 4,
            // Our addition: flags for compaction survival (not in Cline)
            isSkillActivation = true,
            activatedSkillName = skillName,
            activatedSkillContent = skillContent.instructions
        )
    }
}
