package com.workflow.orchestrator.agent.orchestrator

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.api.SourcegraphChatClient
import com.workflow.orchestrator.agent.api.dto.*
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.agent.brain.OpenAiCompatBrain
import com.workflow.orchestrator.agent.context.ContextManager
import com.workflow.orchestrator.agent.context.RepoMapGenerator
import com.workflow.orchestrator.agent.context.TokenEstimator
import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.runtime.*
import com.workflow.orchestrator.agent.service.MetricsStore
import com.workflow.orchestrator.agent.tools.DynamicToolSelector
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.core.model.ApiResult
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Result of an agent orchestration run.
 */
sealed class AgentResult {
    /** All tasks completed successfully. */
    data class Completed(
        val summary: String,
        val artifacts: List<String>,
        val totalTokens: Int,
        val rollbackCheckpointId: String? = null
    ) : AgentResult()

    /** Execution failed with an error. */
    data class Failed(
        val error: String,
        val partialResults: String? = null,
        val rollbackCheckpointId: String? = null
    ) : AgentResult()

    /** User cancelled the task. */
    data class Cancelled(val completedSteps: Int) : AgentResult()

    /** Context exhausted but state externalized for rotation to a new session. */
    data class ContextRotated(
        val summary: String,
        val rotationStatePath: String,
        val tokensUsed: Int
    ) : AgentResult()
}

/**
 * Progress update emitted during orchestration.
 */
data class AgentProgress(
    val step: String,
    val workerType: WorkerType? = null,
    val tokensUsed: Int = 0,
    val totalTasks: Int = 0,
    val completedTasks: Int = 0,
    /** Tool call details for rich UI rendering. */
    val toolCallInfo: ToolCallInfo? = null
)

/**
 * Rich tool call information for UI rendering.
 */
data class ToolCallInfo(
    val toolName: String,
    val args: String = "",
    val result: String = "",
    val durationMs: Long = 0,
    val isError: Boolean = false,
    /** Full tool output for UI display (capped at 5000 chars). */
    val output: String? = null,
    /** Edit diff data for file modifications. */
    val editFilePath: String? = null,
    val editOldText: String? = null,
    val editNewText: String? = null
)

/**
 * Top-level orchestrator that manages task execution using a single ReAct loop.
 *
 * The LLM has ALL tools available and decides whether to analyze, code, review,
 * or call enterprise tools. For complex tasks, the LLM can use the delegate_task
 * tool to spawn sub-agents.
 */
class AgentOrchestrator(
    private val brain: LlmBrain,
    private val toolRegistry: ToolRegistry,
    private val project: Project
) {
    companion object {
        private val LOG = Logger.getInstance(AgentOrchestrator::class.java)
        private const val RESERVED_TOKEN_BUFFER = 200
    }

    private val cancelled = AtomicBoolean(false)

    /**
     * Execute a task end-to-end using a single ReAct loop.
     *
     * The agent has ALL tools and handles the full task in one loop.
     * For complex sub-tasks, the LLM can use the delegate_task tool.
     */
    suspend fun executeTask(
        taskDescription: String,
        session: ConversationSession? = null,
        onProgress: (AgentProgress) -> Unit = {},
        onStreamChunk: (String) -> Unit = {},
        approvalGate: ApprovalGate? = null,
        onDebugLog: ((String, String, String, Map<String, Any?>?) -> Unit)? = null
    ): AgentResult {
        cancelled.set(false)

        val settings = try { AgentSettings.getInstance(project) } catch (_: Exception) { null }
        val maxOutputTokens = settings?.state?.maxOutputTokens ?: SourcegraphChatClient.MAX_OUTPUT_TOKENS

        // Scope API debug dumps to the active session directory.
        // Counter resets per session so file numbering restarts from 001.
        if (brain is OpenAiCompatBrain) {
            brain.setApiDebugDir(session?.store?.sessionDirectory)
            if (session == null) brain.resetApiCallCounter()
        }

        // Default: Single Agent Mode
        onProgress(AgentProgress("Starting task...", tokensUsed = 0))

        if (cancelled.get()) return AgentResult.Cancelled(0)

        // Resolve context, tools, and system prompt — reuse from session or create fresh
        val allTools: Map<String, com.workflow.orchestrator.agent.tools.AgentTool>
        val allToolDefs: List<ToolDefinition>
        val contextManager: ContextManager
        val effectiveSystemPrompt: String?

        if (session != null) {
            // Multi-turn: reuse session's context (the core fix)
            // Build context from task + recent user messages for tool selection
            val toolContext = run {
                val recentUserMsgs = session.contextManager.getMessages()
                    .filter { it.role == "user" || it.role == "system" }
                    .takeLast(3)
                    .mapNotNull { it.content }
                    .joinToString("\n")
                "$taskDescription\n$recentUserMsgs"
            }
            // Dynamic tool injection: filter tools based on conversation context
            val prefs = try { com.workflow.orchestrator.agent.settings.ToolPreferences.getInstance(project) } catch (_: Exception) { null }
            val disabledTools = prefs?.getDisabledTools() ?: emptySet()
            val preferredTools = session.skillManager?.getPreferredTools() ?: emptySet()
            val skillAllowedTools = session.skillManager?.getAllowedTools()
            val newlySelectedTools = DynamicToolSelector.selectTools(session.tools.values, toolContext, disabledTools = disabledTools, preferredTools = preferredTools, projectTools = session.projectTools, skillAllowedTools = skillAllowedTools)

            // STABILIZE: Merge with existing session tools — only ADD, never remove.
            // This prevents tool count from swinging 24→62→24 between messages,
            // which confuses the LLM and breaks budget math.
            val stableToolNames = session.activeToolNames
            stableToolNames.addAll(newlySelectedTools.map { it.name })
            session.activeToolNames = stableToolNames
            val selectedTools = session.tools.values.filter { it.name in stableToolNames }

            allTools = selectedTools.associateBy { it.name }
            allToolDefs = selectedTools.map { it.toToolDefinition() }
            contextManager = session.contextManager

            // Recalculate reserved tokens for the current (expanded) tool set
            val toolDefTokens = TokenEstimator.estimateToolDefinitions(allToolDefs)
            val systemPromptTokens = TokenEstimator.estimate(session.systemPrompt)
            val newReservedTokens = toolDefTokens + systemPromptTokens + RESERVED_TOKEN_BUFFER
            contextManager.updateReservedTokens(newReservedTokens)

            // Initialize session (adds system prompt) on first use, then null to avoid re-adding
            session.initialize()
            effectiveSystemPrompt = null  // already in context from initialize()
        } else {
            // Backward compat: create everything from scratch (tests, no-session mode)
            val maxInputTokens = settings?.state?.maxInputTokens ?: AgentSettings.DEFAULTS.maxInputTokens

            // Dynamic tool injection: filter tools based on task description
            val registeredTools = toolRegistry.allTools()
            val prefs = try { com.workflow.orchestrator.agent.settings.ToolPreferences.getInstance(project) } catch (_: Exception) { null }
            val disabledTools = prefs?.getDisabledTools() ?: emptySet()
            val selectedTools = DynamicToolSelector.selectTools(registeredTools, taskDescription, disabledTools = disabledTools)
            allTools = selectedTools.associateBy { it.name }
            allToolDefs = selectedTools.map { it.toToolDefinition() }
            val toolDefTokens = TokenEstimator.estimateToolDefinitions(allToolDefs)

            val repoMap = try {
                RepoMapGenerator.generate(project, maxTokens = 1500)
            } catch (_: Exception) { "" }

            val repoContext = try {
                val pluginSettings = com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project)
                val repos = pluginSettings.getRepos()
                if (repos.size > 1) {
                    buildString {
                        appendLine("This project has ${repos.size} configured repositories:")
                        repos.forEach { repo ->
                            val primary = if (repo.isPrimary) " (primary)" else ""
                            val services = buildList {
                                if (!repo.bambooPlanKey.isNullOrBlank()) add("Bamboo: ${repo.bambooPlanKey}")
                                if (!repo.sonarProjectKey.isNullOrBlank()) add("Sonar: ${repo.sonarProjectKey}")
                                if (!repo.bitbucketProjectKey.isNullOrBlank() && !repo.bitbucketRepoSlug.isNullOrBlank())
                                    add("Bitbucket: ${repo.bitbucketProjectKey}/${repo.bitbucketRepoSlug}")
                            }.joinToString(", ")
                            appendLine("- ${repo.name}$primary — $services")
                        }
                        appendLine("\nUse repo_name parameter on Bitbucket, Bamboo, and Sonar tools to target a specific repo.")
                    }
                } else null
            } catch (_: Exception) { null }

            val guardrailsContext = try {
                val basePath = project.basePath
                if (basePath != null) {
                    val store = com.workflow.orchestrator.agent.context.GuardrailStore(java.io.File(basePath))
                    store.load()
                    store.toContextString().ifBlank { null }
                } else null
            } catch (_: Exception) { null }

            val promptAssembler = PromptAssembler(toolRegistry)
            val systemPrompt = promptAssembler.buildSingleAgentPrompt(
                projectName = project.name,
                projectPath = project.basePath,
                repoMapContext = repoMap.ifBlank { null },
                guardrailsContext = guardrailsContext,
                repoContext = repoContext
            )
            val systemPromptTokens = TokenEstimator.estimate(systemPrompt)
            val reservedTokens = toolDefTokens + systemPromptTokens + RESERVED_TOKEN_BUFFER

            contextManager = ContextManager(
                maxInputTokens = maxInputTokens,
                reservedTokens = reservedTokens
            )
            effectiveSystemPrompt = systemPrompt
        }

        // Create LocalHistory checkpoint before execution for rollback capability
        // Reuse rollback manager from session if available (persists labels across turns)
        val rollbackManager = session?.rollbackManager ?: AgentRollbackManager(project)
        if (session != null && session.rollbackManager == null) {
            session.rollbackManager = rollbackManager
        }
        val checkpointId = rollbackManager.createCheckpoint(taskDescription.take(100))

        // Create event log and session trace for observability (per-task, not per-session)
        val traceId = session?.sessionId ?: UUID.randomUUID().toString().take(12)
        val sessionDirForLogs: java.io.File? = session?.store?.sessionDirectory
            ?: project.basePath?.let {
                com.workflow.orchestrator.core.util.ProjectIdentifier.sessionsDir(it)
                    .resolve(traceId).also { dir -> dir.mkdirs() }
            }
        val eventLog = sessionDirForLogs?.let { AgentEventLog(traceId, it) }
        val sessionTrace = sessionDirForLogs?.let { SessionTrace(traceId, it) }
        eventLog?.let {
            it.log(AgentEventType.SNAPSHOT_CREATED, "checkpoint:$checkpointId")
        }

        val agentFileLogger = try { AgentService.getInstance(project).agentFileLogger } catch (_: Exception) { null }
        val singleAgentSession = SingleAgentSession(agentFileLogger = agentFileLogger)
        val result = singleAgentSession.execute(
            task = taskDescription,
            tools = allTools,
            toolDefinitions = allToolDefs,
            brain = brain,
            contextManager = contextManager,
            project = project,
            systemPrompt = effectiveSystemPrompt,
            maxOutputTokens = maxOutputTokens,
            approvalGate = approvalGate,
            eventLog = eventLog,
            sessionTrace = sessionTrace,
            sessionId = traceId,
            onProgress = onProgress,
            onStreamChunk = onStreamChunk,
            onDebugLog = onDebugLog,
            onCheckpoint = { data ->
                session?.saveCheckpoint(
                    iteration = data.iteration,
                    tokensUsed = data.tokensUsed,
                    lastToolCall = data.lastToolCall,
                    rollbackCheckpointId = checkpointId,
                    editedFiles = data.editedFiles,
                    hasPlan = data.hasPlan,
                    lastActivity = data.lastActivity
                )
            }
        )

        // Persist scorecard for trend analysis
        val scorecard = when (result) {
            is SingleAgentResult.Completed -> result.scorecard
            is SingleAgentResult.Failed -> result.scorecard
            is SingleAgentResult.ContextRotated -> result.scorecard
        }
        if (scorecard != null && project.basePath != null) {
            try {
                MetricsStore(project.basePath!!).save(scorecard)
            } catch (e: Exception) {
                LOG.warn("AgentOrchestrator: failed to persist scorecard", e)
            }
        }

        return when (result) {
            is SingleAgentResult.Completed -> {
                result.artifacts.forEach { rollbackManager.trackFileChange(it) }
                AgentResult.Completed(
                    result.summary, result.artifacts, result.tokensUsed,
                    rollbackCheckpointId = checkpointId
                )
            }
            is SingleAgentResult.Failed -> {
                AgentResult.Failed(
                    result.error,
                    rollbackCheckpointId = checkpointId
                )
            }
            is SingleAgentResult.ContextRotated -> {
                AgentResult.ContextRotated(
                    summary = result.summary,
                    rotationStatePath = result.rotationStatePath,
                    tokensUsed = result.tokensUsed
                )
            }
        }
    }

    /**
     * Cancel the currently running task. The next iteration check will return Cancelled.
     */
    fun cancelTask() {
        cancelled.set(true)
        LOG.info("AgentOrchestrator: cancellation requested")
    }

}
