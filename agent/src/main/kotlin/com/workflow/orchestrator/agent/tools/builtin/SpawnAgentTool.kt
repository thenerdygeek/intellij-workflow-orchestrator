package com.workflow.orchestrator.agent.tools.builtin

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.api.dto.FunctionParameters
import com.workflow.orchestrator.agent.api.dto.ParameterProperty
import com.workflow.orchestrator.agent.context.ContextManager
import com.workflow.orchestrator.agent.orchestrator.OrchestratorPrompts
import com.workflow.orchestrator.agent.runtime.*
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolResult
import kotlinx.coroutines.*
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.text.NumberFormat
import java.util.Locale

/**
 * Spawns a subagent to handle a task autonomously, matching Claude Code's Agent tool design.
 *
 * Supports three lifecycle operations beyond basic spawn:
 * - **Resume:** Reload a previous agent's transcript and continue execution
 * - **Background:** Launch an agent in a detached coroutine, return immediately
 * - **Kill:** Cancel a running background agent via its agentId
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
            "explorer" to BuiltInAgent(WorkerType.ANALYZER, "Fast read-only codebase exploration with PSI intelligence — specify thoroughness: quick/medium/very thorough"),
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
            "- explorer: Read-only, fast codebase exploration. Specify thoroughness in prompt: " +
            "'quick' (1-3 calls, targeted lookup), 'medium' (3-6 calls, default), " +
            "'very thorough' (6-10 calls, exhaustive). Uses PSI tools for semantically accurate navigation. " +
            "Prefer explorer over direct Grep/Glob when: the search is open-ended, requires >3 queries, " +
            "or needs to follow references/inheritance/call chains.\n" +
            "- coder: Code editing and implementation\n" +
            "- reviewer: Code review and analysis (read-only)\n" +
            "- tooler: Integration tools (Jira, Bamboo, SonarQube, Bitbucket)\n" +
            "Or specify any custom agent defined in .workflow/agents/\n\n" +
            "If subagent_type is omitted, defaults to general-purpose.\n\n" +
            "When NOT to use explorer (use direct tools instead):\n" +
            "- Reading a specific known file → read_file\n" +
            "- Searching for a specific class/method → search_code or find_definition\n" +
            "- Searching within 1-3 known files → read_file\n\n" +
            "Lifecycle:\n" +
            "- Resume: agent(resume='agentId', prompt='continue with...') — continues a previous agent\n" +
            "- Background: agent(run_in_background=true, ...) — returns immediately, notifies on completion\n" +
            "- Kill: agent(kill='agentId') — cancels a running background agent"

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
                description = "Which agent type to use. ALWAYS specify this — do not omit. " +
                    "Built-in types: general-purpose (complex tasks), explorer (read-only codebase search — " +
                    "use when you're not confident you'll find what you need in 1-2 tool calls), " +
                    "coder (editing/implementation), reviewer (code review), tooler (Jira/Bamboo/Sonar/Bitbucket). " +
                    "Also accepts any custom agent name from .workflow/agents/."
            ),
            "model" to ParameterProperty(
                type = "string",
                description = "Optional model override: sonnet, opus, haiku. If omitted, inherits from parent or agent definition."
            ),
            "resume" to ParameterProperty(
                type = "string",
                description = "Agent ID to resume from a previous execution. The agent continues with its full previous context preserved."
            ),
            "run_in_background" to ParameterProperty(
                type = "boolean",
                description = "Set to true to run the agent in the background. Returns immediately with the agent ID. You will be notified when it completes."
            ),
            "kill" to ParameterProperty(
                type = "string",
                description = "Agent ID to kill. Cancels a running background agent."
            )
        ),
        required = listOf("description", "prompt")
    )

    override val allowedWorkers = setOf(WorkerType.ORCHESTRATOR)

    override suspend fun execute(params: JsonObject, project: Project): ToolResult {
        // --- 0. Get services early (needed by all paths) ---
        val agentService: AgentService
        try {
            agentService = AgentService.getInstance(project)
        } catch (e: Exception) {
            LOG.warn("SpawnAgentTool: failed to get AgentService", e)
            return errorResult("Error: Agent services not available: ${e.message}")
        }

        // --- 0a. Kill check (no description/prompt required) ---
        val killId = params["kill"]?.jsonPrimitive?.contentOrNull
        if (killId != null) {
            val killed = agentService.killWorker(killId)
            return if (killed) {
                ToolResult("Agent '$killId' has been killed.", "Killed agent $killId", 20)
            } else {
                errorResult("Agent '$killId' not found or not running. Active: ${agentService.listBackgroundWorkers().joinToString { it.agentId }}")
            }
        }

        // --- 1. Parse parameters (description + prompt required for spawn/resume) ---
        val description = params["description"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Error: 'description' parameter required")

        val prompt = params["prompt"]?.jsonPrimitive?.contentOrNull
            ?: return errorResult("Error: 'prompt' parameter required")

        // --- 1a. Resume check ---
        val resumeId = params["resume"]?.jsonPrimitive?.contentOrNull
        if (resumeId != null) {
            return executeResume(resumeId, prompt, project, agentService)
        }

        val subagentType = params["subagent_type"]?.jsonPrimitive?.contentOrNull
        val modelOverride = params["model"]?.jsonPrimitive?.contentOrNull

        // --- 2. Get remaining services ---
        val settings: AgentSettings
        try {
            settings = AgentSettings.getInstance(project)
        } catch (e: Exception) {
            LOG.warn("SpawnAgentTool: failed to get settings", e)
            return errorResult("Error: Agent settings not available: ${e.message}")
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
            agentDef = customAgent
            workerType = WorkerType.ORCHESTRATOR
            maxIter = customAgent.maxTurns
        } else if (resolvedType in BUILT_IN_AGENTS) {
            agentDef = null
            val builtIn = BUILT_IN_AGENTS[resolvedType]!!
            workerType = builtIn.workerType
            maxIter = DEFAULT_MAX_ITERATIONS
        } else {
            val availableBuiltIn = BUILT_IN_AGENTS.keys.joinToString(", ")
            val availableCustom = registry?.getAllAgents()?.joinToString(", ") { it.name } ?: "none"
            return errorResult(
                "Error: Unknown subagent_type '$resolvedType'. " +
                    "Built-in types: $availableBuiltIn. " +
                    "Custom agents: $availableCustom"
            )
        }

        // --- 4a. Background check ---
        val runInBackground = params["run_in_background"]?.jsonPrimitive?.booleanOrNull ?: false
        if (runInBackground) {
            return executeBackground(
                agentId = WorkerTranscriptStore.generateAgentId(),
                description = description,
                prompt = prompt,
                subagentType = resolvedType,
                agentDef = agentDef,
                workerType = workerType,
                maxIter = maxIter,
                project = project,
                agentService = agentService,
                settings = settings
            )
        }

        // --- 5. Foreground spawn ---
        val agentId = WorkerTranscriptStore.generateAgentId()
        val sessionDir = agentService.currentSessionDir
        val transcriptStore = if (sessionDir != null) WorkerTranscriptStore(sessionDir) else null

        agentService.activeWorkerCount.incrementAndGet()

        // Create rollback checkpoint
        val rollbackManager = AgentRollbackManager(project)
        val checkpointId = rollbackManager.createCheckpoint("agent: $resolvedType - ${description.take(60)}")

        // Create event log for telemetry (write into parent session dir, or fallback to project-based path)
        val sessionId = "agent-${System.currentTimeMillis()}"
        val eventLogDir: java.io.File = sessionDir
            ?: project.basePath?.let {
                com.workflow.orchestrator.core.util.ProjectIdentifier.sessionsDir(it)
                    .resolve(sessionId).also { dir -> dir.mkdirs() }
            }
            ?: java.io.File(".").resolve(sessionId).also { it.mkdirs() }
        val eventLog = AgentEventLog(sessionId, eventLogDir)
        eventLog.log(AgentEventType.WORKER_SPAWNED, "type=$resolvedType, description=$description")

        try {
            val contextManager = ContextManager(
                maxInputTokens = settings.state.maxInputTokens
            )

            val systemPrompt = resolveSystemPrompt(agentDef, resolvedType, agentService, project)
            val toolsForWorker = resolveTools(agentDef, resolvedType, agentService)
            val toolMap = toolsForWorker.associateBy { it.name }
            val toolDefinitions = toolsForWorker.map { it.toToolDefinition() }

            // Execute with timeout
            val parentJob = currentCoroutineContext()[Job]
            val workerSession = WorkerSession(
                maxIterations = maxIter,
                parentJob = parentJob,
                transcriptStore = transcriptStore,
                agentId = agentId
            )
            val workerResult: WorkerResult = withTimeout(WORKER_TIMEOUT_MS) {
                workerSession.execute(
                    workerType = workerType,
                    systemPrompt = systemPrompt,
                    task = prompt,
                    tools = toolMap,
                    toolDefinitions = toolDefinitions,
                    brain = agentService.brain,
                    contextManager = contextManager,
                    project = project,
                    maxOutputTokens = AgentSettings.getInstance(project).state.maxOutputTokens
                )
            }

            // Track tokens
            agentService.totalSessionTokens.addAndGet(workerResult.tokensUsed.toLong())

            // Persist transcript metadata
            transcriptStore?.saveMetadata(WorkerTranscriptStore.WorkerMetadata(
                agentId = agentId,
                subagentType = resolvedType,
                description = description,
                status = if (workerResult.isError) "failed" else "completed",
                tokensUsed = workerResult.tokensUsed,
                summary = workerResult.summary,
                completedAt = System.currentTimeMillis()
            ))

            if (workerResult.isError) {
                // Worker failed — rollback
                rollbackManager.rollbackToCheckpoint(checkpointId)
                eventLog.log(AgentEventType.WORKER_FAILED, "error=${workerResult.summary}")
                eventLog.log(AgentEventType.WORKER_ROLLED_BACK, "checkpoint=$checkpointId")

                return ToolResult(
                    content = "Agent ($resolvedType) failed: ${workerResult.content}\n\n" +
                        "File changes have been rolled back.\n" +
                        "Agent ID: $agentId (can be resumed with agent(resume='$agentId', prompt='...'))",
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
                    "Agent ID: $agentId (can be resumed with agent(resume='$agentId', prompt='...'))\n" +
                    if (workerResult.artifacts.isNotEmpty())
                        "Files modified: ${workerResult.artifacts}\n\n" +
                            "Note: The above files were modified by the agent. " +
                            "Re-read them if needed as your cached version may be stale."
                    else "",
                summary = "Agent '$description' completed: ${workerResult.summary.take(100)}",
                tokenEstimate = workerResult.tokensUsed,
                artifacts = workerResult.artifacts
            )

        } catch (e: TimeoutCancellationException) {
            // Timeout — rollback
            rollbackManager.rollbackToCheckpoint(checkpointId)
            eventLog.log(AgentEventType.WORKER_TIMED_OUT, "timeout=${WORKER_TIMEOUT_MS}ms")
            eventLog.log(AgentEventType.WORKER_ROLLED_BACK, "checkpoint=$checkpointId")
            transcriptStore?.updateStatus(agentId, "failed", summary = "Timed out after ${WORKER_TIMEOUT_MS / 1000}s")

            return ToolResult(
                content = "Error: Agent ($resolvedType) timed out after ${WORKER_TIMEOUT_MS / 1000} seconds. " +
                    "File changes have been rolled back. Consider breaking the task into smaller pieces.\n" +
                    "Agent ID: $agentId (can be resumed with agent(resume='$agentId', prompt='...'))",
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
            transcriptStore?.updateStatus(agentId, "failed", summary = e.message)

            return ToolResult(
                content = "Error: Agent ($resolvedType) failed: ${e.message}\n" +
                    "File changes have been rolled back.\n" +
                    "Agent ID: $agentId (can be resumed with agent(resume='$agentId', prompt='...'))",
                summary = "Agent '$description' error: ${e.message?.take(100)}",
                tokenEstimate = ToolResult.ERROR_TOKEN_ESTIMATE,
                isError = true
            )
        } finally {
            agentService.activeWorkerCount.decrementAndGet()
        }
    }

    /**
     * Resume execution of a previous agent from its saved transcript.
     * Reconstructs the full conversation context and continues with a new prompt.
     */
    private suspend fun executeResume(
        agentId: String,
        newPrompt: String,
        project: Project,
        agentService: AgentService
    ): ToolResult {
        val sessionDir = agentService.currentSessionDir
            ?: return errorResult("Error: no active session directory for transcript storage")
        val transcriptStore = WorkerTranscriptStore(sessionDir)
        val metadata = transcriptStore.loadMetadata(agentId)
            ?: return errorResult("Error: agent '$agentId' not found. Available: ${transcriptStore.listWorkers().joinToString { it.agentId }}")

        val transcript = transcriptStore.loadTranscript(agentId)
        if (transcript.isEmpty()) {
            return errorResult("Error: agent '$agentId' has no transcript to resume from")
        }

        // Reconstruct context from transcript
        val settings = try { AgentSettings.getInstance(project) } catch (_: Exception) { null }
        val contextManager = ContextManager(
            maxInputTokens = settings?.state?.maxInputTokens ?: AgentSettings.DEFAULTS.maxInputTokens
        )

        // Replay previous messages into context
        val chatMessages = transcriptStore.toChatMessages(transcript)
        for (msg in chatMessages) {
            contextManager.addMessage(msg)
        }

        // Add the new prompt as a user message
        contextManager.addMessage(ChatMessage(role = "user", content = newPrompt))
        transcriptStore.appendMessage(agentId, WorkerTranscriptStore.TranscriptMessage(
            role = "user", content = newPrompt
        ))

        // Resolve tools from metadata
        val agentDef = agentService.agentDefinitionRegistry?.getAgent(metadata.subagentType)
        val toolsForWorker = resolveTools(agentDef, metadata.subagentType, agentService)
        val toolMap = toolsForWorker.associateBy { it.name }
        val toolDefinitions = toolsForWorker.map { it.toToolDefinition() }

        // Resume execution
        val workerSession = WorkerSession(
            maxIterations = agentDef?.maxTurns ?: DEFAULT_MAX_ITERATIONS,
            parentJob = currentCoroutineContext()[Job],
            transcriptStore = transcriptStore,
            agentId = agentId
        )

        agentService.activeWorkerCount.incrementAndGet()
        try {
            val result = withTimeout(WORKER_TIMEOUT_MS) {
                workerSession.executeFromContext(
                    tools = toolMap,
                    toolDefinitions = toolDefinitions,
                    brain = agentService.brain,
                    contextManager = contextManager,
                    project = project,
                    maxOutputTokens = AgentSettings.getInstance(project).state.maxOutputTokens
                )
            }

            transcriptStore.updateStatus(agentId, if (result.isError) "failed" else "completed",
                summary = result.summary, tokensUsed = result.tokensUsed)
            agentService.totalSessionTokens.addAndGet(result.tokensUsed.toLong())

            return ToolResult(
                content = "Resumed agent '$agentId' completed.\n\nResult: ${result.summary}\n" +
                    "Agent ID: $agentId (can resume again)",
                summary = "Resumed agent completed: ${result.summary.take(100)}",
                tokenEstimate = result.tokensUsed
            )
        } catch (e: Exception) {
            transcriptStore.updateStatus(agentId, "failed", summary = e.message)
            return errorResult("Resumed agent '$agentId' failed: ${e.message}")
        } finally {
            agentService.activeWorkerCount.decrementAndGet()
        }
    }

    /**
     * Launch an agent in a detached coroutine for background execution.
     * Returns immediately with the agentId. The parent is notified on completion
     * via [AgentService.onBackgroundWorkerCompleted].
     */
    private fun executeBackground(
        agentId: String,
        description: String,
        prompt: String,
        subagentType: String,
        agentDef: AgentDefinitionRegistry.AgentDefinition?,
        workerType: WorkerType,
        maxIter: Int,
        project: Project,
        agentService: AgentService,
        settings: AgentSettings
    ): ToolResult {
        val sessionDir = agentService.currentSessionDir
        val transcriptStore = if (sessionDir != null) WorkerTranscriptStore(sessionDir) else null

        // Save initial metadata
        transcriptStore?.saveMetadata(WorkerTranscriptStore.WorkerMetadata(
            agentId = agentId,
            subagentType = subagentType,
            description = description
        ))

        // Launch in detached coroutine
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val job = scope.launch {
            agentService.activeWorkerCount.incrementAndGet()
            try {
                val contextManager = ContextManager(
                    maxInputTokens = settings.state.maxInputTokens
                )

                val toolsForWorker = resolveTools(agentDef, subagentType, agentService)
                val toolMap = toolsForWorker.associateBy { it.name }
                val toolDefinitions = toolsForWorker.map { it.toToolDefinition() }

                val systemPrompt = resolveSystemPrompt(agentDef, subagentType, agentService, project)

                val workerSession = WorkerSession(
                    maxIterations = maxIter,
                    transcriptStore = transcriptStore,
                    agentId = agentId
                )

                val result = withTimeout(WORKER_TIMEOUT_MS) {
                    workerSession.execute(
                        workerType = workerType,
                        systemPrompt = systemPrompt,
                        task = prompt,
                        tools = toolMap,
                        toolDefinitions = toolDefinitions,
                        brain = agentService.brain,
                        contextManager = contextManager,
                        project = project,
                        maxOutputTokens = AgentSettings.getInstance(project).state.maxOutputTokens
                    )
                }

                // Update metadata
                transcriptStore?.updateStatus(agentId, if (result.isError) "failed" else "completed",
                    summary = result.summary, tokensUsed = result.tokensUsed)
                agentService.totalSessionTokens.addAndGet(result.tokensUsed.toLong())

                // Notify parent
                val bgWorker = agentService.backgroundWorkers[agentId]
                bgWorker?.status = if (result.isError) "failed" else "completed"
                agentService.onBackgroundWorkerCompleted?.invoke(
                    agentId,
                    "Background agent '$agentId' ($subagentType) ${if (result.isError) "failed" else "completed"}.\n" +
                        "Summary: ${result.summary}\nAgent ID: $agentId (can resume)",
                    result.isError
                )
            } catch (e: CancellationException) {
                transcriptStore?.updateStatus(agentId, "killed")
                val bgWorker = agentService.backgroundWorkers[agentId]
                bgWorker?.status = "killed"
            } catch (e: Exception) {
                LOG.warn("SpawnAgentTool: background agent '$agentId' failed", e)
                transcriptStore?.updateStatus(agentId, "failed", summary = e.message)
                agentService.onBackgroundWorkerCompleted?.invoke(
                    agentId, "Background agent '$agentId' failed: ${e.message}", true
                )
            } finally {
                agentService.activeWorkerCount.decrementAndGet()
                agentService.backgroundWorkers.remove(agentId)
            }
        }

        // Track the background worker
        agentService.backgroundWorkers[agentId] = AgentService.BackgroundWorker(
            agentId = agentId,
            job = job,
            subagentType = subagentType,
            description = description
        )

        return ToolResult(
            content = "Agent '$agentId' ($subagentType) launched in background.\n" +
                "Description: $description\n" +
                "You will be notified when it completes. Continue with other work.\n" +
                "To kill: agent(kill='$agentId')\n" +
                "Agent ID: $agentId",
            summary = "Background agent $agentId launched: $description",
            tokenEstimate = 50
        )
    }

    // --- Helper methods to avoid duplication across foreground/background/resume ---

    /**
     * Resolve the tools available for a given agent definition and type.
     */
    private fun resolveTools(
        agentDef: AgentDefinitionRegistry.AgentDefinition?,
        subagentType: String,
        agentService: AgentService
    ): List<AgentTool> {
        val workerType = resolveWorkerType(subagentType)
        return if (agentDef != null) {
            val allTools = agentService.toolRegistry.getToolsForWorker(workerType)
            val allRegisteredTools = agentService.toolRegistry.allTools()
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
        } else {
            agentService.toolRegistry.getToolsForWorker(workerType).toList()
        }
    }

    /**
     * Resolve the system prompt for a given agent definition and type.
     */
    private fun resolveSystemPrompt(
        agentDef: AgentDefinitionRegistry.AgentDefinition?,
        subagentType: String,
        agentService: AgentService,
        project: Project
    ): String {
        return if (agentDef != null) {
            buildSubagentPrompt(agentDef, agentService, project)
        } else {
            OrchestratorPrompts.getSystemPrompt(resolveWorkerType(subagentType))
        }
    }

    /**
     * Resolve the WorkerType for a given subagent type string.
     */
    private fun resolveWorkerType(subagentType: String): WorkerType {
        return BUILT_IN_AGENTS[subagentType]?.workerType ?: WorkerType.ORCHESTRATOR
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
