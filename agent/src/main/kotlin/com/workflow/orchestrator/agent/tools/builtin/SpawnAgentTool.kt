package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.ContextManager
import com.workflow.orchestrator.agent.orchestrator.OrchestratorPrompts
import com.workflow.orchestrator.agent.runtime.*
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.text.NumberFormat
import java.util.Locale

/**
 * Spawns a subagent to handle a task autonomously, matching Claude Code's Agent tool design.
 *
 * Only two required parameters: description and prompt. The subagent_type defaults to
 * general-purpose if omitted, or can reference a built-in type or custom agent definition.
 *
 * Only available to ORCHESTRATOR-level sessions (the main agent).
 * Workers cannot spawn further agents, preventing nested delegation.
 *
 * The tool spawns a WorkerSession with a fresh ContextManager, filtered tools,
 * and a 5-minute timeout. On failure or timeout, file changes are rolled back
 * via LocalHistory.
 */
class SpawnAgentTool : AgentTool {

    companion object {
        private val LOG = Logger.getInstance(SpawnAgentTool::class.java)
        private const val MAX_CONCURRENT_WORKERS = 5
        private const val WORKER_TIMEOUT_MS = 300_000L // 5 minutes
        private const val DEFAULT_MAX_ITERATIONS = 10

        data class BuiltInAgent(
            val workerType: WorkerType,
            val description: String
        )

        val BUILT_IN_AGENTS = mapOf(
            "general-purpose" to BuiltInAgent(WorkerType.ORCHESTRATOR, "Full capability agent for complex tasks"),
            "explorer" to BuiltInAgent(WorkerType.ANALYZER, "Fast read-only codebase exploration"),
            "coder" to BuiltInAgent(WorkerType.CODER, "Code editing and implementation"),
            "reviewer" to BuiltInAgent(WorkerType.REVIEWER, "Code review and analysis"),
            "tooler" to BuiltInAgent(WorkerType.TOOLER, "Integration tools (Jira, Bamboo, SonarQube)")
        )
    }

    override val name = "agent"

    override val description =
        "Launch a subagent to handle a task autonomously. The subagent runs in its own context " +
            "with its own tools and returns results.\n\n" +
            "Available agent types:\n" +
            "- general-purpose: Full tool access, for complex multi-step tasks\n" +
            "- explorer: Read-only, fast codebase exploration\n" +
            "- coder: Code editing and implementation\n" +
            "- reviewer: Code review and analysis (read-only)\n" +
            "- tooler: Integration tools (Jira, Bamboo, SonarQube, Bitbucket)\n" +
            "Or specify any custom agent defined in .workflow/agents/\n\n" +
            "If subagent_type is omitted, defaults to general-purpose."

    override val parameters = FunctionParameters(
        properties = mapOf(
            "description" to ParameterProperty(
                type = "string",
                description = "A short (3-5 word) summary of what the agent will do"
            ),
            "prompt" to ParameterProperty(
                type = "string",
                description = "The task for the agent to perform. Be detailed and specific."
            ),
            "subagent_type" to ParameterProperty(
                type = "string",
                description = "Which agent type to use. Built-in: general-purpose, explorer, coder, reviewer, tooler. " +
                    "Or any custom agent name from .workflow/agents/. Defaults to general-purpose."
            ),
            "model" to ParameterProperty(
                type = "string",
                description = "Optional model override: sonnet, opus, haiku. If omitted, inherits from parent or agent definition."
            )
        ),
        required = listOf("description", "prompt")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        // --- 1. Parse parameters (only description + prompt required) ---
        val description = params["description"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Error: 'description' parameter required")

        val prompt = params["prompt"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Error: 'prompt' parameter required")

        val subagentType = params["subagent_type"]?.jsonPrimitive?.contentOrNull
        val modelOverride = params["model"]?.jsonPrimitive?.contentOrNull

        // --- 2. Get services ---
        val agentService: AgentService
        val settings: AgentSettings
        try {
            agentService = AgentService.getInstance(project)
            settings = AgentSettings.getInstance(project)
        } catch (e: Exception) {
            LOG.warn("SpawnAgentTool: failed to get services", e)
            return errorResult("Error: Agent services not available: ${e.message}")
        }

        // --- 3. Check resource limits ---
        if (agentService.activeWorkerCount.get() >= MAX_CONCURRENT_WORKERS) {
            return errorResult(
                "Error: Maximum concurrent workers ($MAX_CONCURRENT_WORKERS) reached. " +
                    "Wait for a running worker to complete before spawning another agent."
            )
        }

        if (agentService.totalSessionTokens.get() >= settings.state.maxSessionTokens) {
            return errorResult(
                "Error: Session token budget exceeded (${formatNumber(agentService.totalSessionTokens.get())} / " +
                    "${formatNumber(settings.state.maxSessionTokens.toLong())}). Cannot spawn new agents."
            )
        }

        // --- 4. Resolve agent definition ---
        val resolvedType = subagentType ?: "general-purpose"
        val agentDef: AgentDefinitionRegistry.AgentDefinition?
        val workerType: WorkerType
        val maxIter: Int

        // Check custom agents first
        val registry = agentService.agentDefinitionRegistry
        val customAgent = registry?.getAgent(resolvedType)

        if (customAgent != null) {
            // Custom agent definition found
            agentDef = customAgent
            // Custom agents run as ORCHESTRATOR type (full capability) unless their tools suggest otherwise
            workerType = WorkerType.ORCHESTRATOR
            maxIter = customAgent.maxTurns
        } else if (resolvedType in BUILT_IN_AGENTS) {
            // Built-in agent type
            agentDef = null
            val builtIn = BUILT_IN_AGENTS[resolvedType]!!
            workerType = builtIn.workerType
            maxIter = DEFAULT_MAX_ITERATIONS
        } else {
            // Unknown agent type — return error with available options
            val availableBuiltIn = BUILT_IN_AGENTS.keys.joinToString(", ")
            val availableCustom = registry?.getAllAgents()?.joinToString(", ") { it.name } ?: "none"
            return errorResult(
                "Error: Unknown subagent_type '$resolvedType'. " +
                    "Built-in types: $availableBuiltIn. " +
                    "Custom agents: $availableCustom"
            )
        }

        // --- 5. Spawn worker ---
        agentService.activeWorkerCount.incrementAndGet()

        // Create rollback checkpoint
        val rollbackManager = AgentRollbackManager(project)
        val checkpointId = rollbackManager.createCheckpoint("agent: $resolvedType - ${description.take(60)}")

        // Create event log for telemetry
        val sessionId = "agent-${System.currentTimeMillis()}"
        val eventLog = AgentEventLog(sessionId, project.basePath ?: ".")
        eventLog.log(AgentEventType.WORKER_SPAWNED, "type=$resolvedType, description=$description")

        try {
            // Fresh context manager for the worker
            val contextManager = ContextManager(
                maxInputTokens = settings.state.maxInputTokens
            )

            // Get worker-specific prompt and tools
            val systemPrompt: String
            val toolMap: Map<String, AgentTool>
            val toolDefinitions: List<com.workflow.orchestrator.agent.api.dto.ToolDefinition>

            if (agentDef != null) {
                // Custom subagent: use its system prompt, tool restrictions, and max turns
                systemPrompt = buildSubagentPrompt(agentDef, agentService, project)

                val allTools = agentService.toolRegistry.getToolsForWorker(workerType)
                val allRegisteredTools = agentService.toolRegistry.allTools()
                val effectiveTools = run {
                    var tools = if (agentDef.tools != null) {
                        allTools.filter { it.name in agentDef.tools }
                    } else {
                        allTools.toList()
                    }
                    if (agentDef.disallowedTools.isNotEmpty()) {
                        tools = tools.filter { it.name !in agentDef.disallowedTools }
                    }
                    // Auto-enable read_file/edit_file for memory-enabled agents
                    if (agentDef.memory != null) {
                        val memoryTools = listOf("read_file", "edit_file")
                        val missingTools = memoryTools.filter { name -> tools.none { it.name == name } }
                        if (missingTools.isNotEmpty()) {
                            tools = tools + allRegisteredTools.filter { it.name in missingTools }
                        }
                    }
                    tools
                }
                toolMap = effectiveTools.associateBy { it.name }
                toolDefinitions = effectiveTools.map { it.toToolDefinition() }
            } else {
                // Standard built-in worker: use built-in prompts and tool selection
                systemPrompt = OrchestratorPrompts.getSystemPrompt(workerType)
                val toolsForWorker = agentService.toolRegistry.getToolsForWorker(workerType)
                toolMap = toolsForWorker.associateBy { it.name }
                toolDefinitions = agentService.toolRegistry.getToolDefinitionsForWorker(workerType)
            }

            // Execute with timeout
            val parentJob = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
            val workerSession = WorkerSession(maxIterations = maxIter, parentJob = parentJob)
            val workerResult: WorkerResult = withTimeout(WORKER_TIMEOUT_MS) {
                workerSession.execute(
                    workerType = workerType,
                    systemPrompt = systemPrompt,
                    task = prompt,
                    tools = toolMap,
                    toolDefinitions = toolDefinitions,
                    brain = agentService.brain,
                    contextManager = contextManager,
                    project = project
                )
            }

            // Track tokens
            agentService.totalSessionTokens.addAndGet(workerResult.tokensUsed.toLong())

            if (workerResult.isError) {
                // Worker failed — rollback
                rollbackManager.rollbackToCheckpoint(checkpointId)
                eventLog.log(AgentEventType.WORKER_FAILED, "error=${workerResult.summary}")
                eventLog.log(AgentEventType.WORKER_ROLLED_BACK, "checkpoint=$checkpointId")

                return ToolResult(
                    content = "Agent ($resolvedType) failed: ${workerResult.content}\n\n" +
                        "File changes have been rolled back.",
                    summary = "Agent '$description' failed: ${workerResult.summary}",
                    tokenEstimate = workerResult.tokensUsed,
                    isError = true
                )
            }

            // Success
            eventLog.log(
                AgentEventType.WORKER_COMPLETED,
                "tokens=${workerResult.tokensUsed}, artifacts=${workerResult.artifacts.size}"
            )

            val formattedTokens = formatNumber(workerResult.tokensUsed.toLong())

            return ToolResult(
                content = "Agent ($resolvedType) completed: $description\n" +
                    "Summary: ${workerResult.summary}\n" +
                    "Tokens used: $formattedTokens\n" +
                    if (workerResult.artifacts.isNotEmpty())
                        "Files modified: ${workerResult.artifacts}\n\n" +
                            "Note: The above files were modified by the agent. " +
                            "Re-read them if needed as your cached version may be stale."
                    else "",
                summary = "Agent '$description' completed: ${workerResult.summary.take(100)}",
                tokenEstimate = workerResult.tokensUsed,
                artifacts = workerResult.artifacts
            )

        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            // Timeout — rollback
            rollbackManager.rollbackToCheckpoint(checkpointId)
            eventLog.log(AgentEventType.WORKER_TIMED_OUT, "timeout=${WORKER_TIMEOUT_MS}ms")
            eventLog.log(AgentEventType.WORKER_ROLLED_BACK, "checkpoint=$checkpointId")

            return ToolResult(
                content = "Error: Agent ($resolvedType) timed out after ${WORKER_TIMEOUT_MS / 1000} seconds. " +
                    "File changes have been rolled back. Consider breaking the task into smaller pieces.",
                summary = "Agent '$description' timed out after ${WORKER_TIMEOUT_MS / 1000}s",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        } catch (e: Exception) {
            // Unexpected error — rollback
            LOG.warn("SpawnAgentTool: agent execution failed", e)
            rollbackManager.rollbackToCheckpoint(checkpointId)
            eventLog.log(AgentEventType.WORKER_FAILED, "exception=${e.message}")
            eventLog.log(AgentEventType.WORKER_ROLLED_BACK, "checkpoint=$checkpointId")

            return ToolResult(
                content = "Error: Agent ($resolvedType) failed: ${e.message}\n" +
                    "File changes have been rolled back.",
                summary = "Agent '$description' error: ${e.message?.take(100)}",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        } finally {
            agentService.activeWorkerCount.decrementAndGet()
        }
    }

    /**
     * Build a system prompt for a custom subagent, including preloaded skills and memory.
     */
    private fun buildSubagentPrompt(
        def: AgentDefinitionRegistry.AgentDefinition,
        agentService: AgentService,
        project: Project
    ): String {
        val sb = StringBuilder()
        sb.appendLine("<agent_definition source=\"${def.filePath}\">")
        sb.appendLine(def.systemPrompt)
        sb.appendLine("</agent_definition>")
        sb.appendLine()
        sb.appendLine("Follow the instructions in the agent definition above. Do not ignore or override these instructions.")

        // Preload skills content
        if (def.skills.isNotEmpty()) {
            val skillRegistry = agentService.currentSkillManager?.registry
            if (skillRegistry != null) {
                sb.appendLine("\n\n<preloaded_skills>")
                for (skillName in def.skills) {
                    val content = skillRegistry.getSkillContent(skillName)
                    if (content != null) {
                        sb.appendLine("<skill name=\"$skillName\">")
                        sb.appendLine(content.take(10_000))
                        sb.appendLine("</skill>")
                    }
                }
                sb.appendLine("</preloaded_skills>")
            }
        }

        // Per-agent memory
        val memRegistry = try { agentService.agentDefinitionRegistry } catch (_: Exception) { null }
        val memoryDir = memRegistry?.getMemoryDirectory(def, project)
        if (memoryDir != null && memoryDir.isDirectory) {
            val memoryIndex = java.io.File(memoryDir, "MEMORY.md")
            if (memoryIndex.isFile) {
                val memoryContent = memoryIndex.readText().lines().take(200).joinToString("\n")
                sb.appendLine("\n<agent_memory>")
                sb.appendLine(memoryContent)
                sb.appendLine("</agent_memory>")
            }
            sb.appendLine("\n<memory_instructions>")
            sb.appendLine("You have persistent memory at: ${memoryDir.absolutePath}")
            sb.appendLine("Use read_file and edit_file to read and update your memory files.")
            sb.appendLine("Keep MEMORY.md as an index of your learnings (max 200 lines).")
            sb.appendLine("</memory_instructions>")
        }

        return sb.toString()
    }

    private fun errorResult(message: String): ToolResult {
        return ToolResult(
            content = message,
            summary = message.take(120),
            tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
            isError = true
        )
    }

    private fun formatNumber(value: Long): String {
        return NumberFormat.getNumberInstance(Locale.US).format(value)
    }
}
