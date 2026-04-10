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
import com.workflow.orchestrator.agent.loop.SteeringMessage
import com.workflow.orchestrator.agent.loop.TaskProgress
import com.workflow.orchestrator.agent.loop.ToolCallProgress
import com.workflow.orchestrator.agent.prompt.EnvironmentDetailsBuilder
import com.workflow.orchestrator.agent.prompt.InstructionLoader
import com.workflow.orchestrator.agent.prompt.SystemPrompt
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.agent.session.Session
import com.workflow.orchestrator.agent.session.SessionStatus
import com.workflow.orchestrator.agent.session.SessionStore
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.memory.ArchivalMemory
import com.workflow.orchestrator.agent.memory.ConversationRecall
import com.workflow.orchestrator.agent.memory.CoreMemory
import com.workflow.orchestrator.agent.observability.AgentFileLogger
import com.workflow.orchestrator.agent.observability.SessionMetrics
import com.workflow.orchestrator.agent.tools.builtin.*
import com.workflow.orchestrator.agent.tools.database.*
import com.workflow.orchestrator.agent.tools.debug.AgentDebugController
import com.workflow.orchestrator.agent.tools.debug.DebugBreakpointsTool
import com.workflow.orchestrator.agent.tools.debug.DebugInspectTool
import com.workflow.orchestrator.agent.tools.debug.DebugStepTool
import com.workflow.orchestrator.agent.tools.framework.*
import com.workflow.orchestrator.agent.tools.ide.*
import com.workflow.orchestrator.agent.tools.integration.*
import com.workflow.orchestrator.agent.tools.memory.*
import com.workflow.orchestrator.agent.tools.process.ProcessRegistry
import com.workflow.orchestrator.agent.tools.subagent.AgentConfigLoader
import com.workflow.orchestrator.agent.tools.subagent.SubagentProgressUpdate
import com.workflow.orchestrator.agent.tools.psi.*
import com.workflow.orchestrator.agent.tools.runtime.*
import com.workflow.orchestrator.agent.tools.vcs.*
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.LlmBrainFactory
import com.workflow.orchestrator.core.ai.ModelCache
import com.workflow.orchestrator.core.ai.OpenAiCompatBrain
import com.workflow.orchestrator.core.ai.SourcegraphChatClient
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.agent.loop.ModelFallbackManager
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.core.util.ProjectIdentifier
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
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

    /** Dedicated structured agent log file — one per project, lives for plugin lifetime. */
    private val fileLogger: AgentFileLogger by lazy {
        AgentFileLogger(logDir = ProjectIdentifier.logsDir(project.basePath ?: ""))
    }

    private var debugController: AgentDebugController? = null
    private var coreMemory: CoreMemory? = null
    private var archivalMemory: ArchivalMemory? = null
    private var conversationRecall: ConversationRecall? = null
    private lateinit var agentDir: java.io.File

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
        this.agentDir = agentDir
        sessionStore = SessionStore(agentDir)

        // Initialize 3-tier memory system (Letta pattern)
        val coreMem = CoreMemory.forProject(agentDir)
        val archivalMemory = ArchivalMemory.forProject(agentDir)
        val conversationRecall = ConversationRecall.forProject(agentDir)
        this.coreMemory = coreMem
        this.archivalMemory = archivalMemory
        this.conversationRecall = conversationRecall

        // Prune stale archival entries on startup (Codex decay pattern)
        val pruned = archivalMemory.prune()
        if (pruned > 0) log.info("[AgentService] Pruned $pruned stale archival memories")

        // Initialize hook system (ported from Cline's HookFactory + getAllHooksDirs)
        val hookRunner = HookRunner(workingDir = basePath)
        hookManager = HookManager(hookRunner)
        hookManager.loadFromConfigFile(basePath)

        registerAllTools()

        // Task 7: Load dynamic agent configurations and register lifecycle
        val configLoader = AgentConfigLoader.getInstance()
        configLoader.loadFromDisk()
        val configs = configLoader.getAllCachedConfigsWithToolNames()
        if (configs.isNotEmpty()) {
            log.info("[AgentService] Loaded ${configs.size} dynamic agent config(s): ${configs.keys.toList()}")
        }
        com.intellij.openapi.util.Disposer.register(this, configLoader)
    }

    // ── Brain ──────────────────────────────────────────────────────────────

    /**
     * Creates a fresh LLM brain for each task execution.
     * Never cached — always picks up the latest settings (model, URL, token).
     */
    private suspend fun createBrain(): LlmBrain {
        val connections = ConnectionSettings.getInstance()
        val sgUrl = connections.state.sourcegraphUrl.trimEnd('/')
        val credentialStore = CredentialStore()
        val tokenProvider = { credentialStore.getToken(ServiceType.SOURCEGRAPH) }

        if (sgUrl.isBlank()) {
            throw IllegalStateException("No Sourcegraph URL configured. Set one in Settings > AI & Advanced.")
        }

        // Always fetch models and pick the best (latest Opus).
        // If fetch fails, fall back to settings or factory auto-resolution.
        val client = SourcegraphChatClient(baseUrl = sgUrl, tokenProvider = tokenProvider, model = "")
        val models = try {
            ModelCache.getModels(client)
        } catch (e: Exception) {
            log.warn("[Agent] Failed to fetch models from Sourcegraph: ${e.message}")
            emptyList()
        }
        val best = ModelCache.pickBest(models)

        val modelId = if (best != null) {
            log.info("[Agent] Auto-selected model: ${best.modelName} (${best.id})")
            best.id
        } else {
            // Model fetch failed or returned empty — try settings
            val settingsModel = AgentSettings.getInstance(project).state.sourcegraphChatModel
            if (!settingsModel.isNullOrBlank()) {
                log.info("[Agent] Models unavailable, using settings model: $settingsModel")
                settingsModel
            } else {
                // Last resort — try factory which may have cached models
                log.warn("[Agent] No models available and no model configured. Trying factory auto-resolution.")
                try {
                    return LlmBrainFactory.create(project)
                } catch (e: Exception) {
                    throw IllegalStateException(
                        "Cannot start agent: failed to fetch models from Sourcegraph ($sgUrl) " +
                        "and no model is configured in settings. " +
                        "Please check your Sourcegraph URL and token in Settings > AI & Advanced. " +
                        "Error: ${e.message}"
                    )
                }
            }
        }

        val allToolNames = registry.getActiveTools().keys
        val allParamNames = registry.getActiveTools().values
            .flatMap { it.parameters.properties.keys }
            .toSet()
        log.info("[Agent] Creating brain with model: $modelId at $sgUrl (tools=${allToolNames.size}, params=${allParamNames.size})")

        return OpenAiCompatBrain(
            sourcegraphUrl = sgUrl,
            tokenProvider = tokenProvider,
            model = modelId,
            toolNameSet = allToolNames,
            paramNameSet = allParamNames
        )
    }

    // ── Manual Compaction ──────────────────────────────────────────────────

    /**
     * Manually compact the conversation context (user-triggered via /compact).
     * Creates a temporary brain for LLM summarization if needed (Stage 3 only).
     *
     * @return pair of (tokensBefore, tokensAfter), or null if utilization is too low to compact
     */
    suspend fun compactContext(contextManager: ContextManager): Pair<Int, Int>? {
        val utilization = contextManager.utilizationPercent()
        if (utilization <= 70.0) return null // Matches ContextManager.compact() internal threshold

        val tokensBefore = contextManager.tokenEstimate()
        val brain = createBrain()
        contextManager.compact(brain, hookManager)
        val tokensAfter = contextManager.tokenEstimate()
        return tokensBefore to tokensAfter
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
        safeRegisterCore { EnablePlanModeTool() }
        safeRegisterCore { UseSkillTool() }
        safeRegisterCore { NewTaskTool() }
        safeRegisterCore { RenderArtifactTool() }

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

        // Sub-agent delegation tool — progress callback wired at task level
        safeRegisterCore { SpawnAgentTool(
            brainProvider = { createBrain() },
            toolRegistry = registry,
            project = project,
            configLoader = AgentConfigLoader.getInstance()
        ) }

        // ── Deferred tools (loaded via tool_search) ──────────────────────

        // Code Intelligence — PSI-based semantic analysis
        safeRegisterDeferred("Code Intelligence") { FindImplementationsTool() }
        safeRegisterDeferred("Code Intelligence") { FileStructureTool() }
        safeRegisterDeferred("Code Intelligence") { TypeHierarchyTool() }
        safeRegisterDeferred("Code Intelligence") { CallHierarchyTool() }
        safeRegisterDeferred("Code Intelligence") { TypeInferenceTool() }
        safeRegisterDeferred("Code Intelligence") { DataFlowAnalysisTool() }
        safeRegisterDeferred("Code Intelligence") { GetMethodBodyTool() }
        safeRegisterDeferred("Code Intelligence") { GetAnnotationsTool() }
        safeRegisterDeferred("Code Intelligence") { TestFinderTool() }
        safeRegisterDeferred("Code Intelligence") { StructuralSearchTool() }
        safeRegisterDeferred("Code Intelligence") { ReadWriteAccessTool() }

        // Code Quality — formatting, refactoring, inspections
        safeRegisterDeferred("Code Quality") { FormatCodeTool() }
        safeRegisterDeferred("Code Quality") { OptimizeImportsTool() }
        safeRegisterDeferred("Code Quality") { RefactorRenameTool() }
        safeRegisterDeferred("Code Quality") { RunInspectionsTool() }
        safeRegisterDeferred("Code Quality") { ProblemViewTool() }
        safeRegisterDeferred("Code Quality") { ListQuickFixesTool() }

        // Git — history, branches, blame, shelve
        safeRegisterDeferred("Git") { GitBlameTool() }
        safeRegisterDeferred("Git") { GitBranchesTool() }
        safeRegisterDeferred("Git") { GitShowCommitTool() }
        safeRegisterDeferred("Git") { GitShowFileTool() }
        safeRegisterDeferred("Git") { GitStashListTool() }
        safeRegisterDeferred("Git") { GitFileHistoryTool() }
        safeRegisterDeferred("Git") { GitMergeBaseTool() }
        safeRegisterDeferred("Git") { ChangelistShelveTool() }
        safeRegisterDeferred("Git") { GenerateExplanationTool() }

        // Build & Run — project build, run configs, coverage
        safeRegisterDeferred("Build & Run") { BuildTool() }
        safeRegisterDeferred("Build & Run") { SpringTool() }
        safeRegisterDeferred("Build & Run") { RuntimeExecTool() }
        safeRegisterDeferred("Build & Run") { RuntimeConfigTool() }
        safeRegisterDeferred("Build & Run") { CoverageTool() }

        // Database — queries, schema, connection profiles
        safeRegisterDeferred("Database") { DbListProfilesTool() }
        safeRegisterDeferred("Database") { DbListDatabasesTool() }
        safeRegisterDeferred("Database") { DbQueryTool() }
        safeRegisterDeferred("Database") { DbSchemaTool() }

        // Utilities
        safeRegisterDeferred("Utilities") { ProjectContextTool() }
        safeRegisterDeferred("Utilities") { CurrentTimeTool() }
        safeRegisterDeferred("Utilities") { KillProcessTool() }
        safeRegisterDeferred("Utilities") { SendStdinTool() }
        safeRegisterDeferred("Utilities") { AskUserInputTool() }

        // Debug tools (require AgentDebugController)
        registerDebugTools()

        // ── Memory tools (always available — 3-tier Letta pattern) ───────
        coreMemory?.let { cm ->
            safeRegisterCore { CoreMemoryReadTool(cm) }
            safeRegisterCore { CoreMemoryAppendTool(cm) }
            safeRegisterCore { CoreMemoryReplaceTool(cm) }
        }
        archivalMemory?.let { am ->
            safeRegisterCore { ArchivalMemoryInsertTool(am) }
            safeRegisterCore { ArchivalMemorySearchTool(am) }
        }
        conversationRecall?.let { cr ->
            safeRegisterCore { ConversationSearchTool(cr) }
        }
        if (::agentDir.isInitialized) {
            safeRegisterCore { SaveMemoryTool(agentDir) }
        }

        // ── Conditional integration tools ────────────────────────────────
        // Only registered when the service URL is configured in ConnectionSettings
        registerConditionalIntegrationTools()

        log.info("[Agent] Registered ${registry.coreCount()} core + ${registry.deferredCount()} deferred tools")
    }

    /**
     * Register integration tools conditionally — only when their service URL
     * is configured. Prevents the LLM from seeing tools it can never use.
     */
    private fun registerConditionalIntegrationTools() {
        val connections = ConnectionSettings.getInstance()

        if (connections.state.jiraUrl.isNotBlank()) {
            safeRegisterDeferred("Integration") { JiraTool() }
        }
        if (connections.state.bambooUrl.isNotBlank()) {
            safeRegisterDeferred("Integration") { BambooBuildsTool() }
            safeRegisterDeferred("Integration") { BambooPlansTool() }
        }
        if (connections.state.sonarUrl.isNotBlank()) {
            safeRegisterDeferred("Integration") { SonarTool() }
        }
        if (connections.state.bitbucketUrl.isNotBlank()) {
            safeRegisterDeferred("Integration") { BitbucketPrTool() }
            safeRegisterDeferred("Integration") { BitbucketRepoTool() }
            safeRegisterDeferred("Integration") { BitbucketReviewTool() }
        }
    }

    private fun registerDebugTools() {
        try {
            val controller = AgentDebugController(project)
            debugController = controller
            registry.registerDeferred(DebugStepTool(controller), "Debug")
            registry.registerDeferred(DebugInspectTool(controller), "Debug")
            registry.registerDeferred(DebugBreakpointsTool(controller), "Debug")
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

    private inline fun safeRegisterDeferred(category: String = "Other", factory: () -> AgentTool) {
        try {
            registry.registerDeferred(factory(), category)
        } catch (e: Exception) {
            log.warn("AgentService: failed to register deferred tool: ${e.message}")
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
        onComplete: (LoopResult) -> Unit = {},
        /**
         * Callback fired when the LLM presents a plan via plan_mode_respond.
         * Used by the UI to render the plan card. Does NOT exit the loop.
         */
        onPlanResponse: ((planText: String, needsMoreExploration: Boolean, planSteps: List<String>) -> Unit)? = null,
        /**
         * Callback fired when the LLM toggles plan mode via enable_plan_mode tool.
         * Used by the UI to update the plan mode button and rebuild tool definitions.
         */
        onPlanModeToggled: ((Boolean) -> Unit)? = null,
        /**
         * Channel for feeding user input into a running loop.
         * Used in plan mode: after plan presentation, the loop waits on this channel
         * for the user to send a message, add comments, or approve.
         */
        userInputChannel: Channel<String>? = null,
        /**
         * Optional approval gate for write tool executions.
         * When set, the loop suspends before write tools and waits for user approval.
         * When null (e.g. in sub-agents or handoff sessions), write tools execute without approval.
         */
        approvalGate: (suspend (toolName: String, args: String, riskLevel: String) -> com.workflow.orchestrator.agent.loop.ApprovalResult)? = null,
        /**
         * Optional callback fired after a write checkpoint is saved.
         * Used by the UI to update the checkpoint timeline display.
         *
         * @param sessionId the session the checkpoint belongs to
         */
        onCheckpointSaved: ((sessionId: String) -> Unit)? = null,
        /**
         * Optional callback for sub-agent progress updates.
         * Streams sub-agent status (running/completed/failed) and tool calls to the dashboard.
         */
        onSubagentProgress: ((agentId: String, update: SubagentProgressUpdate) -> Unit)? = null,
        /**
         * Optional callback fired after each API call with cumulative token counts.
         * Used by the UI to show real-time token budget utilization.
         */
        onTokenUpdate: ((inputTokens: Int, outputTokens: Int) -> Unit)? = null,
        /**
         * Optional callback for real-time debug log entries.
         * Pushed to the JCEF debug panel when showDebugLog setting is enabled.
         */
        onDebugLog: ((level: String, event: String, detail: String, meta: Map<String, Any?>?) -> Unit)? = null,
        /**
         * Callback fired when the loop retries a failed API call.
         * Always fires — retries are user-visible events.
         */
        onRetry: ((attempt: Int, maxAttempts: Int, reason: String, delayMs: Long) -> Unit)? = null,
        /**
         * Callback fired when the loop switches to a different model via fallback.
         * Used by the UI to update the model chip and show a status message.
         */
        onModelSwitch: ((fromModel: String, toModel: String, reason: String) -> Unit)? = null,
        /**
         * Optional callback fired synchronously before the agent loop coroutine starts.
         * Provides the session ID so callers can track the session early (e.g. before
         * the first checkpoint fires). Called on the thread that invokes executeTask.
         */
        onSessionStarted: ((sessionId: String) -> Unit)? = null,
        /**
         * Thread-safe queue for mid-turn steering messages.
         * When provided, the loop drains this at the start of each iteration and
         * injects queued user messages into the conversation context.
         */
        steeringQueue: java.util.concurrent.ConcurrentLinkedQueue<SteeringMessage>? = null,
        /**
         * Callback fired after steering messages are drained and injected.
         * The UI promotes queued messages to regular chat messages.
         */
        onSteeringDrained: ((drainedIds: List<String>) -> Unit)? = null
    ): Job {
        val sid = sessionId ?: UUID.randomUUID().toString()
        var session = Session(
            id = sid,
            title = task.take(100),
            status = SessionStatus.ACTIVE
        )
        sessionStore.save(session)
        onSessionStarted?.invoke(sid)

        val sessionMetrics = SessionMetrics()
        val sessionStartTime = System.currentTimeMillis()

        val job = scope.launch {
            var brainRef: LlmBrain? = null
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
                brainRef = brain
                log.info("[Agent] Task started: sessionId=$sid, model=${brain.modelId}")

                // Wire API debug dumps — save request/response JSON per session
                val basePath = project.basePath ?: System.getProperty("user.home")
                val sessionDebugDir = java.io.File(
                    ProjectIdentifier.agentDir(basePath),
                    "sessions/$sid"
                )
                // Session-scoped API call counter — shared across the initial brain AND any
                // brains spawned by the brainFactory below (recycle, model fallback). Keeps
                // `api-debug/call-NNN-*.txt` filenames monotonic across the entire task,
                // even if the brain is replaced mid-loop. Owned by this executeTask scope.
                val sharedApiCounter = java.util.concurrent.atomic.AtomicInteger(0)
                if (brain is OpenAiCompatBrain) {
                    brain.setApiDebugDir(sessionDebugDir)
                    brain.setSharedApiCallCounter(sharedApiCounter)
                    log.debug("[Agent] API debug dir: ${sessionDebugDir.absolutePath}/api-debug/")
                }

                val agentSettings = AgentSettings.getInstance(project)

                // Network error strategy
                val strategy = agentSettings.state.networkErrorStrategy ?: "none"

                // Build the fallback chain ONCE — used by both ModelFallbackManager (when
                // enabled) AND L2 tier escalation (always, when chain has >=2 entries).
                // Order: Opus thinking → Opus → Sonnet thinking → Sonnet (no Haiku).
                val cachedFallbackChain = run {
                    val cachedModels = ModelCache.getCached()
                    val chain = ModelCache.buildFallbackChain(cachedModels)
                    if (chain.size > 1) {
                        log.info("[Agent] Fallback chain available: ${chain.map { it.substringAfterLast("::") }}")
                        chain
                    } else {
                        log.info("[Agent] Fallback chain has ≤1 model — L2 tier escalation disabled")
                        null
                    }
                }

                val fallbackManager = if (strategy == "model_fallback" && cachedFallbackChain != null) {
                    log.info("[Agent] Model fallback enabled (L1 takes priority over L2)")
                    ModelFallbackManager(cachedFallbackChain)
                } else null
                val compactOnTimeoutExhaustion = strategy == "context_compaction"

                // Counter for recycle marker filenames (recycle-001.txt, recycle-002.txt, ...).
                // Increments every time the factory is invoked, regardless of reason
                // (model fallback OR same-tier recycle).
                val recycleMarkerCounter = java.util.concurrent.atomic.AtomicInteger(0)

                // brainFactory is now ALWAYS built — even when model fallback is disabled.
                // Used by AgentLoop for both:
                //   - Model fallback (when fallbackManager != null and an alternate tier is available)
                //   - Same-tier brain recycling on stream/timeout errors (always available now,
                //     fixes broken socket / dead ConnectionPool / stale activeCall ref)
                val fbConnections = ConnectionSettings.getInstance()
                val fbUrl = fbConnections.state.sourcegraphUrl.trimEnd('/')
                val fbCredentialStore = CredentialStore()
                val fbTokenProvider = { fbCredentialStore.getToken(ServiceType.SOURCEGRAPH) }
                val fbToolNames = registry.getActiveTools().keys
                val fbParamNames = registry.getActiveTools().values
                    .flatMap { it.parameters.properties.keys }
                    .toSet()
                val brainFactory: suspend (String, String?) -> LlmBrain = { modelId: String, reason: String? ->
                    val newBrain = OpenAiCompatBrain(
                        sourcegraphUrl = fbUrl,
                        tokenProvider = fbTokenProvider,
                        model = modelId,
                        toolNameSet = fbToolNames,
                        paramNameSet = fbParamNames
                    ).also { b ->
                        b.setApiDebugDir(sessionDebugDir)
                        // Inherit the shared API call counter so call-NNN-*.txt filenames
                        // stay monotonic across the new brain's calls.
                        b.setSharedApiCallCounter(sharedApiCounter)
                    }
                    // Track the currently-live brain so the finally block at task end clears
                    // the api-debug dir on the right instance (a recycled brain, not a stale
                    // discarded one). Cancel propagation doesn't use brainRef — it goes
                    // through `task.loop.cancel()` + `task.job.cancel()` which propagate
                    // coroutine cancellation through brain.chatStream()'s suspension points
                    // regardless of which brain instance is currently in use.
                    brainRef = newBrain

                    // Write a recycle marker file into api-debug/ so the directory listing
                    // tells the recovery story: "after call NNN, the brain was recycled
                    // because <reason>; the next call comes from a fresh OkHttpClient".
                    // The api-debug/ directory is already created on first access by
                    // SourcegraphChatClient.apiDebugDir getter when it dumps a call, so we
                    // don't need to mkdirs() again here.
                    try {
                        val recycleIdx = recycleMarkerCounter.incrementAndGet()
                        val lastCallNum = sharedApiCounter.get()
                        val markerFile = java.io.File(
                            sessionDebugDir,
                            "api-debug/recycle-${String.format("%03d", recycleIdx)}.txt"
                        )
                        markerFile.writeText(buildString {
                            appendLine("=== Brain Recycle #$recycleIdx === ${java.time.Instant.now()} ===")
                            appendLine("Model:        $modelId")
                            appendLine("After call #: $lastCallNum")
                            appendLine("Reason:       ${reason ?: "(unspecified)"}")
                            appendLine()
                            appendLine("The previous OpenAiCompatBrain (and its OkHttpClient + ConnectionPool +")
                            appendLine("activeCall ref) was discarded. The fresh brain shares the session's API")
                            appendLine("call counter, so the next call dump will be call-${String.format("%03d", lastCallNum + 1)}-request.txt")
                        })
                        log.info("[Agent] Brain recycled (#$recycleIdx) — model=$modelId, after call #$lastCallNum, reason: ${reason?.take(120)}")
                    } catch (e: Exception) {
                        log.debug("[Agent] Failed to write recycle marker: ${e.message}")
                    }

                    newBrain
                }

                // Build context manager
                val ctx = contextManager ?: ContextManager(
                    maxInputTokens = agentSettings.state.maxInputTokens
                )

                // I7: Always re-set system prompt (plan mode may have changed between turns)
                val projectName = project.name
                val projectPath = project.basePath ?: ""

                // Load project instructions (CLAUDE.md) and discover skills
                // Port of Cline's skill discovery: discoverSkills + getAvailableSkills
                val projectInstructions = InstructionLoader.loadProjectInstructions(projectPath)
                val allSkills = InstructionLoader.discoverSkills(projectPath)
                val availableSkills = InstructionLoader.getAvailableSkills(allSkills)
                    .ifEmpty { null }

                // Reset active deferred tools for new sessions (not resumed ones)
                if (contextManager == null) {
                    registry.resetActiveDeferred()
                }

                // Build deferred catalog for system prompt injection (grouped by category)
                val deferredCatalog = registry.getDeferredCatalogGrouped()

                // Build system prompt — XML tool definitions added dynamically below
                val systemPromptBuilder = { toolDefsMarkdown: String? ->
                    SystemPrompt.build(
                        projectName = projectName,
                        projectPath = projectPath,
                        planModeEnabled = planModeActive.get(),
                        additionalContext = projectInstructions,
                        availableSkills = availableSkills,
                        activeSkillContent = ctx.getActiveSkill(),
                        taskProgress = ctx.getTaskProgress(),
                        deferredToolCatalog = deferredCatalog,
                        coreMemoryXml = coreMemory?.compile(),
                        toolDefinitionsMarkdown = toolDefsMarkdown
                    )
                }
                // Set initial system prompt (XML defs added on first toolDefinitionProvider call)
                ctx.setSystemPrompt(systemPromptBuilder(null))

                // Build tool definitions dynamically — called on each loop iteration.
                // Plan mode: remove write tools + act_mode_respond + enable_plan_mode, keep plan_mode_respond.
                // Act mode: remove plan_mode_respond, keep act_mode_respond + write tools + enable_plan_mode.
                // Re-reads planModeActive on each call so enable_plan_mode tool takes effect mid-session.
                //
                // Also rebuilds the system prompt with updated tool definitions —
                // critical because the LLM only sees tools via the system prompt
                // (tools: null in API request, XML mode is always on).
                val hasSkills = availableSkills != null
                var lastXmlToolDefsHash = 0
                val toolDefinitionProvider: () -> List<com.workflow.orchestrator.core.ai.dto.ToolDefinition> = {
                    val isPlanMode = planModeActive.get()
                    val defs = registry.getActiveTools().values
                        .filter { tool ->
                            // Port of Cline's contextRequirements: omit use_skill when no skills available
                            if (tool.name == "use_skill" && !hasSkills) return@filter false
                            if (isPlanMode) {
                                tool.name !in writeToolNames && tool.name != "act_mode_respond" && tool.name != "enable_plan_mode"
                            } else {
                                tool.name != "plan_mode_respond"
                            }
                        }
                        .map { AgentTool.injectTaskProgress(it.toToolDefinition()) }

                    // Update system prompt when tool set changes (plan mode switch, deferred tool load)
                    val defsHash = defs.map { it.function.name }.hashCode()
                    if (defsHash != lastXmlToolDefsHash) {
                        lastXmlToolDefsHash = defsHash
                        val markdown = com.workflow.orchestrator.core.ai.ToolPromptBuilder.build(defs)
                        ctx.setSystemPrompt(systemPromptBuilder(markdown))
                    }

                    defs
                }

                // Wire sub-agent progress callback and settings for this task execution
                val spawnAgentTool = registry.get("agent") as? com.workflow.orchestrator.agent.tools.builtin.SpawnAgentTool
                if (spawnAgentTool != null) {
                    spawnAgentTool.contextBudget = agentSettings.state.maxInputTokens
                    spawnAgentTool.maxOutputTokens = agentSettings.state.maxOutputTokens
                    spawnAgentTool.sessionDebugDir = sessionDebugDir
                    spawnAgentTool.onSubagentProgress = if (onSubagentProgress != null) {
                        { agentId, update -> onSubagentProgress(agentId, update) }
                    } else null
                }

                // Initial tool definitions (also used as fallback in AgentLoop)
                val toolDefs = toolDefinitionProvider()
                // Tool map for execution — use registry.get() to resolve any tool including deferred
                val tools = registry.getActiveTools()

                // Log session start with actual tool count
                fileLogger.logSessionStart(sid, task, toolDefs.size)

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
                    maxOutputTokens = agentSettings.state.maxOutputTokens,
                    toolDefinitionProvider = toolDefinitionProvider,
                    toolResolver = { name -> registry.get(name) },
                    hookManager = if (hookManager.hasAnyHooks()) hookManager else null,
                    sessionId = sid,
                    onTokenUpdate = onTokenUpdate,
                    onPlanResponse = onPlanResponse,
                    onPlanModeToggle = { enabled ->
                        planModeActive.set(enabled)
                        onPlanModeToggled?.invoke(enabled)
                    },
                    userInputChannel = userInputChannel,
                    approvalGate = approvalGate,
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
                            log.debug("[Agent] Checkpoint saved: $sid/$checkpointId")
                            // Notify UI to update checkpoint timeline
                            onCheckpointSaved?.invoke(sid)
                        } catch (e: Exception) {
                            log.warn("AgentService: write checkpoint save failed (non-fatal)", e)
                        }
                    },
                    onDebugLog = onDebugLog,
                    onRetry = onRetry,
                    fileLogger = fileLogger,
                    sessionMetrics = sessionMetrics,
                    environmentDetailsProvider = {
                        val pluginSettings = PluginSettings.getInstance(project)
                        EnvironmentDetailsBuilder.build(
                            project = project,
                            planModeEnabled = planModeActive.get(),
                            contextManager = ctx,
                            activeTicketId = pluginSettings.state.activeTicketId,
                            activeTicketSummary = pluginSettings.state.activeTicketSummary
                        )
                    },
                    steeringQueue = steeringQueue,
                    onSteeringDrained = onSteeringDrained,
                    fallbackManager = fallbackManager,
                    brainFactory = brainFactory,
                    cachedFallbackChain = cachedFallbackChain,
                    onModelSwitch = onModelSwitch,
                    compactOnTimeoutExhaustion = compactOnTimeoutExhaustion,
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
                            log.debug("[Agent] Session saved: $sid")
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
                    is LoopResult.SessionHandoff -> result.tokensUsed
                }
                val inputTokens = when (result) {
                    is LoopResult.Completed -> result.inputTokens
                    is LoopResult.Failed -> result.inputTokens
                    is LoopResult.Cancelled -> result.inputTokens
                    is LoopResult.SessionHandoff -> result.inputTokens
                }
                val outputTokens = when (result) {
                    is LoopResult.Completed -> result.outputTokens
                    is LoopResult.Failed -> result.outputTokens
                    is LoopResult.Cancelled -> result.outputTokens
                    is LoopResult.SessionHandoff -> result.outputTokens
                }
                session = session.copy(
                    status = when (result) {
                        is LoopResult.Completed -> SessionStatus.COMPLETED
                        is LoopResult.Failed -> SessionStatus.FAILED
                        is LoopResult.Cancelled -> SessionStatus.CANCELLED
                        is LoopResult.SessionHandoff -> SessionStatus.COMPLETED
                    },
                    lastMessageAt = System.currentTimeMillis(),
                    totalTokens = tokensUsed,
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    messageCount = ctx.messageCount(),
                    metrics = sessionMetrics.snapshot()
                )
                sessionStore.save(session)
                log.info("[Agent] Task ended: status=${session.status}, iterations=${ctx.messageCount()}, tokens=$tokensUsed")

                // Log session end to structured file logger
                val sessionDurationMs = System.currentTimeMillis() - sessionStartTime
                val iterations = when (result) {
                    is LoopResult.Completed -> result.iterations
                    is LoopResult.Failed -> result.iterations
                    is LoopResult.Cancelled -> result.iterations
                    is LoopResult.SessionHandoff -> result.iterations
                }
                fileLogger.logSessionEnd(
                    sessionId = sid,
                    iterations = iterations,
                    totalTokens = tokensUsed,
                    durationMs = sessionDurationMs,
                    error = if (result is LoopResult.Failed) result.error else null
                )

                // TASK_COMPLETE hook — fire-and-forget, observation-only (non-cancellable).
                // Fires when a task completes successfully. Matching Cline's 8th hook type.
                if (result is LoopResult.Completed && hookManager.hasHooks(HookType.TASK_COMPLETE)) {
                    try {
                        hookManager.dispatch(
                            HookEvent(
                                type = HookType.TASK_COMPLETE,
                                data = mapOf(
                                    "sessionId" to sid,
                                    "summary" to result.summary,
                                    "iterations" to result.iterations,
                                    "tokensUsed" to result.tokensUsed
                                )
                            )
                        )
                    } catch (e: Exception) {
                        log.warn("AgentService: TASK_COMPLETE hook failed (non-fatal)", e)
                    }
                }

                onComplete(result)
            } catch (e: CancellationException) {
                session = session.copy(
                    status = SessionStatus.CANCELLED,
                    lastMessageAt = System.currentTimeMillis()
                )
                sessionStore.save(session)
                fileLogger.logSessionEnd(sid, 0, 0, System.currentTimeMillis() - sessionStartTime)
                onComplete(LoopResult.Cancelled(iterations = 0))
            } catch (e: Exception) {
                log.error("AgentService: task execution failed", e)
                session = session.copy(
                    status = SessionStatus.FAILED,
                    lastMessageAt = System.currentTimeMillis()
                )
                sessionStore.save(session)
                fileLogger.logSessionEnd(sid, 0, 0, System.currentTimeMillis() - sessionStartTime, error = e.message)
                onComplete(LoopResult.Failed(error = e.message ?: "Unknown error"))
            } finally {
                activeTask.set(null)
                // Clear API debug dir so the brain doesn't dump after task ends
                (brainRef as? OpenAiCompatBrain)?.setApiDebugDir(null)
                // Clear per-task sub-agent progress callback
                (registry.get("agent") as? com.workflow.orchestrator.agent.tools.builtin.SpawnAgentTool)?.onSubagentProgress = null
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
            val systemPrompt = SystemPrompt.build(
                projectName = project.name,
                projectPath = project.basePath ?: "",
                planModeEnabled = session.planModeEnabled
            )
            ctx.setSystemPrompt(systemPrompt)
        }

        // Restore saved messages into context manager
        ctx.restoreMessages(savedMessages)

        // Restore plan mode state from session
        planModeActive.set(session.planModeEnabled)

        log.info("[Agent] Resuming session: $sessionId (${savedMessages.size} messages)")

        // TASK_RESUME hook (ported from Cline's TaskResume hook)
        // Cancellable: can prevent session resumption.
        // Cline: "Executes when a task is resumed after being interrupted."
        if (hookManager.hasHooks(HookType.TASK_RESUME)) {
            val hookResult = runBlocking {
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

    /**
     * Update the title of an existing session (e.g. after Haiku generates a descriptive title).
     */
    fun updateSessionTitle(sessionId: String, title: String) {
        val session = sessionStore.load(sessionId) ?: return
        sessionStore.save(session.copy(title = title))
    }

    /**
     * Get files modified between a checkpoint and the current session state.
     * Used by the UI to highlight affected files after a rollback.
     *
     * Extracts file paths from tool calls (edit_file, create_file) in messages
     * that exist after the checkpoint but before the current state.
     */
    fun getFilesModifiedSinceCheckpoint(sessionId: String, checkpointId: String): List<String> {
        return try {
            val checkpointMessages = sessionStore.loadCheckpoint(sessionId, checkpointId) ?: return emptyList()
            val currentMessages = sessionStore.loadMessages(sessionId)
            // Messages after the checkpoint = those beyond checkpointMessages.size
            val afterCheckpoint = currentMessages.drop(checkpointMessages.size)
            // Extract file paths from tool calls in assistant messages
            val files = mutableSetOf<String>()
            for (msg in afterCheckpoint) {
                msg.toolCalls?.forEach { call ->
                    if (call.function.name in AgentLoop.WRITE_TOOLS) {
                        try {
                            val args = kotlinx.serialization.json.Json.parseToJsonElement(call.function.arguments)
                            val path = (args as? kotlinx.serialization.json.JsonObject)
                                ?.get("path")
                                ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
                            if (path != null) files.add(path)
                        } catch (_: Exception) { }
                    }
                }
            }
            files.toList()
        } catch (e: Exception) {
            log.debug("Failed to get files modified since checkpoint: ${e.message}")
            emptyList()
        }
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

    // ── New Chat Reset ──────────────────────────────────────────────────────

    /**
     * Reset all service-level state for a new chat session.
     * Called by AgentController.newChat() to ensure no state leaks between conversations.
     */
    fun resetForNewChat() {
        cancelCurrentTask()
        planModeActive.set(false)
        registry.resetActiveDeferred()
        ProcessRegistry.killAll()
        activeTask.set(null)
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
