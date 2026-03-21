package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.ContextManager
import com.workflow.orchestrator.agent.context.TokenEstimator
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

        // If skill has context: fork, execute in isolated WorkerSession
        if (entry.contextFork) {
            return executeForked(entry, arguments, project, skillManager)
        }

        skillManager.activateSkill(skillName, arguments)
            ?: return ToolResult("Error: failed to activate skill '$skillName'", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        return ToolResult(
            content = "Skill '$skillName' activated. Follow the skill instructions in your context.",
            summary = "Activated skill: $skillName",
            tokenEstimate = 5
        )
    }

    private suspend fun executeForked(
        entry: SkillRegistry.SkillEntry,
        arguments: String?,
        project: Project,
        skillManager: com.workflow.orchestrator.agent.runtime.SkillManager
    ): ToolResult {
        // Load and preprocess skill content (substitutions + dynamic injection)
        val content = skillManager.loadAndPreprocessSkill(entry, arguments)
            ?: return ToolResult("Error: could not load skill content", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)

        // Get agent service for brain and tools
        val agentService = try { AgentService.getInstance(project) } catch (_: Exception) {
            return ToolResult("Error: agent service not available", "Error", ToolResult.ERROR_TOKEN_ESTIMATE, isError = true)
        }

        // Determine tools for the worker
        val allTools = agentService.toolRegistry.allTools()
        val workerToolsList = if (entry.allowedTools != null) {
            allTools.filter { it.name in entry.allowedTools }
        } else {
            allTools.toList()
        }
        val workerTools = workerToolsList.associateBy { it.name }
        val toolDefinitions = workerToolsList.map { it.toToolDefinition() }

        val systemPrompt = "You are a specialized agent executing a skill. Follow the instructions precisely."

        // Create fresh context manager for the worker
        val settings = try { AgentSettings.getInstance(project) } catch (_: Exception) { null }
        val contextManager = ContextManager(
            maxInputTokens = settings?.state?.maxInputTokens ?: AgentSettings.DEFAULTS.maxInputTokens
        )

        val parentJob = currentCoroutineContext()[Job]
        val workerSession = WorkerSession(maxIterations = 10, parentJob = parentJob)

        return try {
            val result = withTimeout(300_000) { // 5 min timeout
                workerSession.execute(
                    workerType = WorkerType.ORCHESTRATOR,
                    systemPrompt = systemPrompt,
                    task = content,
                    tools = workerTools,
                    toolDefinitions = toolDefinitions,
                    brain = agentService.brain,
                    contextManager = contextManager,
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
