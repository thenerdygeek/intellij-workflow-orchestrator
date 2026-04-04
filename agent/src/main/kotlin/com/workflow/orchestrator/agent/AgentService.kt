package com.workflow.orchestrator.agent

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.loop.AgentLoop
import com.workflow.orchestrator.agent.loop.ContextManager
import com.workflow.orchestrator.agent.loop.LoopResult
import com.workflow.orchestrator.agent.loop.TaskProgress
import com.workflow.orchestrator.agent.loop.ToolCallProgress
import com.workflow.orchestrator.agent.prompt.InstructionLoader
import com.workflow.orchestrator.agent.prompt.SystemPrompt
import com.workflow.orchestrator.agent.session.Session
import com.workflow.orchestrator.agent.session.SessionStatus
import com.workflow.orchestrator.agent.session.SessionStore
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.tools.builtin.*
import com.workflow.orchestrator.agent.tools.config.*
import com.workflow.orchestrator.agent.tools.database.*
import com.workflow.orchestrator.agent.tools.debug.AgentDebugController
import com.workflow.orchestrator.agent.tools.debug.DebugBreakpointsTool
import com.workflow.orchestrator.agent.tools.debug.DebugInspectTool
import com.workflow.orchestrator.agent.tools.debug.DebugStepTool
import com.workflow.orchestrator.agent.tools.framework.*
import com.workflow.orchestrator.agent.tools.ide.*
import com.workflow.orchestrator.agent.tools.integration.*
import com.workflow.orchestrator.agent.tools.process.ProcessRegistry
import com.workflow.orchestrator.agent.tools.psi.*
import com.workflow.orchestrator.agent.tools.runtime.*
import com.workflow.orchestrator.agent.tools.vcs.*
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.LlmBrainFactory
import com.workflow.orchestrator.core.ai.OpenAiCompatBrain
import com.workflow.orchestrator.core.ai.dto.ToolDefinition
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.core.util.ProjectIdentifier
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Central agent service — wires the AgentLoop, ToolRegistry, ContextManager,
 * SessionStore, and LLM brain together. Exposes [executeTask] for the UI layer.
 *
 * IntelliJ project-level service: one instance per open project.
 */
@Service(Service.Level.PROJECT)
class AgentService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(AgentService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val registry = ToolRegistry()
    val sessionStore: SessionStore

    /** Atomic reference tracking both the loop and job together to avoid race conditions. */
    private data class ActiveTask(val loop: AgentLoop, val job: Job)
    private val activeTask = AtomicReference<ActiveTask?>(null)

    private var debugController: AgentDebugController? = null

    /** Tool names that are blocked in plan mode (write/mutate tools). */
    private val writeToolNames = setOf(
        "edit_file", "create_file", "run_command", "revert_file",
        "kill_process", "send_stdin", "format_code", "optimize_imports",
        "refactor_rename"
    )

    init {
        val basePath = project.basePath ?: System.getProperty("user.home")
        val agentDir = ProjectIdentifier.agentDir(basePath)
        sessionStore = SessionStore(agentDir)
        registerAllTools()
    }

    // ── Brain ──────────────────────────────────────────────────────────────

    /**
     * Creates a fresh LLM brain for each task execution.
     * Never cached — always picks up the latest settings (model, URL, token).
     */
    private suspend fun createBrain(): LlmBrain {
        val agentSettings = AgentSettings.getInstance(project)
        val connections = ConnectionSettings.getInstance()
        val sgUrl = connections.state.sourcegraphUrl.trimEnd('/')
        val credentialStore = CredentialStore()
        val tokenProvider = { credentialStore.getToken(ServiceType.SOURCEGRAPH) }

        val model = agentSettings.state.sourcegraphChatModel
        if (!model.isNullOrBlank() && sgUrl.isNotBlank()) {
            return OpenAiCompatBrain(
                sourcegraphUrl = sgUrl,
                tokenProvider = tokenProvider,
                model = model
            )
        }

        // Fall back to factory auto-resolution
        return LlmBrainFactory.create(project)
    }

    // ── Tool Registration ──────────────────────────────────────────────────

    private fun registerAllTools() {
        // Builtin tools
        safeRegister { ReadFileTool() }
        safeRegister { EditFileTool() }
        safeRegister { CreateFileTool() }
        safeRegister { SearchCodeTool() }
        safeRegister { GlobFilesTool() }
        safeRegister { RunCommandTool() }
        safeRegister { KillProcessTool() }
        safeRegister { SendStdinTool() }
        safeRegister { RevertFileTool() }
        safeRegister { AttemptCompletionTool() }
        safeRegister { ThinkTool() }
        safeRegister { AskQuestionsTool() }
        safeRegister { AskUserInputTool() }
        safeRegister { ProjectContextTool() }
        safeRegister { CurrentTimeTool() }
        safeRegister { PlanModeRespondTool() }
        safeRegister { ActModeRespondTool() }
        safeRegister { UseSkillTool() }

        // VCS tools
        safeRegister { GitTool() }
        safeRegister { GitStatusTool() }
        safeRegister { GitDiffTool() }
        safeRegister { GitLogTool() }
        safeRegister { GitBranchesTool() }
        safeRegister { GitBlameTool() }
        safeRegister { GitShowCommitTool() }
        safeRegister { GitShowFileTool() }
        safeRegister { GitStashListTool() }
        safeRegister { GitFileHistoryTool() }
        safeRegister { GitMergeBaseTool() }
        safeRegister { ChangelistShelveTool() }

        // PSI / code intelligence tools
        safeRegister { FindDefinitionTool() }
        safeRegister { FindReferencesTool() }
        safeRegister { FindImplementationsTool() }
        safeRegister { FileStructureTool() }
        safeRegister { TypeHierarchyTool() }
        safeRegister { CallHierarchyTool() }
        safeRegister { TypeInferenceTool() }
        safeRegister { DataFlowAnalysisTool() }
        safeRegister { GetMethodBodyTool() }
        safeRegister { GetAnnotationsTool() }
        safeRegister { TestFinderTool() }
        safeRegister { StructuralSearchTool() }
        safeRegister { ReadWriteAccessTool() }

        // IDE tools
        safeRegister { FormatCodeTool() }
        safeRegister { OptimizeImportsTool() }
        safeRegister { RefactorRenameTool() }
        safeRegister { SemanticDiagnosticsTool() }
        safeRegister { RunInspectionsTool() }
        safeRegister { ProblemViewTool() }
        safeRegister { ListQuickFixesTool() }

        // Database tools
        safeRegister { DbListProfilesTool() }
        safeRegister { DbQueryTool() }
        safeRegister { DbSchemaTool() }

        // Framework tools
        safeRegister { BuildTool() }
        safeRegister { SpringTool() }

        // Run config tools
        safeRegister { CreateRunConfigTool() }
        safeRegister { ModifyRunConfigTool() }
        safeRegister { DeleteRunConfigTool() }

        // Integration tools (Jira, Bamboo, Bitbucket, Sonar)
        safeRegister { JiraTool() }
        safeRegister { BambooBuildsTool() }
        safeRegister { BambooPlansTool() }
        safeRegister { BitbucketPrTool() }
        safeRegister { BitbucketRepoTool() }
        safeRegister { BitbucketReviewTool() }
        safeRegister { SonarTool() }

        // Runtime tools
        safeRegister { RuntimeExecTool() }
        safeRegister { RuntimeConfigTool() }
        safeRegister { CoverageTool() }

        // Debug tools (require AgentDebugController)
        registerDebugTools()

        // Sub-agent delegation tool (depth-1: sub-agents cannot spawn further sub-agents)
        safeRegister { SpawnAgentTool(
            brainProvider = { createBrain() },
            toolRegistry = registry,
            project = project
        ) }

        log.info("AgentService: registered ${registry.allTools().size} tools")
    }

    private fun registerDebugTools() {
        try {
            val controller = AgentDebugController(project)
            debugController = controller
            registry.register(DebugStepTool(controller))
            registry.register(DebugInspectTool(controller))
            registry.register(DebugBreakpointsTool(controller))
        } catch (e: Exception) {
            log.warn("AgentService: failed to register debug tools: ${e.message}")
        }
    }

    private inline fun safeRegister(factory: () -> AgentTool) {
        try {
            registry.register(factory())
        } catch (e: Exception) {
            log.warn("AgentService: failed to register tool: ${e.message}")
        }
    }

    // ── Task Execution ─────────────────────────────────────────────────────

    /**
     * Execute a task in the agent loop. Returns a Job for cancellation.
     *
     * Checkpoint integration (ported from Cline):
     * Cline's message-state.ts calls saveApiConversationHistory inside every
     * addToApiConversationHistory call, persisting the full conversation history
     * after every message mutation. We replicate this via the AgentLoop's
     * onCheckpoint callback, which fires after every tool result is added to
     * context, writing both session metadata and the latest message to JSONL.
     *
     * @param task The user's request.
     * @param sessionId Reuse existing session ID for resume, or null for new.
     * @param contextManager Reuse for multi-turn, or null for new conversation.
     * @param onStreamChunk Streaming text callback (each LLM chunk).
     * @param onToolCall Tool progress callback.
     * @param onComplete Called when the loop finishes.
     */
    fun executeTask(
        task: String,
        sessionId: String? = null,
        contextManager: ContextManager? = null,
        onStreamChunk: (String) -> Unit = {},
        onToolCall: (ToolCallProgress) -> Unit = {},
        onTaskProgress: (TaskProgress) -> Unit = {},
        onComplete: (LoopResult) -> Unit = {}
    ): Job {
        val sid = sessionId ?: UUID.randomUUID().toString()
        var session = Session(
            id = sid,
            title = task.take(100),
            status = SessionStatus.ACTIVE
        )
        sessionStore.save(session)

        val job = scope.launch {
            try {
                // I3: Create a fresh brain each time to pick up settings changes
                val brain = createBrain()
                val agentSettings = AgentSettings.getInstance(project)

                // Build context manager
                val ctx = contextManager ?: ContextManager(
                    maxInputTokens = agentSettings.state.maxInputTokens
                )

                // I7: Always re-set system prompt (plan mode may have changed between turns)
                val projectName = project.name
                val projectPath = project.basePath ?: ""

                // Load project instructions (CLAUDE.md) and bundled skills
                val projectInstructions = InstructionLoader.loadProjectInstructions(projectPath)
                val bundledSkills = InstructionLoader.loadBundledSkills()
                val availableSkills = bundledSkills.map { it.name to it.description }
                    .ifEmpty { null }

                val systemPrompt = SystemPrompt.build(
                    projectName = projectName,
                    projectPath = projectPath,
                    osName = System.getProperty("os.name") ?: "Unknown",
                    shell = if ((System.getProperty("os.name") ?: "").lowercase().contains("win"))
                        System.getenv("COMSPEC") ?: "cmd.exe"
                    else
                        System.getenv("SHELL") ?: "/bin/bash",
                    planModeEnabled = planModeActive.get(),
                    additionalContext = projectInstructions,
                    availableSkills = availableSkills,
                    activeSkillContent = ctx.getActiveSkill(),
                    taskProgress = ctx.getTaskProgress()
                )
                ctx.setSystemPrompt(systemPrompt)

                // Build tool definitions, filtering by mode.
                // Plan mode: remove write tools + act_mode_respond, keep plan_mode_respond.
                // Act mode: remove plan_mode_respond, keep act_mode_respond + write tools.
                // Ported from Cline's mode-specific tool schema filtering.
                val tools = registry.allTools().associateBy { it.name }
                val isPlanMode = planModeActive.get()
                val toolDefs = tools.values
                    .filter { tool ->
                        if (isPlanMode) {
                            tool.name !in writeToolNames && tool.name != "act_mode_respond"
                        } else {
                            tool.name != "plan_mode_respond"
                        }
                    }
                    .map { it.toToolDefinition() }

                // Track the message count before this turn, so we can
                // checkpoint only newly-added messages (JSONL append).
                var lastCheckpointedCount = ctx.messageCount()

                val loop = AgentLoop(
                    brain = brain,
                    tools = tools,
                    toolDefinitions = toolDefs,
                    contextManager = ctx,
                    project = project,
                    onStreamChunk = onStreamChunk,
                    onToolCall = onToolCall,
                    onTaskProgress = onTaskProgress,
                    planMode = planModeActive.get(),
                    onCheckpoint = {
                        // Checkpoint: persist new messages since last checkpoint.
                        // Ported from Cline's message-state.ts pattern where
                        // saveApiConversationHistory is called on every state change.
                        // We use JSONL append for efficiency (Cline rewrites the whole file).
                        try {
                            val allMessages = ctx.exportMessages()
                            val newMessages = allMessages.subList(
                                lastCheckpointedCount.coerceAtMost(allMessages.size),
                                allMessages.size
                            )
                            for (msg in newMessages) {
                                sessionStore.appendMessage(sid, msg)
                            }
                            lastCheckpointedCount = allMessages.size

                            // Update session metadata with latest state
                            // (Cline does this in saveClineMessagesAndUpdateHistoryInternal)
                            session = session.copy(
                                messageCount = allMessages.size,
                                lastMessageAt = System.currentTimeMillis(),
                                systemPrompt = ctx.getSystemPromptContent() ?: "",
                                planModeEnabled = planModeActive.get(),
                                lastToolCallId = allMessages.lastOrNull { it.role == "tool" }?.toolCallId
                            )
                            sessionStore.save(session)
                        } catch (e: Exception) {
                            log.warn("AgentService: checkpoint save failed (non-fatal)", e)
                        }
                    }
                )

                // I4: Set activeTask atomically after both loop and job are available
                activeTask.set(ActiveTask(loop = loop, job = coroutineContext.job))

                val result = loop.run(task)

                // Final checkpoint: save all remaining messages
                try {
                    val allMessages = ctx.exportMessages()
                    val remaining = allMessages.subList(
                        lastCheckpointedCount.coerceAtMost(allMessages.size),
                        allMessages.size
                    )
                    for (msg in remaining) {
                        sessionStore.appendMessage(sid, msg)
                    }
                } catch (e: Exception) {
                    log.warn("AgentService: final checkpoint save failed (non-fatal)", e)
                }

                // I5: Update session via .copy() (Session is now fully immutable)
                val tokensUsed = when (result) {
                    is LoopResult.Completed -> result.tokensUsed
                    is LoopResult.Failed -> result.tokensUsed
                    is LoopResult.Cancelled -> result.tokensUsed
                    is LoopResult.PlanPresented -> result.tokensUsed
                }
                session = session.copy(
                    status = when (result) {
                        is LoopResult.Completed -> SessionStatus.COMPLETED
                        is LoopResult.Failed -> SessionStatus.FAILED
                        is LoopResult.Cancelled -> SessionStatus.CANCELLED
                        is LoopResult.PlanPresented -> SessionStatus.ACTIVE
                    },
                    lastMessageAt = System.currentTimeMillis(),
                    totalTokens = tokensUsed,
                    messageCount = ctx.messageCount()
                )
                sessionStore.save(session)

                onComplete(result)
            } catch (e: CancellationException) {
                session = session.copy(
                    status = SessionStatus.CANCELLED,
                    lastMessageAt = System.currentTimeMillis()
                )
                sessionStore.save(session)
                onComplete(LoopResult.Cancelled(iterations = 0))
            } catch (e: Exception) {
                log.error("AgentService: task execution failed", e)
                session = session.copy(
                    status = SessionStatus.FAILED,
                    lastMessageAt = System.currentTimeMillis()
                )
                sessionStore.save(session)
                onComplete(LoopResult.Failed(error = e.message ?: "Unknown error"))
            } finally {
                activeTask.set(null)
            }
        }
        return job
    }

    // ── Session Resume ────────────────────────────────────────────────────

    /**
     * Resume a previously interrupted session from its checkpoint.
     *
     * Faithful port of Cline's task resumption pattern:
     * 1. Load session metadata (Cline's readTaskHistoryFromState + HistoryItem)
     * 2. Load conversation history (Cline's getSavedApiConversationHistory)
     * 3. Rebuild ContextManager with saved messages (Cline's setApiConversationHistory)
     * 4. Rebuild system prompt from saved settings (Cline's readTaskSettingsFromStorage)
     * 5. Continue execution — the LLM sees full history and picks up where it left off
     *
     * @param sessionId ID of the session to resume
     * @param continuationMessage optional message to inject (e.g. "Continue from where you left off")
     * @param onStreamChunk streaming callback
     * @param onToolCall tool progress callback
     * @param onComplete completion callback
     * @return the Job, or null if session not found or not resumable
     *
     * @see <a href="https://github.com/cline/cline/blob/main/src/core/storage/disk.ts">Cline disk.ts getSavedApiConversationHistory</a>
     */
    fun resumeSession(
        sessionId: String,
        continuationMessage: String = "Continue from where you left off. Your previous conversation history has been restored.",
        onStreamChunk: (String) -> Unit = {},
        onToolCall: (ToolCallProgress) -> Unit = {},
        onTaskProgress: (TaskProgress) -> Unit = {},
        onComplete: (LoopResult) -> Unit = {}
    ): Job? {
        // Step 1: Load session metadata
        val session = sessionStore.load(sessionId)
        if (session == null) {
            log.warn("AgentService.resumeSession: session $sessionId not found")
            return null
        }

        // Only resume sessions that were interrupted (not already completed)
        if (session.status == SessionStatus.COMPLETED) {
            log.warn("AgentService.resumeSession: session $sessionId already completed")
            return null
        }

        // Step 2: Load conversation history
        val savedMessages = sessionStore.loadMessages(sessionId)
        if (savedMessages.isEmpty()) {
            log.warn("AgentService.resumeSession: no messages found for session $sessionId")
            return null
        }

        // Step 3: Rebuild ContextManager with saved messages
        val agentSettings = AgentSettings.getInstance(project)
        val ctx = ContextManager(maxInputTokens = agentSettings.state.maxInputTokens)

        // Step 4: Restore system prompt from session metadata
        if (session.systemPrompt.isNotBlank()) {
            ctx.setSystemPrompt(session.systemPrompt)
        } else {
            // Rebuild system prompt from current settings (fallback)
            val projectName = project.name
            val projectPath = project.basePath ?: ""
            val systemPrompt = SystemPrompt.build(
                projectName = projectName,
                projectPath = projectPath,
                osName = System.getProperty("os.name") ?: "Unknown",
                shell = if ((System.getProperty("os.name") ?: "").lowercase().contains("win"))
                    System.getenv("COMSPEC") ?: "cmd.exe"
                else
                    System.getenv("SHELL") ?: "/bin/bash",
                planModeEnabled = session.planModeEnabled
            )
            ctx.setSystemPrompt(systemPrompt)
        }

        // Restore saved messages into context manager
        ctx.restoreMessages(savedMessages)

        // Restore plan mode state from session
        planModeActive.set(session.planModeEnabled)

        log.info("AgentService.resumeSession: restoring session $sessionId with ${savedMessages.size} messages")

        // Step 5: Continue execution with the restored context
        return executeTask(
            task = continuationMessage,
            sessionId = sessionId,
            contextManager = ctx,
            onStreamChunk = onStreamChunk,
            onToolCall = onToolCall,
            onTaskProgress = onTaskProgress,
            onComplete = onComplete
        )
    }

    // ── Cancel ─────────────────────────────────────────────────────────────

    /**
     * Cancel the currently running task. Safe to call from any thread.
     * Uses atomic ActiveTask reference to avoid race between loop and job.
     */
    fun cancelCurrentTask() {
        activeTask.get()?.let { task ->
            task.loop.cancel()
            task.job.cancel()
        }
    }

    // ── Dispose ────────────────────────────────────────────────────────────

    override fun dispose() {
        cancelCurrentTask()
        scope.cancel("AgentService disposed")
        ProcessRegistry.killAll()
        debugController?.dispose()
    }

    companion object {
        val planModeActive = AtomicBoolean(false)

        fun getInstance(project: Project): AgentService =
            project.service<AgentService>()
    }
}
