package com.workflow.orchestrator.agent

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.hooks.HookEvent
import com.workflow.orchestrator.agent.hooks.HookManager
import com.workflow.orchestrator.agent.hooks.HookResult
import com.workflow.orchestrator.agent.hooks.HookRunner
import com.workflow.orchestrator.agent.hooks.HookType
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

    /**
     * Hook manager — loaded from .agent-hooks.json in project root.
     * Ported from Cline's hook system: provides lifecycle extensibility points
     * (TaskStart, PreToolUse, PostToolUse, etc.) via shell command hooks.
     *
     * @see <a href="https://github.com/cline/cline/blob/main/src/core/hooks/hook-factory.ts">Cline HookFactory</a>
     */
    val hookManager: HookManager

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

        // Initialize hook system (ported from Cline's HookFactory + getAllHooksDirs)
        val hookRunner = HookRunner(workingDir = basePath)
        hookManager = HookManager(hookRunner)
        hookManager.loadFromConfigFile(basePath)

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

    /**
     * Three-tier tool registration:
     * - Core tools (~21): always sent to LLM on every API call
     * - Deferred tools (~45): available via tool_search, loaded on demand
     * - Conditional: integration tools only registered when their service URL is configured
     *
     * This reduces per-call schema tokens from ~10K to ~4K.
     * The GitTool meta-tool is removed — individual git_status/git_diff/git_log
     * are core, and remaining git tools are deferred.
     */
    private fun registerAllTools() {
        // ── Core tools (always sent to LLM) ──────────────────────────────
        safeRegisterCore { ReadFileTool() }
        safeRegisterCore { EditFileTool() }
        safeRegisterCore { CreateFileTool() }
        safeRegisterCore { SearchCodeTool() }
        safeRegisterCore { GlobFilesTool() }
        safeRegisterCore { RunCommandTool() }
        safeRegisterCore { RevertFileTool() }
        safeRegisterCore { AttemptCompletionTool() }
        safeRegisterCore { ThinkTool() }
        safeRegisterCore { AskQuestionsTool() }
        safeRegisterCore { PlanModeRespondTool() }
        safeRegisterCore { ActModeRespondTool() }
        safeRegisterCore { UseSkillTool() }
        safeRegisterCore { NewTaskTool() }

        // Core VCS — the three most commonly needed git tools
        safeRegisterCore { GitStatusTool() }
        safeRegisterCore { GitDiffTool() }
        safeRegisterCore { GitLogTool() }

        // Core PSI — essential navigation tools
        safeRegisterCore { FindDefinitionTool() }
        safeRegisterCore { FindReferencesTool() }
        safeRegisterCore { SemanticDiagnosticsTool() }

        // tool_search itself is core (the LLM needs it to discover deferred tools)
        safeRegisterCore { ToolSearchTool(registry) }

        // Sub-agent delegation tool
        safeRegisterCore { SpawnAgentTool(
            brainProvider = { createBrain() },
            toolRegistry = registry,
            project = project
        ) }

        // ── Deferred tools (loaded via tool_search) ──────────────────────

        // PSI tools beyond the core 3
        safeRegisterDeferred { FindImplementationsTool() }
        safeRegisterDeferred { FileStructureTool() }
        safeRegisterDeferred { TypeHierarchyTool() }
        safeRegisterDeferred { CallHierarchyTool() }
        safeRegisterDeferred { TypeInferenceTool() }
        safeRegisterDeferred { DataFlowAnalysisTool() }
        safeRegisterDeferred { GetMethodBodyTool() }
        safeRegisterDeferred { GetAnnotationsTool() }
        safeRegisterDeferred { TestFinderTool() }
        safeRegisterDeferred { StructuralSearchTool() }
        safeRegisterDeferred { ReadWriteAccessTool() }

        // IDE tools beyond core
        safeRegisterDeferred { FormatCodeTool() }
        safeRegisterDeferred { OptimizeImportsTool() }
        safeRegisterDeferred { RefactorRenameTool() }
        safeRegisterDeferred { RunInspectionsTool() }
        safeRegisterDeferred { ProblemViewTool() }
        safeRegisterDeferred { ListQuickFixesTool() }

        // VCS tools beyond core 3 (individual tools — GitTool meta-tool removed)
        safeRegisterDeferred { GitBlameTool() }
        safeRegisterDeferred { GitBranchesTool() }
        safeRegisterDeferred { GitShowCommitTool() }
        safeRegisterDeferred { GitShowFileTool() }
        safeRegisterDeferred { GitStashListTool() }
        safeRegisterDeferred { GitFileHistoryTool() }
        safeRegisterDeferred { GitMergeBaseTool() }
        safeRegisterDeferred { ChangelistShelveTool() }

        // Framework tools
        safeRegisterDeferred { BuildTool() }
        safeRegisterDeferred { SpringTool() }

        // Runtime tools
        safeRegisterDeferred { RuntimeExecTool() }
        safeRegisterDeferred { RuntimeConfigTool() }
        safeRegisterDeferred { CoverageTool() }

        // Run config tools
        safeRegisterDeferred { CreateRunConfigTool() }
        safeRegisterDeferred { ModifyRunConfigTool() }
        safeRegisterDeferred { DeleteRunConfigTool() }

        // Database tools
        safeRegisterDeferred { DbListProfilesTool() }
        safeRegisterDeferred { DbQueryTool() }
        safeRegisterDeferred { DbSchemaTool() }

        // VCS explanation tool (ported from Cline's generate_explanation)
        safeRegisterDeferred { GenerateExplanationTool() }

        // Other deferred tools
        safeRegisterDeferred { ProjectContextTool() }
        safeRegisterDeferred { CurrentTimeTool() }
        safeRegisterDeferred { KillProcessTool() }
        safeRegisterDeferred { SendStdinTool() }
        safeRegisterDeferred { AskUserInputTool() }

        // Debug tools (require AgentDebugController)
        registerDebugTools()

        // ── Conditional integration tools ────────────────────────────────
        // Only registered when the service URL is configured in ConnectionSettings
        registerConditionalIntegrationTools()

        log.info("AgentService: registered ${registry.count()} tools " +
            "(${registry.coreCount()} core, ${registry.deferredCount()} deferred)")
    }

    /**
     * Register integration tools conditionally — only when their service URL
     * is configured. Prevents the LLM from seeing tools it can never use.
     */
    private fun registerConditionalIntegrationTools() {
        val connections = ConnectionSettings.getInstance()

        if (connections.state.jiraUrl.isNotBlank()) {
            safeRegisterDeferred { JiraTool() }
        }
        if (connections.state.bambooUrl.isNotBlank()) {
            safeRegisterDeferred { BambooBuildsTool() }
            safeRegisterDeferred { BambooPlansTool() }
        }
        if (connections.state.sonarUrl.isNotBlank()) {
            safeRegisterDeferred { SonarTool() }
        }
        if (connections.state.bitbucketUrl.isNotBlank()) {
            safeRegisterDeferred { BitbucketPrTool() }
            safeRegisterDeferred { BitbucketRepoTool() }
            safeRegisterDeferred { BitbucketReviewTool() }
        }
    }

    private fun registerDebugTools() {
        try {
            val controller = AgentDebugController(project)
            debugController = controller
            registry.registerDeferred(DebugStepTool(controller))
            registry.registerDeferred(DebugInspectTool(controller))
            registry.registerDeferred(DebugBreakpointsTool(controller))
        } catch (e: Exception) {
            log.warn("AgentService: failed to register debug tools: ${e.message}")
        }
    }

    private inline fun safeRegisterCore(factory: () -> AgentTool) {
        try {
            registry.registerCore(factory())
        } catch (e: Exception) {
            log.warn("AgentService: failed to register core tool: ${e.message}")
        }
    }

    private inline fun safeRegisterDeferred(factory: () -> AgentTool) {
        try {
            registry.registerDeferred(factory())
        } catch (e: Exception) {
            log.warn("AgentService: failed to register deferred tool: ${e.message}")
        }
    }

    /** Backward-compatible register — delegates to core. */
    private inline fun safeRegister(factory: () -> AgentTool) {
        safeRegisterCore(factory)
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
                // TASK_START hook (ported from Cline's TaskStart hook)
                // Fires before the agent loop begins. Cancellable: can abort the task.
                // Cline: executeHook({ hookName: "TaskStart", hookInput: { taskStart: { task } }, ... })
                if (hookManager.hasHooks(HookType.TASK_START)) {
                    val hookResult = hookManager.dispatch(
                        HookEvent(
                            type = HookType.TASK_START,
                            data = mapOf(
                                "task" to task,
                                "sessionId" to sid
                            )
                        )
                    )
                    if (hookResult is HookResult.Cancel) {
                        log.info("AgentService: TASK_START hook cancelled task: ${hookResult.reason}")
                        session = session.copy(
                            status = SessionStatus.CANCELLED,
                            lastMessageAt = System.currentTimeMillis()
                        )
                        sessionStore.save(session)
                        onComplete(LoopResult.Cancelled(iterations = 0))
                        return@launch
                    }
                }

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

                // Load project instructions (CLAUDE.md) and all skills (bundled + user)
                val projectInstructions = InstructionLoader.loadProjectInstructions(projectPath)
                val allSkills = InstructionLoader.loadAllSkills(projectPath)
                val availableSkills = allSkills.map { it.name to it.description }
                    .ifEmpty { null }

                // Reset active deferred tools for new sessions (not resumed ones)
                if (contextManager == null) {
                    registry.resetActiveDeferred()
                }

                // Build deferred catalog for system prompt injection
                val deferredCatalog = registry.getDeferredCatalog()

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
                    taskProgress = ctx.getTaskProgress(),
                    deferredToolCatalog = deferredCatalog
                )
                ctx.setSystemPrompt(systemPrompt)

                // Build tool definitions dynamically — uses getActiveTools() which grows
                // as tool_search activates deferred tools during the session.
                // Plan mode: remove write tools + act_mode_respond, keep plan_mode_respond.
                // Act mode: remove plan_mode_respond, keep act_mode_respond + write tools.
                val isPlanMode = planModeActive.get()

                // Dynamic tool definition provider — called on each loop iteration
                val toolDefinitionProvider: () -> List<com.workflow.orchestrator.core.ai.dto.ToolDefinition> = {
                    registry.getActiveTools().values
                        .filter { tool ->
                            if (isPlanMode) {
                                tool.name !in writeToolNames && tool.name != "act_mode_respond"
                            } else {
                                tool.name != "plan_mode_respond"
                            }
                        }
                        .map { it.toToolDefinition() }
                }

                // Initial tool definitions (also used as fallback in AgentLoop)
                val toolDefs = toolDefinitionProvider()
                // Tool map for execution — use registry.get() to resolve any tool including deferred
                val tools = registry.getActiveTools()

                // Track the message count before this turn, so we can
                // checkpoint only newly-added messages (JSONL append).
                var lastCheckpointedCount = ctx.messageCount()

                // Write checkpoint counter — create checkpoint after write operations
                var writeCheckpointCounter = 0

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
                    toolDefinitionProvider = toolDefinitionProvider,
                    toolResolver = { name -> registry.get(name) },
                    hookManager = if (hookManager.hasAnyHooks()) hookManager else null,
                    sessionId = sid,
                    onWriteCheckpoint = { toolName, args ->
                        // Create named checkpoint after write operations (ported from Cline)
                        writeCheckpointCounter++
                        try {
                            val checkpointId = "cp-${writeCheckpointCounter}-${System.currentTimeMillis()}"
                            val description = "After $toolName: ${args.take(100)}"
                            sessionStore.saveCheckpoint(
                                sessionId = sid,
                                checkpointId = checkpointId,
                                messages = ctx.exportMessages(),
                                description = description
                            )
                        } catch (e: Exception) {
                            log.warn("AgentService: write checkpoint save failed (non-fatal)", e)
                        }
                    },
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
                // Extract token counts from result (ported from Cline's tokensIn/tokensOut)
                val tokensUsed = when (result) {
                    is LoopResult.Completed -> result.tokensUsed
                    is LoopResult.Failed -> result.tokensUsed
                    is LoopResult.Cancelled -> result.tokensUsed
                    is LoopResult.PlanPresented -> result.tokensUsed
                    is LoopResult.SessionHandoff -> result.tokensUsed
                }
                val inputTokens = when (result) {
                    is LoopResult.Completed -> result.inputTokens
                    is LoopResult.Failed -> result.inputTokens
                    is LoopResult.Cancelled -> result.inputTokens
                    is LoopResult.PlanPresented -> result.inputTokens
                    is LoopResult.SessionHandoff -> result.inputTokens
                }
                val outputTokens = when (result) {
                    is LoopResult.Completed -> result.outputTokens
                    is LoopResult.Failed -> result.outputTokens
                    is LoopResult.Cancelled -> result.outputTokens
                    is LoopResult.PlanPresented -> result.outputTokens
                    is LoopResult.SessionHandoff -> result.outputTokens
                }
                session = session.copy(
                    status = when (result) {
                        is LoopResult.Completed -> SessionStatus.COMPLETED
                        is LoopResult.Failed -> SessionStatus.FAILED
                        is LoopResult.Cancelled -> SessionStatus.CANCELLED
                        is LoopResult.PlanPresented -> SessionStatus.ACTIVE
                        is LoopResult.SessionHandoff -> SessionStatus.COMPLETED
                    },
                    lastMessageAt = System.currentTimeMillis(),
                    totalTokens = tokensUsed,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
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

        // TASK_RESUME hook (ported from Cline's TaskResume hook)
        // Cancellable: can prevent session resumption.
        // Cline: "Executes when a task is resumed after being interrupted."
        if (hookManager.hasHooks(HookType.TASK_RESUME)) {
            val hookResult = kotlinx.coroutines.runBlocking {
                hookManager.dispatch(
                    HookEvent(
                        type = HookType.TASK_RESUME,
                        data = mapOf(
                            "sessionId" to sessionId,
                            "messageCount" to savedMessages.size
                        )
                    )
                )
            }
            if (hookResult is HookResult.Cancel) {
                log.info("AgentService: TASK_RESUME hook cancelled resume: ${hookResult.reason}")
                return null
            }
        }

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

    // ── Checkpoint Reversion (ported from Cline's checkpoint reversion) ─────

    /**
     * Revert a session to a specific checkpoint.
     *
     * Ported from Cline's checkpoint reversion pattern:
     * 1. Load the checkpoint messages
     * 2. Restore ContextManager state to that point
     * 3. Delete later checkpoints (they're invalidated)
     * 4. Overwrite the session's messages with the checkpoint
     * 5. Continue from that point
     *
     * @param sessionId the session to revert
     * @param checkpointId the checkpoint to revert to
     * @param onStreamChunk streaming callback
     * @param onToolCall tool progress callback
     * @param onTaskProgress progress callback
     * @param onComplete completion callback
     * @return the Job for the continued session, or null if checkpoint not found
     */
    fun revertToCheckpoint(
        sessionId: String,
        checkpointId: String,
        onStreamChunk: (String) -> Unit = {},
        onToolCall: (ToolCallProgress) -> Unit = {},
        onTaskProgress: (TaskProgress) -> Unit = {},
        onComplete: (LoopResult) -> Unit = {}
    ): Job? {
        // Load checkpoint
        val checkpointMessages = sessionStore.loadCheckpoint(sessionId, checkpointId)
        if (checkpointMessages == null) {
            log.warn("AgentService.revertToCheckpoint: checkpoint $checkpointId not found for session $sessionId")
            return null
        }

        // Load session metadata
        val session = sessionStore.load(sessionId)
        if (session == null) {
            log.warn("AgentService.revertToCheckpoint: session $sessionId not found")
            return null
        }

        // Rebuild ContextManager from checkpoint
        val agentSettings = AgentSettings.getInstance(project)
        val ctx = ContextManager(maxInputTokens = agentSettings.state.maxInputTokens)

        if (session.systemPrompt.isNotBlank()) {
            ctx.setSystemPrompt(session.systemPrompt)
        }

        ctx.restoreMessages(checkpointMessages)

        // Overwrite session's messages with checkpoint state
        sessionStore.saveMessages(sessionId, checkpointMessages)

        // Delete checkpoints after this one (they're invalidated by reversion)
        sessionStore.deleteCheckpointsAfter(sessionId, checkpointId)

        // Update session metadata
        val updatedSession = session.copy(
            status = SessionStatus.ACTIVE,
            messageCount = checkpointMessages.size,
            lastMessageAt = System.currentTimeMillis()
        )
        sessionStore.save(updatedSession)

        log.info("AgentService.revertToCheckpoint: reverted session $sessionId to checkpoint $checkpointId " +
            "(${checkpointMessages.size} messages)")

        // Continue execution from the checkpoint
        return executeTask(
            task = "Continue from where you left off. The conversation has been reverted to an earlier checkpoint.",
            sessionId = sessionId,
            contextManager = ctx,
            onStreamChunk = onStreamChunk,
            onToolCall = onToolCall,
            onTaskProgress = onTaskProgress,
            onComplete = onComplete
        )
    }

    /**
     * List checkpoints for a session.
     *
     * @param sessionId the session to list checkpoints for
     * @return list of checkpoint metadata, newest first
     */
    fun listCheckpoints(sessionId: String): List<com.workflow.orchestrator.agent.session.CheckpointInfo> {
        return sessionStore.listCheckpoints(sessionId)
    }

    // ── Session Handoff (ported from Cline's new_task) ──────────────────────

    /**
     * Start a new session with handoff context from a completed session.
     *
     * Ported from Cline's new_task flow:
     * 1. Save the current session as COMPLETED
     * 2. Create a new session
     * 3. Create a fresh ContextManager
     * 4. Inject the handoff context as the first user message
     * 5. Start a new AgentLoop with the fresh context
     *
     * @param handoffContext the structured context summary from the LLM
     * @param onStreamChunk streaming callback
     * @param onToolCall tool progress callback
     * @param onTaskProgress task progress callback
     * @param onComplete completion callback
     * @return the Job for the new session
     */
    fun startHandoffSession(
        handoffContext: String,
        onStreamChunk: (String) -> Unit = {},
        onToolCall: (ToolCallProgress) -> Unit = {},
        onTaskProgress: (TaskProgress) -> Unit = {},
        onComplete: (LoopResult) -> Unit = {}
    ): Job {
        // The handoff context becomes the task for the new session
        val preamble = "Continue from the previous session. Here is the preserved context:\n\n$handoffContext"

        return executeTask(
            task = preamble,
            sessionId = null, // new session ID
            contextManager = null, // fresh context
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
     *
     * Dispatches TASK_CANCEL hook (observation-only, ported from Cline's TaskCancel).
     * Cline: "Executes when a task is cancelled by the user."
     */
    fun cancelCurrentTask() {
        activeTask.get()?.let { task ->
            task.loop.cancel()
            task.job.cancel()

            // TASK_CANCEL hook — observation only, fire-and-forget
            // Cline: TaskCancel is non-cancellable (observation only)
            if (hookManager.hasHooks(HookType.TASK_CANCEL)) {
                scope.launch {
                    try {
                        hookManager.dispatch(
                            HookEvent(
                                type = HookType.TASK_CANCEL,
                                data = mapOf(
                                    "reason" to "user_cancelled"
                                )
                            )
                        )
                    } catch (e: Exception) {
                        log.warn("AgentService: TASK_CANCEL hook failed (non-fatal)", e)
                    }
                }
            }
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
