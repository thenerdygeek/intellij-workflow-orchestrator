package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.ContextManagementConfig
import com.workflow.orchestrator.agent.context.EventSourcedContextBridge
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.runtime.SkillManager
import com.workflow.orchestrator.agent.runtime.SkillRegistry
import com.workflow.orchestrator.agent.runtime.WorkerSession
import com.workflow.orchestrator.agent.runtime.WorkerType
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class SkillTool : AgentTool {
    override val name = "skill"
    override val description = "Load a skill definition and its instructions. Returns the full skill content for you to follow. Skills provide structured workflows for specific tasks — check the available skills listed in your context."
    override val parameters = FunctionParameters(
        properties = mapOf(
            "skill" to ParameterProperty(type = "string", description = "The skill name to load"),
            "args" to ParameterProperty(type = "string", description = "Optional arguments passed to the skill")
        ),
        required = listOf("skill")
    )
    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        val skillName = params["skill"]?.jsonPrimitive?.content
            ?: return ToolResult("Error: 'skill' parameter required", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

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

        val arguments = params["args"]?.jsonPrimitive?.content

        // If skill has context: fork, execute in isolated WorkerSession
        if (entry.contextFork) {
            return executeForked(entry, arguments, project, skillManager)
        }

        // Activate the skill (sets compression-proof anchor via callback)
        val activeSkill = skillManager.activateSkill(skillName, arguments)
            ?: return ToolResult("Error: failed to load skill '$skillName'", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        // Return full skill content so the LLM sees it immediately in the tool result
        return ToolResult(
            content = activeSkill.content,
            summary = "Loaded skill: $skillName",
            tokenEstimate = TokenEstimator.estimate(activeSkill.content)
        )
    }

    private suspend fun executeForked(
        entry: SkillRegistry.SkillEntry,
        arguments: String?,
        project: Project,
        skillManager: SkillManager
    ): ToolResult {
        val content = skillManager.loadAndPreprocessSkill(entry, arguments)
            ?: return ToolResult("Error: could not load skill content", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        val agentService = try { AgentService.getInstance(project) } catch (_: Exception) {
            return ToolResult("Error: agent service not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        val allTools = agentService.toolRegistry.allTools()
        val workerToolsList = if (entry.allowedTools != null) {
            allTools.filter { it.name in entry.allowedTools }
        } else {
            allTools.toList()
        }
        val workerTools = workerToolsList.associateBy { it.name }
        val toolDefinitions = workerToolsList.map { it.toToolDefinition() }

        val systemPrompt = "You are a specialized agent executing a skill. Follow the instructions precisely."

        val settings = try { AgentSettings.getInstance(project) } catch (_: Exception) { null }
        val contextManager = EventSourcedContextBridge.create(
            sessionDir = null,
            config = ContextManagementConfig.WORKER,
            maxInputTokens = settings?.state?.maxInputTokens ?: AgentSettings.DEFAULTS.maxInputTokens
        )

        val parentJob = currentCoroutineContext()[Job]
        val workerSession = WorkerSession(maxIterations = 10, parentJob = parentJob)

        return try {
            val result = withTimeout(300_000) {
                workerSession.execute(
                    workerType = WorkerType.ORCHESTRATOR,
                    systemPrompt = systemPrompt,
                    task = content,
                    tools = workerTools,
                    toolDefinitions = toolDefinitions,
                    brain = agentService.brain,
                    bridge = contextManager,
                    project = project
                )
            }
            ToolResult(
                "Skill '${entry.name}' completed via subagent.\n\nResult: ${result.summary}",
                "Skill ${entry.name} executed in subagent",
                TokenEstimator.estimate(result.summary)
            )
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            ToolResult("Skill '${entry.name}' timed out after 5 minutes", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        } catch (e: Exception) {
            ToolResult("Skill '${entry.name}' failed: ${e.message}", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }
    }
}
