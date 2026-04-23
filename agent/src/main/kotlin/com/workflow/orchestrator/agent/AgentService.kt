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
import com.workflow.orchestrator.agent.ide.Framework
import com.workflow.orchestrator.agent.ide.IdeContext
import com.workflow.orchestrator.agent.ide.IdeContextDetector
import com.workflow.orchestrator.agent.ide.JavaKotlinProvider
import com.workflow.orchestrator.agent.ide.LanguageProviderRegistry
import com.workflow.orchestrator.agent.ide.PythonPsiHelper
import com.workflow.orchestrator.agent.ide.PythonProvider
import com.workflow.orchestrator.agent.ide.ToolRegistrationFilter
import com.workflow.orchestrator.agent.loop.AgentLoop
import com.workflow.orchestrator.agent.loop.ContextManager
import com.workflow.orchestrator.agent.loop.FailureReason
import com.workflow.orchestrator.agent.loop.LoopResult
import com.workflow.orchestrator.agent.loop.SteeringMessage
import com.workflow.orchestrator.agent.loop.ToolCallProgress
import com.workflow.orchestrator.agent.prompt.EnvironmentDetailsBuilder
import com.workflow.orchestrator.agent.prompt.InstructionLoader
import com.workflow.orchestrator.agent.prompt.SystemPrompt
import com.workflow.orchestrator.core.settings.PluginSettings
import com.workflow.orchestrator.agent.session.AtomicFileWriter
import com.workflow.orchestrator.agent.session.TaskStore
import com.workflow.orchestrator.agent.session.Session
import com.workflow.orchestrator.agent.session.SessionStatus
import com.workflow.orchestrator.agent.session.HistoryItem
import com.workflow.orchestrator.agent.session.MessageStateHandler
import com.workflow.orchestrator.agent.session.UiMessage
import com.workflow.orchestrator.agent.session.UiMessageType
import com.workflow.orchestrator.agent.session.UiAsk
import com.workflow.orchestrator.agent.session.UiSay
import com.workflow.orchestrator.agent.session.ApiMessage
import com.workflow.orchestrator.agent.session.ApiRole
import com.workflow.orchestrator.agent.session.ContentBlock
import com.workflow.orchestrator.agent.session.SessionLock
import com.workflow.orchestrator.agent.session.ResumeHelper
import com.workflow.orchestrator.agent.session.toChatMessage
import com.workflow.orchestrator.agent.session.toApiMessage
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolOutputSpiller
import com.workflow.orchestrator.agent.tools.ToolRegistry
import com.workflow.orchestrator.agent.memory.ArchivalMemory
import com.workflow.orchestrator.agent.memory.ConversationRecall
import com.workflow.orchestrator.agent.memory.CoreMemory
import com.workflow.orchestrator.agent.memory.auto.AutoMemoryManager
import com.workflow.orchestrator.agent.observability.AgentFileLogger
import com.workflow.orchestrator.agent.observability.SessionMetrics
import com.workflow.orchestrator.agent.tools.builtin.*
import com.workflow.orchestrator.agent.tools.database.*
import com.workflow.orchestrator.agent.tools.debug.AgentDebugController
import com.workflow.orchestrator.agent.tools.debug.DebugBreakpointsTool
import com.workflow.orchestrator.agent.tools.debug.DebugInspectTool
import com.workflow.orchestrator.agent.tools.debug.DebugInvocation
import com.workflow.orchestrator.agent.tools.debug.DebugStepTool
import com.workflow.orchestrator.agent.tools.framework.*
import com.workflow.orchestrator.agent.tools.framework.endpoints.EndpointsTool
import com.workflow.orchestrator.agent.tools.ide.*
import com.workflow.orchestrator.agent.tools.integration.*
import com.workflow.orchestrator.agent.tools.memory.*
import com.workflow.orchestrator.agent.tools.process.ProcessRegistry
import com.workflow.orchestrator.agent.tools.process.ShellResolver
import com.workflow.orchestrator.agent.tools.subagent.AgentConfigLoader
import com.workflow.orchestrator.agent.tools.subagent.SubagentProgressUpdate
import com.workflow.orchestrator.agent.tools.psi.*
import com.workflow.orchestrator.agent.tools.runtime.*
import com.workflow.orchestrator.agent.tools.vcs.ChangelistShelveTool
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * Central agent service — wires the AgentLoop, ToolRegistry, ContextManager,
 * MessageStateHandler, and LLM brain together. Exposes [executeTask] for the UI layer.
 *
 * IntelliJ project-level service: one instance per open project.
 */
@Service(Service.Level.PROJECT)
class AgentService(private val project: Project) : Disposable {

    private val log = Logger.getInstance(AgentService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val registry = ToolRegistry()

    /** Atomic reference tracking both the loop and job together to avoid race conditions. */
    private data class ActiveTask(val sessionId: String, val loop: AgentLoop, val job: Job)
    private val activeTask = AtomicReference<ActiveTask?>(null)

    /**
     * Test-only capture hooks keyed by sessionId — when set, background completion
     * messages for that session are delivered to the callback instead of being routed
     * to the live AgentLoop / persistence. See [setSteeringCapturerForTest].
     *
     * Declared here (not in a test-only subclass) because the listener path lives inside
     * [onBackgroundCompletion], which must be exercised end-to-end from the real
     * [com.workflow.orchestrator.agent.tools.background.BackgroundPool] instance.
     */
    private val steeringCapturersForTest = java.util.concurrent.ConcurrentHashMap<String, (String) -> Unit>()

    /**
     * Install a capture callback for background-completion steering messages
     * belonging to [sessionId]. Test-only hook — production code should never call it.
     * When set, the callback receives the formatted system message and the
     * production routing (activeLoopForSession / persistForLaterResume) is skipped.
     */
    @org.jetbrains.annotations.TestOnly
    fun setSteeringCapturerForTest(sessionId: String, capture: (String) -> Unit) {
        steeringCapturersForTest[sessionId] = capture
    }

    /**
     * Task 6.1 — per-session auto-wake guardrail state (enabled toggle, per-session
     * cap, cooldown). Decision is made against [AgentSettings] from
     * [onBackgroundCompletion] when the loop is idle. Guardrail logic lives in a
     * separate helper so it can be unit-tested without instantiating AgentService.
     */
    private val autoWakeGuards = com.workflow.orchestrator.agent.tools.background.AutoWakeGuardState()

    /**
     * Task 6.1 — optional hook the UI layer (AgentController) registers to carry out
     * the actual auto-wake. The agent service cannot drive the full resume pipeline
     * on its own because all the interactive callbacks (approval gate, plan, token
     * updates, etc.) live at the controller layer. When no listener is registered
     * (e.g. headless tests, pre-controller-init), we fall back to plain persistence.
     *
     * Contract: `(sessionId, syntheticUserMessage) -> Unit`. Called on the EDT.
     */
    @Volatile
    private var autoWakeListener: ((String, String) -> Unit)? = null

    /**
     * Register the auto-wake listener. Call from [AgentController.init] (or equivalent).
     * Passing `null` clears the hook. Thread-safe: listener is a volatile field.
     */
    fun setAutoWakeListener(listener: ((String, String) -> Unit)?) {
        this.autoWakeListener = listener
    }

    /** Dedicated structured agent log file — one per project, lives for plugin lifetime. */
    private val fileLogger: AgentFileLogger by lazy {
        AgentFileLogger(logDir = ProjectIdentifier.logsDir(project.basePath ?: ""))
    }

    lateinit var ideContext: IdeContext
        private set

    /**
     * Returns the current [IdeContext], or null if [registerAllTools] has not yet run.
     * Used by [com.workflow.orchestrator.agent.tools.builtin.SpawnAgentTool] to thread
     * IDE context into [com.workflow.orchestrator.agent.tools.subagent.SubagentRunner].
     */
    fun getIdeContextOrNull(): IdeContext? = if (::ideContext.isInitialized) ideContext else null

    /** Shells available for run_command — populated by registerAllTools() before any consumer reads it. */
    private var allowedShells: List<String> = emptyList()

    lateinit var providerRegistry: LanguageProviderRegistry
        private set

    private var debugController: AgentDebugController? = null
    private var coreMemory: CoreMemory? = null
    private var archivalMemory: ArchivalMemory? = null
    private var conversationRecall: ConversationRecall? = null
    private var autoMemoryManager: AutoMemoryManager? = null
    private val autoMemoryInitMutex = Mutex()
    private lateinit var agentDir: java.io.File
    private val failedRegistrations = mutableListOf<String>()

    /**
     * Session-scoped output spiller. Hoisted from the [executeTask] local val so that
     * [AgentTool.spillOrFormat] can resolve it via [service] on [AgentService] without
     * coupling individual tools to the execute-task closure.
     *
     * Lifecycle: set when a session starts in [executeTask], cleared in [resetForNewChat]
     * so each new chat gets a fresh spiller pointing at the correct session directory.
     */
    @Volatile private var _outputSpiller: ToolOutputSpiller? = null

    /** Public read-only view of the session-scoped spiller; null before the first task starts. */
    val outputSpiller: ToolOutputSpiller? get() = _outputSpiller

    /**
     * Hook manager — loaded from .agent-hooks.json in project root.
     * Ported from Cline's hook system: provides lifecycle extensibility points
     * (TaskStart, PreToolUse, PostToolUse, etc.) via shell command hooks.
     *
     * @see <a href="https://github.com/cline/cline/blob/main/src/core/hooks/hook-factory.ts">Cline HookFactory</a>
     */
    val hookManager: HookManager

    /** Tool names that are blocked in plan mode (write/mutate tools).
     *  Single source of truth is AgentLoop.WRITE_TOOLS — this is an alias so callers
     *  in this class keep readable local references without duplicating the set. */
    private val writeToolNames get() = AgentLoop.WRITE_TOOLS

    /**
     * Session-scoped Disposable scope for per-run IDE state (IDE tests, coverage runs,
     * debug sessions, etc.). Every call to [newRunInvocation] hands out a
     * [RunInvocation] whose parent is the current session Disposable, so a
     * "new chat" click (which routes through [resetForNewChat]) tears down any
     * outstanding RunInvocation transitively — listeners detached, processes
     * killed, RunContent descriptors removed.
     *
     * Phase 3 / Task 2.2 of the IDE-state-leak fix plan — see
     * `docs/plans/2026-04-17-phase3-ide-state-leak-fixes.md`. Tasks 2.3/2.4/2.5
     * will consume this factory from `JavaRuntimeExecTool.run_tests` and
     * `CoverageTool.run_with_coverage`.
     *
     * Extracted into [SessionDisposableHolder] (a thin pure helper) so the
     * per-session Disposable lifecycle can be unit-tested without instantiating
     * the full AgentService — which has a heavy `init` (memory system,
     * tool registration, hook loading) that's infeasible to mock.
     */
    private val sessionDisposableHolder: SessionDisposableHolder =
        SessionDisposableHolder(parent = this, diagnosticName = "agent-session")

    /**
     * Allocate a per-run disposal scope tied to the current chat session.
     *
     * The returned [RunInvocation] auto-cleans listeners, process handlers,
     * and descriptor teardown when disposed. Its parent is the session
     * Disposable, so a "new chat" click will cascade-dispose any outstanding
     * invocation automatically — no bookkeeping required in call sites.
     *
     * Typical usage:
     *
     * ```kotlin
     * val invocation = project.service<AgentService>().newRunInvocation("run-tests-$className")
     * try {
     *     invocation.attachListener(...)
     *     invocation.attachProcessListener(handler, listener)
     *     invocation.onDispose { removeRunContent(...) }
     *     // ... await result ...
     * } finally {
     *     Disposer.dispose(invocation)
     * }
     * ```
     */
    internal fun newRunInvocation(name: String): RunInvocation =
        sessionDisposableHolder.newRunInvocation(name)

    /**
     * Allocate a per-debug-session disposal scope tied to the current
     * chat session. Phase 5 / Task 4.3 — see
     * `docs/plans/2026-04-17-phase5-debug-tools-fixes.md`. Sibling of
     * [newRunInvocation] for `XDebugSession` listener lifecycle —
     * returns a [DebugInvocation] whose parent is the session
     * Disposable, so a "new chat" click cascade-disposes any
     * outstanding debug invocation automatically (listeners detached,
     * replay cache cleared, `onDispose` hooks fired). Task 4.2 will
     * convert `AgentDebugController.registerSession` to consume this
     * factory.
     *
     * Typical usage:
     *
     * ```kotlin
     * val invocation = project.service<AgentService>().newDebugInvocation("debug-$counter")
     * try {
     *     invocation.attachListener(xDebugSession, xDebugSessionListener)
     *     invocation.pauseFlow.emit(pauseEvent)
     *     // ... await pause / step / stop events ...
     * } finally {
     *     Disposer.dispose(invocation)
     * }
     * ```
     */
    internal fun newDebugInvocation(name: String): DebugInvocation =
        sessionDisposableHolder.newDebugInvocation(name)

    init {
        val basePath = project.basePath ?: System.getProperty("user.home")
        val agentDir = ProjectIdentifier.agentDir(basePath)
        this.agentDir = agentDir

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

        // Task 5.2 — Silent kill-all of background processes on project close.
        // Best-effort: any failure is logged, never thrown, so IDE shutdown is not blocked.
        com.intellij.openapi.project.ProjectManager.getInstance()
            .addProjectManagerListener(project, object : com.intellij.openapi.project.ProjectManagerListener {
                override fun projectClosing(closingProject: com.intellij.openapi.project.Project) {
                    if (closingProject == project) {
                        runCatching {
                            com.workflow.orchestrator.agent.tools.background.BackgroundPool
                                .getInstance(project).killAllForProject()
                        }.onFailure {
                            log.warn("[AgentService] killAllForProject failed at shutdown: ${it.message}", it)
                        }
                    }
                }
            })

        // Task 5.4 — Route BackgroundPool completion events into the active loop's
        // steering queue so the LLM learns about process exits at the next iteration
        // boundary. One completion event produces exactly one steering message
        // (no batching) so the LLM can react to each process exit individually.
        com.workflow.orchestrator.agent.tools.background.BackgroundPool.getInstance(project)
            .addCompletionListener { event -> onBackgroundCompletion(event) }
    }

    /**
     * Task 5.4 — Handle a [com.workflow.orchestrator.agent.tools.background.BackgroundCompletionEvent]
     * emitted by [com.workflow.orchestrator.agent.tools.background.BackgroundPool].
     *
     * Routing:
     * 1. If a test capturer is installed for the session, deliver the message there and return.
     * 2. If there is an active loop whose sessionId matches, enqueue a steering message into it.
     * 3. Otherwise persist the event so the next session that resumes can surface it
     *    (Task 6 will wire auto-wake from that store).
     */
    private fun onBackgroundCompletion(
        event: com.workflow.orchestrator.agent.tools.background.BackgroundCompletionEvent
    ) {
        val message = buildCompletionSystemMessage(event)

        steeringCapturersForTest[event.sessionId]?.let { capture ->
            runCatching { capture(message) }.onFailure {
                log.warn("[AgentService] steering capturer for ${event.sessionId} failed: ${it.message}")
            }
            return
        }

        val loop = activeLoopForSession(event.sessionId)
        if (loop != null) {
            loop.enqueueSteeringMessage(message)
        } else {
            // Task 6.1 — loop is idle. Always persist first so the completion
            // survives even if auto-wake is skipped (disabled / capped / cooled)
            // or the listener is unavailable — Task 6.2 picks it up on resume.
            persistForLaterResume(event)
            autoResumeForBackgroundCompletion(event.sessionId, event)
        }
    }

    /**
     * Task 6.1 — auto-wake the session for an idle-path background completion,
     * subject to guardrails:
     *  - `autoWakeOnBackgroundCompletion` setting toggle (master kill-switch)
     *  - per-session attempt cap (`autoWakeMaxPerSession`)
     *  - per-session cooldown window (`autoWakeCooldownMs`)
     *
     * If any guardrail rejects the wake, we log and return — the event is already
     * persisted by the caller so Task 6.2 still delivers it on the next resume.
     *
     * If all guards pass and a listener is registered (production path: wired from
     * [com.workflow.orchestrator.agent.ui.AgentController]), we hand off a synthetic
     * `[BACKGROUND COMPLETION — AUTO-RESUMED]` user message. If no listener is wired
     * (tests / shutdown), we no-op — the persisted entry still surfaces on resume.
     *
     * TODO: iteration budget guard — Task 6.x refinement. Current ActiveTask doesn't
     * expose the loop's iteration count cleanly; once AgentLoop publishes a metrics
     * snapshot, check `iterations >= 180` (of 200) and skip auto-wake accordingly.
     *
     * TODO: session-lock hold check — if another resume is already holding the
     * session lock (cross-instance), abort auto-wake. Presently [resumeSession]
     * already tryAcquires the lock and bails with a warning, so this is handled
     * one layer down; revisit if contention becomes a real-world problem.
     */
    private fun autoResumeForBackgroundCompletion(
        sessionId: String,
        event: com.workflow.orchestrator.agent.tools.background.BackgroundCompletionEvent,
    ) {
        val settings = AgentSettings.getInstance(project).state
        val decision = autoWakeGuards.decide(
            sessionId = sessionId,
            enabled = settings.autoWakeOnBackgroundCompletion,
            cap = settings.autoWakeMaxPerSession,
            cooldownMs = settings.autoWakeCooldownMs,
        )
        if (decision != com.workflow.orchestrator.agent.tools.background.AutoWakeGuardState.Decision.PROCEED) {
            log.info(
                "[AutoWake] skipped (${decision.name}); session=$sessionId bg=${event.bgId} " +
                    "attempts=${autoWakeGuards.attemptCount(sessionId)}"
            )
            return
        }

        val listener = autoWakeListener
        if (listener == null) {
            // No UI controller wired — persisted entry will be delivered on the
            // next manual resume via Task 6.2's resume-path pickup. Expected in
            // headless tests and during plugin startup before AgentController init.
            log.info("[AutoWake] no listener registered; deferring to resume pickup; session=$sessionId bg=${event.bgId}")
            return
        }

        val synthetic = buildAutoResumeSyntheticMessage(event)
        com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater {
            runCatching { listener.invoke(sessionId, synthetic) }
                .onFailure { log.warn("[AutoWake] listener failed: ${it.message}", it) }
        }
    }

    /**
     * Build the synthetic `[BACKGROUND COMPLETION — AUTO-RESUMED]` user message
     * delivered to the session on auto-wake. Format is pinned by
     * `AutoWakeSyntheticMessageFormatTest` (if introduced) — keep the markers and
     * section headers stable for downstream LLM parsing.
     */
    internal fun buildAutoResumeSyntheticMessage(
        event: com.workflow.orchestrator.agent.tools.background.BackgroundCompletionEvent,
    ): String = buildString {
        appendLine("[BACKGROUND COMPLETION — AUTO-RESUMED]")
        appendLine("Your previous turn ended, but a background process just completed:")
        appendLine()
        appendLine("Process ${event.bgId} (${event.kind}: \"${event.label.take(80)}\")")
        appendLine("State: ${event.state}, exit code: ${event.exitCode}, runtime: ${event.runtimeMs}ms")
        appendLine("Output (tail 20 lines):")
        event.tailContent.lines().takeLast(20).forEach { appendLine("  $it") }
        if (event.spillPath != null) appendLine("Full output: ${event.spillPath}")
        appendLine()
        appendLine("Decide whether this needs action. If it completes the original task or")
        appendLine("requires no follow-up, call attempt_completion. Otherwise continue working.")
    }

    /**
     * Build the system message injected as a steering message when a background
     * process exits. Uses a stable `[BACKGROUND COMPLETION]` prefix so tests and
     * humans can identify the source. Tail output is bounded to the last 20 lines
     * — full output lives at [BackgroundCompletionEvent.spillPath] when present.
     */
    private fun buildCompletionSystemMessage(
        event: com.workflow.orchestrator.agent.tools.background.BackgroundCompletionEvent
    ): String = buildString {
        appendLine("[BACKGROUND COMPLETION]")
        appendLine("Process ${event.bgId} (${event.kind}: \"${event.label.take(80)}\")")
        append("State: ${event.state}, exit code: ${event.exitCode}, ")
        appendLine("runtime: ${event.runtimeMs}ms")
        appendLine("Output (tail 20 lines):")
        event.tailContent.lines().takeLast(20).forEach { appendLine("  $it") }
        if (event.spillPath != null) appendLine("Full output: ${event.spillPath}")
    }

    /** Return the active loop if it is running for [sessionId], else null. */
    private fun activeLoopForSession(sessionId: String): AgentLoop? {
        val task = activeTask.get() ?: return null
        return if (task.sessionId == sessionId) task.loop else null
    }

    /**
     * Persist the completion for later resumption when no loop is active. Task 6
     * will read this store at session start and replay queued completions as
     * steering messages before the first LLM call.
     */
    private fun persistForLaterResume(
        event: com.workflow.orchestrator.agent.tools.background.BackgroundCompletionEvent
    ) {
        runCatching {
            com.workflow.orchestrator.agent.tools.background.BackgroundPersistence(agentDir.toPath())
                .appendCompletion(event.sessionId, event)
        }.onFailure {
            log.warn("[AgentService] BackgroundPersistence append failed for ${event.sessionId}: ${it.message}", it)
        }
    }

    /** Current session's message state handler — non-null while a task is running. */
    @Volatile var activeMessageStateHandler: MessageStateHandler? = null
        private set

    /**
     * Session-scoped task store. Initialised alongside [activeMessageStateHandler] at
     * task start, nulled out in the finally block and on [resetForNewChat].
     * Tools access it via [currentTaskStore].
     */
    @Volatile private var taskStore: TaskStore? = null

    /** Provider for the task-system tools — returns null when no session is active. */
    fun currentTaskStore(): TaskStore? = taskStore

    /** Model ID of the currently active parent brain. Subagents inherit this as their default model. */
    @Volatile private var currentBrainModelId: String? = null

    /**
     * Per-conversation runtime state that must persist across multiple [executeTask] calls
     * within the same session (multi-turn chat). Without this, every user message would
     * reset the api-debug call counter and the token/cost running totals to zero —
     * clobbering `call-001-*.txt` from the previous turn and making the TopBar stats
     * display snap back mid-conversation.
     *
     * Keyed by sessionId. Entries are created lazily on first [executeTask] call for a
     * session and cleared wholesale in [resetForNewChat].
     */
    private class SessionRuntimeState {
        val apiCallCounter: java.util.concurrent.atomic.AtomicInteger = java.util.concurrent.atomic.AtomicInteger(0)
        @Volatile var cumulativeInputTokens: Long = 0L
        @Volatile var cumulativeOutputTokens: Long = 0L
        @Volatile var cumulativeCostUsd: Double? = null
    }

    private val sessionRuntime = java.util.concurrent.ConcurrentHashMap<String, SessionRuntimeState>()

    private fun getOrCreateSessionRuntime(sessionId: String): SessionRuntimeState =
        sessionRuntime.computeIfAbsent(sessionId) { SessionRuntimeState() }

    // ── Auto Memory ────────────────────────────────────────────────────────

    /**
     * Return current memory stats for the TopBar indicator.
     * Pair of (coreChars, archivalCount). Returns null if memory is not yet initialized.
     * Best-effort, safe to call from any thread.
     */
    fun getMemoryStats(): Pair<Int, Int>? {
        return try {
            val core = coreMemory ?: return null
            val archival = archivalMemory ?: return null
            core.totalChars() to archival.size()
        } catch (e: Exception) {
            log.warn("[AgentService] Failed to read memory stats (non-fatal): ${e.message}")
            null
        }
    }

    /**
     * Reload core + archival memory from disk. Called by the Memory settings page
     * after the user saves edits, so the next task sees the latest state instead
     * of the agent's cached snapshot. Fixes data-loss bug C1.
     */
    fun reloadMemoryFromDisk() {
        try {
            coreMemory?.reload()
            archivalMemory?.reload()
        } catch (e: Exception) {
            log.warn("[AgentService] Failed to reload memory from disk (non-fatal): ${e.message}")
        }
    }

    /**
     * Lazily initialize AutoMemoryManager on first use. Retrieval-only — no LLM
     * client needed. Returns null if archival memory is not ready.
     *
     * Subsequent calls return the cached instance.
     */
    private suspend fun ensureAutoMemory(): AutoMemoryManager? {
        // Fast path: already cached — no lock needed
        autoMemoryManager?.let { return it }

        return autoMemoryInitMutex.withLock {
            // Double-check after acquiring lock — another coroutine may have initialized while we waited
            autoMemoryManager?.let { return@withLock it }

            val archival = archivalMemory ?: return@withLock null

            try {
                // Path checker for staleness filtering of recalled archival entries.
                // If an entry mentions file paths that no longer exist (renamed/moved/deleted),
                // the retriever will suppress it so we don't inject stale guidance into the prompt.
                val basePath = project.basePath
                val pathChecker: (String) -> Boolean = { path ->
                    try {
                        if (basePath != null) {
                            val abs = if (File(path).isAbsolute) File(path) else File(basePath, path)
                            abs.exists()
                        } else {
                            true  // No base path — can't verify, assume fresh
                        }
                    } catch (_: Exception) {
                        true  // On error, don't filter
                    }
                }

                val mgr = AutoMemoryManager(
                    archivalMemory = archival,
                    pathExists = pathChecker
                )
                autoMemoryManager = mgr
                log.info("[AgentService] AutoMemoryManager initialized (retrieval-only)")
                mgr
            } catch (e: Exception) {
                log.warn("[AgentService] Failed to initialize AutoMemoryManager (non-fatal): ${e.message}")
                null
            }
        }
    }

    // ── Brain ──────────────────────────────────────────────────────────────

    /**
     * Creates a fresh LLM brain for each task execution.
     * Never cached — always picks up the latest settings (model, URL, token).
     */
    private suspend fun createBrain(modelOverride: String? = null): LlmBrain {
        val connections = ConnectionSettings.getInstance()
        val sgUrl = connections.state.sourcegraphUrl.trimEnd('/')
        val credentialStore = CredentialStore()
        val tokenProvider = { credentialStore.getToken(ServiceType.SOURCEGRAPH) }

        if (sgUrl.isBlank()) {
            throw IllegalStateException("No Sourcegraph URL configured. Set one in Settings > AI & Advanced.")
        }

        val modelId = if (!modelOverride.isNullOrBlank()) {
            log.info("[Agent] Using caller-specified model override: $modelOverride")
            modelOverride
        } else {
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

            if (best != null) {
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
        }

        val allToolNames = registry.allToolNames()
        val allParamNames = registry.allParamNames()
        log.info("[Agent] Creating brain with model: $modelId at $sgUrl (tools=${allToolNames.size}, params=${allParamNames.size})")

        return OpenAiCompatBrain(
            sourcegraphUrl = sgUrl,
            tokenProvider = tokenProvider,
            model = modelId,
            toolNameSet = allToolNames,
            paramNameSet = allParamNames
        )
    }

    // ── Helpers ────────────────────────────────────────────────────────────

    /** Format available models as bullet-point strings for injection into the system prompt. */
    private fun formatModelsForPrompt(models: List<com.workflow.orchestrator.core.ai.dto.ModelInfo>): List<String>? {
        if (models.isEmpty()) return null
        return models
            .sortedWith(compareBy({ it.tier }, { -it.created }))
            .map { m ->
                val tags = mutableListOf<String>()
                if (m.isOpusClass) tags.add("most capable")
                if (m.modelName.lowercase().contains("sonnet")) tags.add("balanced")
                if (m.modelName.lowercase().contains("haiku")) tags.add("fastest, cheapest")
                if (m.isThinkingModel) tags.add("extended thinking")
                val tagStr = if (tags.isNotEmpty()) " — ${tags.joinToString(", ")}" else ""
                "- `${m.id}` (${m.displayName})$tagStr"
            }
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
     * Git subprocess tools removed — the LLM uses run_command for git operations.
     * Only changelist_shelve is kept (uses IntelliJ VCS API, not a git subprocess).
     */
    private fun registerAllTools() {
        ideContext = IdeContextDetector.detect(project)
        log.info("IDE context detected: ${ideContext.product} (${ideContext.edition}), " +
            "languages=${ideContext.languages}, frameworks=${ideContext.detectedFrameworks}, " +
            "buildTools=${ideContext.detectedBuildTools}")
        allowedShells = ShellResolver.detectAvailableShells(project).map { it.shellType.name.lowercase() }
        log.info("[AgentService] Available shells: $allowedShells")

        // Initialize language provider registry
        providerRegistry = LanguageProviderRegistry()
        if (ToolRegistrationFilter.shouldRegisterJavaPsiTools(ideContext)) {
            providerRegistry.register(JavaKotlinProvider(project))
        }
        if (ToolRegistrationFilter.shouldRegisterPythonPsiTools(ideContext)) {
            val pythonHelper = PythonPsiHelper()
            if (pythonHelper.isAvailable) {
                providerRegistry.register(PythonProvider(pythonHelper))
                log.info("Python code intelligence provider registered")
            } else {
                log.warn("Python PSI tools requested but PythonCore plugin classes not found")
            }
        }

        // ── Core tools (always sent to LLM) ──────────────────────────────
        safeRegisterCore { ReadFileTool() }
        safeRegisterCore { EditFileTool() }
        safeRegisterCore { CreateFileTool() }
        safeRegisterCore { SearchCodeTool() }
        safeRegisterCore { GlobFilesTool() }
        safeRegisterCore { RunCommandTool(allowedShells) }
        safeRegisterCore { SendStdinTool() }
        safeRegisterCore { BackgroundProcessTool() }
        safeRegisterCore { RevertFileTool() }
        safeRegisterCore { AttemptCompletionTool() }
        safeRegisterCore { TaskReportTool() }
        safeRegisterCore { ThinkTool() }
        safeRegisterCore { AskQuestionsTool() }
        safeRegisterCore { PlanModeRespondTool() }
        safeRegisterCore { EnablePlanModeTool() }
        safeRegisterCore { DiscardPlanTool() }
        safeRegisterCore { UseSkillTool() }
        safeRegisterCore { NewTaskTool() }
        safeRegisterCore { RenderArtifactTool() }

        // Task system tools — four LLM-facing tools for typed task management.
        // Ported from Claude Code's task-system behavior. Hook-exempt (see AgentLoop.HOOK_EXEMPT).
        safeRegisterCore { TaskCreateTool { currentTaskStore() } }
        safeRegisterCore { TaskUpdateTool { currentTaskStore() } }
        safeRegisterCore { TaskListTool { currentTaskStore() } }
        safeRegisterCore { TaskGetTool { currentTaskStore() } }

        // AI Review — local PR-review findings store. Hook-exempt (local-disk only, no Bitbucket calls).
        safeRegisterCore { AiReviewTool(project) }

        // Core PSI — essential navigation tools (guarded by IDE context)
        val hasPsiSupport = ToolRegistrationFilter.shouldRegisterJavaPsiTools(ideContext) ||
            ToolRegistrationFilter.shouldRegisterPythonPsiTools(ideContext)
        if (hasPsiSupport) {
            safeRegisterCore { FindDefinitionTool(providerRegistry) }
            safeRegisterCore { FindReferencesTool(providerRegistry) }
            safeRegisterCore { SemanticDiagnosticsTool(providerRegistry) }
        } else {
            log.info("Skipping PSI tools — neither Java nor Python plugin available")
        }

        // tool_search itself is core (the LLM needs it to discover deferred tools)
        safeRegisterCore { ToolSearchTool(registry) }

        // Sub-agent delegation tool — progress callback wired at task level
        safeRegisterCore { SpawnAgentTool(
            brainProvider = { modelOverride -> createBrain(modelOverride ?: currentBrainModelId) },
            toolRegistry = registry,
            project = project,
            configLoader = AgentConfigLoader.getInstance(),
            ideContext = ideContext
        ) }

        // ── Deferred tools (loaded via tool_search) ──────────────────────

        // Code Intelligence — PSI-based semantic analysis (guarded by IDE context)
        if (hasPsiSupport) {
            safeRegisterDeferred("Code Intelligence") { FindImplementationsTool(providerRegistry) }
            safeRegisterDeferred("Code Intelligence") { FileStructureTool(providerRegistry) }
            safeRegisterDeferred("Code Intelligence") { TypeHierarchyTool(providerRegistry) }
            safeRegisterDeferred("Code Intelligence") { CallHierarchyTool(providerRegistry) }
            safeRegisterDeferred("Code Intelligence") { TypeInferenceTool(providerRegistry) }
            safeRegisterDeferred("Code Intelligence") { DataFlowAnalysisTool(providerRegistry) }
            safeRegisterDeferred("Code Intelligence") { GetMethodBodyTool(providerRegistry) }
            safeRegisterDeferred("Code Intelligence") { GetAnnotationsTool(providerRegistry) }
            safeRegisterDeferred("Code Intelligence") { TestFinderTool(providerRegistry) }
            safeRegisterDeferred("Code Intelligence") { StructuralSearchTool(providerRegistry) }
            safeRegisterDeferred("Code Intelligence") { ReadWriteAccessTool(providerRegistry) }
        }

        // Project Structure — promote to core for multi-module projects
        if (ToolRegistrationFilter.shouldPromoteMultiModuleTools(ideContext)) {
            safeRegisterCore { com.workflow.orchestrator.agent.tools.project.ProjectStructureTool() }
            log.info("project_structure promoted to core (multi-module project detected)")
        } else {
            safeRegisterDeferred("Code Intelligence") { com.workflow.orchestrator.agent.tools.project.ProjectStructureTool() }
        }

        // Code Quality — formatting, refactoring, inspections
        safeRegisterDeferred("Code Quality") { FormatCodeTool() }
        safeRegisterDeferred("Code Quality") { OptimizeImportsTool() }
        safeRegisterDeferred("Code Quality") { RefactorRenameTool(providerRegistry) }
        safeRegisterDeferred("Code Quality") { RunInspectionsTool() }
        safeRegisterDeferred("Code Quality") { ProblemViewTool() }
        safeRegisterDeferred("Code Quality") { ListQuickFixesTool() }

        // VCS — IntelliJ changelist/shelve operations (git subprocess tools removed; use run_command for git)
        safeRegisterDeferred("VCS") { ChangelistShelveTool() }

        // Build & Run — project build, run configs, coverage
        // Build tool — register if Java OR Python build tools available
        val hasBuildSupport = ToolRegistrationFilter.shouldRegisterJavaBuildTools(ideContext) ||
            ToolRegistrationFilter.shouldRegisterPythonBuildTools(ideContext)
        if (hasBuildSupport) {
            if (ToolRegistrationFilter.shouldPromoteMultiModuleTools(ideContext)) {
                safeRegisterCore { BuildTool() }
                log.info("build promoted to core (multi-module project detected)")
            } else {
                safeRegisterDeferred("Build & Run") { BuildTool() }
            }
        } else {
            log.info("Skipping build tools — neither Java nor Python build tools available")
        }
        val registerEndpoints = ToolRegistrationFilter.shouldRegisterMicroservicesEndpoints(ideContext)
        val registerSpringEndpointActions = ToolRegistrationFilter.shouldRegisterSpringEndpointActions(ideContext)

        if (registerEndpoints) {
            safeRegisterDeferred("Build & Run") { EndpointsTool() }
            log.info("endpoints tool registered (microservices module available)")
        }

        if (ToolRegistrationFilter.shouldRegisterSpringTools(ideContext)) {
            safeRegisterDeferred("Build & Run") {
                SpringTool(includeEndpointActions = registerSpringEndpointActions)
            }
            if (!registerSpringEndpointActions) {
                log.info("SpringTool registered without endpoints/boot_endpoints actions (superseded by endpoints tool)")
            }
        } else {
            log.info("Skipping Spring tools — Spring plugin not available")
        }
        // Django
        if (ToolRegistrationFilter.shouldRegisterDjangoTools(ideContext)) {
            if (ToolRegistrationFilter.shouldPromoteFrameworkTool(ideContext, Framework.DJANGO)) {
                safeRegisterCore { DjangoTool() }
                log.info("Django tool promoted to core (framework detected in project)")
            } else {
                safeRegisterDeferred("Framework") { DjangoTool() }
            }
        }
        // FastAPI
        if (ToolRegistrationFilter.shouldRegisterFastApiTools(ideContext)) {
            if (ToolRegistrationFilter.shouldPromoteFrameworkTool(ideContext, Framework.FASTAPI)) {
                safeRegisterCore { FastApiTool() }
                log.info("FastAPI tool promoted to core (framework detected in project)")
            } else {
                safeRegisterDeferred("Framework") { FastApiTool() }
            }
        }
        // Flask
        if (ToolRegistrationFilter.shouldRegisterFlaskTools(ideContext)) {
            if (ToolRegistrationFilter.shouldPromoteFrameworkTool(ideContext, Framework.FLASK)) {
                safeRegisterCore { FlaskTool() }
                log.info("Flask tool promoted to core (framework detected in project)")
            } else {
                safeRegisterDeferred("Framework") { FlaskTool() }
            }
        }
        // Universal process observation (no compile or test runner)
        safeRegisterDeferred("Build & Run") { RuntimeExecTool() }
        if (ToolRegistrationFilter.shouldPromoteMultiModuleTools(ideContext)) {
            safeRegisterCore { RuntimeConfigTool() }
            log.info("runtime_config promoted to core (multi-module project detected)")
        } else {
            safeRegisterDeferred("Build & Run") { RuntimeConfigTool() }
        }

        // Java/Kotlin native test runner + Java compiler
        if (ToolRegistrationFilter.shouldRegisterJavaBuildTools(ideContext)) {
            safeRegisterDeferred("Build & Run") { JavaRuntimeExecTool() }
        }

        // Python pytest runner + py_compile
        if (ToolRegistrationFilter.shouldRegisterPythonBuildTools(ideContext)) {
            safeRegisterDeferred("Build & Run") { PythonRuntimeExecTool() }
        }
        // Coverage depends on the Coverage plugin (Ultimate/Professional)
        if (ToolRegistrationFilter.shouldRegisterCoverageTool(ideContext)) {
            safeRegisterDeferred("Build & Run") { CoverageTool() }
        } else {
            log.info("Skipping coverage tools — requires Ultimate or Professional edition")
        }

        // Database — queries, schema, connection profiles
        safeRegisterDeferred("Database") { DbListProfilesTool() }
        safeRegisterDeferred("Database") { DbListDatabasesTool() }
        safeRegisterDeferred("Database") { DbQueryTool() }
        safeRegisterDeferred("Database") { DbSchemaTool() }
        safeRegisterDeferred("Database") { DbStatsTool() }
        safeRegisterDeferred("Database") { DbExplainTool() }

        // Utilities
        safeRegisterDeferred("Utilities") { ProjectContextTool() }
        safeRegisterDeferred("Utilities") { CurrentTimeTool() }
        safeRegisterDeferred("Utilities") { AskUserInputTool() }

        // Debug tools (require AgentDebugController)
        // XDebugger-based tools work for both Java/Kotlin and Python debug sessions.
        val hasDebugSupport = ToolRegistrationFilter.shouldRegisterJavaDebugTools(ideContext) ||
            ToolRegistrationFilter.shouldRegisterPythonDebugTools(ideContext)
        if (hasDebugSupport) {
            registerDebugTools()
        } else {
            log.info("Skipping debug tools — neither Java nor Python plugin available")
        }

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

        if (failedRegistrations.isNotEmpty()) {
            log.error("AgentService: ${failedRegistrations.size} tools failed to register: $failedRegistrations")
        }
        log.info("AgentService: registered ${registry.coreCount()} core + ${registry.deferredCount()} deferred tools" +
            if (failedRegistrations.isNotEmpty()) " (${failedRegistrations.size} failed)" else "")
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

    /**
     * Re-check connection settings and register/unregister integration tools accordingly.
     *
     * Called whenever connection settings change so the tool set stays in sync without
     * requiring an agent restart. Safe to call at any time — each check is idempotent:
     *   - URL configured + tool absent  → register the tool
     *   - URL blank      + tool present → unregister the tool
     *   - Everything else               → no-op
     */
    fun reregisterConditionalTools() {
        val connections = ConnectionSettings.getInstance()

        // Jira
        if (connections.state.jiraUrl.isNotBlank()) {
            if (registry.getTool("jira") == null) safeRegisterDeferred("Integration") { JiraTool() }
        } else {
            if (registry.getTool("jira") != null) registry.unregisterDeferred("jira")
        }

        // Bamboo
        if (connections.state.bambooUrl.isNotBlank()) {
            if (registry.getTool("bamboo_builds") == null) safeRegisterDeferred("Integration") { BambooBuildsTool() }
            if (registry.getTool("bamboo_plans") == null) safeRegisterDeferred("Integration") { BambooPlansTool() }
        } else {
            if (registry.getTool("bamboo_builds") != null) registry.unregisterDeferred("bamboo_builds")
            if (registry.getTool("bamboo_plans") != null) registry.unregisterDeferred("bamboo_plans")
        }

        // SonarQube
        if (connections.state.sonarUrl.isNotBlank()) {
            if (registry.getTool("sonar") == null) safeRegisterDeferred("Integration") { SonarTool() }
        } else {
            if (registry.getTool("sonar") != null) registry.unregisterDeferred("sonar")
        }

        // Bitbucket
        if (connections.state.bitbucketUrl.isNotBlank()) {
            if (registry.getTool("bitbucket_pr") == null) safeRegisterDeferred("Integration") { BitbucketPrTool() }
            if (registry.getTool("bitbucket_repo") == null) safeRegisterDeferred("Integration") { BitbucketRepoTool() }
            if (registry.getTool("bitbucket_review") == null) safeRegisterDeferred("Integration") { BitbucketReviewTool() }
        } else {
            if (registry.getTool("bitbucket_pr") != null) registry.unregisterDeferred("bitbucket_pr")
            if (registry.getTool("bitbucket_repo") != null) registry.unregisterDeferred("bitbucket_repo")
            if (registry.getTool("bitbucket_review") != null) registry.unregisterDeferred("bitbucket_review")
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
            log.warn("AgentService: failed to register core tool: ${e.message}", e)
            failedRegistrations.add("core:${e.message?.take(50) ?: "unknown"}")
        }
    }

    private inline fun safeRegisterDeferred(category: String = "Other", factory: () -> AgentTool) {
        try {
            registry.registerDeferred(factory(), category)
        } catch (e: Exception) {
            log.warn("AgentService: failed to register deferred tool ($category): ${e.message}", e)
            failedRegistrations.add("deferred/$category:${e.message?.take(50) ?: "unknown"}")
        }
    }

    // ── Task Execution ─────────────────────────────────────────────────────

    /**
     * Strip trailing empty-assistant pollution from the persisted conversation history
     * of the active session, if any. Called from the UI retry path before the "continue"
     * task is issued, so the new loop starts from a clean tail.
     *
     * Safe to call with no active session — returns 0. Thread-safe via the handler's
     * internal mutex. Does NOT touch `ui_messages.json` (we want the user-visible chat
     * trail to retain the "provider returned an empty response" note).
     *
     * No in-memory cleanup: the prior `AgentLoop`'s `ContextManager` is a local in
     * the loop coroutine and is already out of scope by the time retry fires. The new
     * loop rebuilds its `ContextManager` from this cleaned disk state.
     *
     * @return number of empty-assistant entries removed, or 0 if no active session
     */
    suspend fun cleanEmptyArtifactsBeforeRetry(): Int {
        val handler = activeMessageStateHandler ?: return 0
        val diskRemoved = handler.pruneTrailingEmptyAssistants()
        if (diskRemoved > 0) {
            log.info("[AgentService] retry cleanup: $diskRemoved on-disk empty-assistant turn(s) removed")
        }
        return diskRemoved
    }

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
        onComplete: (LoopResult) -> Unit = {},
        /**
         * Callback fired when the LLM presents a plan via plan_mode_respond.
         * Used by the UI to render the plan card. Does NOT exit the loop.
         */
        onPlanResponse: ((planText: String, needsMoreExploration: Boolean) -> Unit)? = null,
        /**
         * Callback fired when the LLM toggles plan mode via enable_plan_mode tool.
         * Used by the UI to update the plan mode button and rebuild tool definitions.
         */
        onPlanModeToggled: ((Boolean) -> Unit)? = null,
        /**
         * Callback fired when the LLM discards the current plan via discard_plan tool.
         * The UI uses this to clear the active plan card without presenting a replacement.
         */
        onPlanDiscarded: (() -> Unit)? = null,
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
        approvalGate: (suspend (toolName: String, args: String, riskLevel: String, allowSessionApproval: Boolean) -> com.workflow.orchestrator.agent.loop.ApprovalResult)? = null,
        /**
         * Session-scoped approval store. Tracks which tools the user has approved
         * for the current session. Injected from the controller so approvals persist
         * across follow-up messages (multiple executeTask calls within the same session).
         * Defaults to a fresh store for backward compatibility (tests, sub-agents).
         */
        sessionApprovalStore: com.workflow.orchestrator.agent.loop.SessionApprovalStore = com.workflow.orchestrator.agent.loop.SessionApprovalStore(),
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
         * Optional callback fired after each API call with cumulative session stats.
         * Used by the UI to show the model chip, token counts, and estimated cost in the TopBar.
         */
        onSessionStats: ((modelId: String, tokensIn: Long, tokensOut: Long, costUsd: Double?) -> Unit)? = null,
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
        onSteeringDrained: ((drainedIds: List<String>) -> Unit)? = null,
        /**
         * Optional pre-built MessageStateHandler for resumed sessions.
         * When provided, skips internal MessageStateHandler creation and initial message recording.
         * Used by resumeSession which pre-loads state from persisted files.
         */
        messageStateHandler: MessageStateHandler? = null,
        /**
         * Fires when the loop suspends on userInputChannel waiting for the user
         * (plan-mode reply turn or consecutive-mistakes recovery). The UI must
         * drop the working spinner, enable steering, and surface the reason.
         */
        onAwaitingUserInput: ((reason: String) -> Unit)? = null,
        /**
         * Optional override for the UI message that will be persisted for this task.
         * When provided, this message is added to the UI message history instead of
         * the synthesized USER_MESSAGE. The task text is still sent to the LLM
         * (via addToApiConversationHistory), so the LLM always gets the full context.
         * Defaults to null (preserves existing behavior of synthesizing a USER_MESSAGE).
         *
         * **Has no effect when `messageStateHandler` is also provided.** When a
         * messageStateHandler is passed, it manages its own UI message recording before
         * `executeTask` is called (e.g., in the resume path). The override is only used
         * when creating a fresh MessageStateHandler.
         *
         * **Caller responsibility:** If provided, the caller must set `ts` to a valid
         * timestamp (`>= System.currentTimeMillis()`) to preserve chronological ordering
         * in the persisted UI message file.
         */
        uiMessageOverride: UiMessage? = null,
        /**
         * Optional callback invoked when the loop receives a message from [userInputChannel].
         * Called with the raw task string; returns the [UiMessage] to persist for that turn,
         * or null if no UI message should be written (e.g. for plan-mode comment injections
         * that are already shown via other UI means).
         *
         * Forwarded directly to [AgentLoop] — see its own KDoc for the contract.
         */
        onUserInputReceived: ((task: String) -> com.workflow.orchestrator.agent.session.UiMessage?)? = null
    ): Job {
        val sid = sessionId ?: UUID.randomUUID().toString()

        var session = Session(
            id = sid,
            title = task.take(100),
            status = SessionStatus.ACTIVE
        )
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
                        onComplete(LoopResult.Cancelled(iterations = 0))
                        return@launch
                    }
                }

                // I3: Create a fresh brain each time to pick up settings changes
                val brain = createBrain()
                brainRef = brain
                currentBrainModelId = brain.modelId
                log.info("[Agent] Task started: sessionId=$sid, model=${brain.modelId}")

                // Wire API debug dumps — save request/response JSON per session
                val basePath = project.basePath ?: System.getProperty("user.home")
                val sessionDebugDir = java.io.File(
                    ProjectIdentifier.agentDir(basePath),
                    "sessions/$sid"
                )
                // Output spiller: writes large tool outputs to disk, returns preview to LLM.
                // Assigned to the session-scoped field so AgentTool.spillOrFormat() can resolve
                // it via project.service<AgentService>().outputSpiller without coupling tools to
                // the executeTask closure.
                _outputSpiller = ToolOutputSpiller(
                    java.io.File(sessionDebugDir, "tool-output").toPath()
                )
                // Session-scoped API call counter — shared across the initial brain AND any
                // brains spawned by the brainFactory below (recycle, model fallback), AND
                // across every follow-up user message within the same session. Owned by
                // [sessionRuntime] so `api-debug/call-NNN-*.txt` filenames stay monotonic
                // across the entire conversation, not just a single [executeTask] call.
                // Cleared wholesale in [resetForNewChat].
                val runtime = getOrCreateSessionRuntime(sid)
                val sharedApiCounter = runtime.apiCallCounter
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
                val brainFactory: suspend (String, String?) -> LlmBrain = { modelId: String, reason: String? ->
                    val currentToolNames = registry.allToolNames()
                    val currentParamNames = registry.allParamNames()
                    val newBrain = OpenAiCompatBrain(
                        sourcegraphUrl = fbUrl,
                        tokenProvider = fbTokenProvider,
                        model = modelId,
                        toolNameSet = currentToolNames,
                        paramNameSet = currentParamNames
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
                    currentBrainModelId = newBrain.modelId

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

                // Build deferred catalog for system prompt injection (grouped by category with descriptions)
                val deferredCatalog = registry.getDeferredCatalogGroupedWithDescriptions()

                // MEMORY INDEX: Load per-project MEMORY.md for injection into Section 10
                val memoryDirPath = java.io.File(agentDir, "memory").toPath()
                val memoryIndexContent = com.workflow.orchestrator.agent.memory.MemoryIndex.load(memoryDirPath)
                val memoryIndexPath = memoryDirPath.resolve("MEMORY.md").toString()

                // Build system prompt — XML tool definitions added dynamically below
                val systemPromptBuilder = { toolDefsMarkdown: String? ->
                    SystemPrompt.build(
                        projectName = projectName,
                        projectPath = projectPath,
                        planModeEnabled = planModeActive.get(),
                        additionalContext = projectInstructions,
                        availableSkills = availableSkills,
                        activeSkillContent = ctx.getActiveSkill(),
                        taskProgress = ctx.renderTaskProgressMarkdown(),
                        deferredToolCatalog = deferredCatalog,
                        toolDefinitionsMarkdown = toolDefsMarkdown,
                        memoryIndex = memoryIndexContent,
                        memoryIndexPath = memoryIndexPath,
                        ideContext = ideContext,
                        availableShells = allowedShells,
                        availableModels = formatModelsForPrompt(ModelCache.getCached())
                    )
                }
                // Set initial system prompt (XML defs added on first toolDefinitionProvider call)
                ctx.setSystemPrompt(systemPromptBuilder(null))

                // Build tool definitions dynamically — called on each loop iteration.
                // Plan mode: remove write tools + enable_plan_mode, keep plan_mode_respond.
                // Act mode: remove plan_mode_respond, keep write tools + enable_plan_mode.
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
                                tool.name !in writeToolNames && tool.name != "enable_plan_mode"
                            } else {
                                tool.name != "plan_mode_respond" && tool.name != "discard_plan"
                            }
                        }
                        .map { AgentTool.injectOutputParams(it.toToolDefinition()) }

                    // Update system prompt when tool set changes (plan mode switch, deferred tool load)
                    val defsHash = defs.map { it.function.name }.hashCode()
                    if (defsHash != lastXmlToolDefsHash) {
                        lastXmlToolDefsHash = defsHash
                        val markdown = com.workflow.orchestrator.core.ai.ToolPromptBuilder.build(defs)
                        ctx.setSystemPrompt(systemPromptBuilder(markdown))
                    }

                    defs
                }

                // Wire sub-agent progress callback and settings for this task execution.
                // All parent-session callbacks — approvalGate, hookManager, sessionMetrics,
                // fileLogger, onDebugLog, onCheckpoint — are forwarded so sub-agents honour
                // the same approval UX, hooks, observability, and checkpoint timeline as
                // the main agent. Without this plumbing, delegating a write tool to a
                // sub-agent would bypass the modal; delegating any tool would leave its
                // PRE/POST_TOOL_USE hooks silent and its timings off the scorecard.
                val spawnAgentTool = registry.get("agent") as? com.workflow.orchestrator.agent.tools.builtin.SpawnAgentTool
                if (spawnAgentTool != null) {
                    spawnAgentTool.contextBudget = agentSettings.state.maxInputTokens
                    spawnAgentTool.maxOutputTokens = agentSettings.state.maxOutputTokens
                    spawnAgentTool.sessionDebugDir = sessionDebugDir
                    spawnAgentTool.toolExecutionMode = agentSettings.state.toolExecutionMode ?: "accumulate"
                    spawnAgentTool.approvalGate = approvalGate
                    spawnAgentTool.sessionApprovalStore = sessionApprovalStore
                    spawnAgentTool.hookManager = hookManager
                    spawnAgentTool.sessionMetrics = sessionMetrics
                    spawnAgentTool.fileLogger = fileLogger
                    spawnAgentTool.onDebugLog = onDebugLog
                    spawnAgentTool.onCheckpoint = onCheckpointSaved?.let { cb ->
                        // AgentLoop expects `suspend () -> Unit`; the outer callback is
                        // `(String) -> Unit` keyed by sessionId. Close over the current
                        // session id so parent-timeline checkpoint updates route correctly.
                        val sidSnapshot = sid
                        suspend { cb(sidSnapshot) }
                    }
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

                // Create MessageStateHandler for Cline-style two-file persistence.
                // Persists ui_messages.json + api_conversation_history.json per session.
                // Reuses basePath from API debug dir setup above.
                val sessionBaseDir = ProjectIdentifier.agentDir(basePath)
                val messageState = messageStateHandler ?: run {
                    // Check if this is a follow-up turn in an existing session
                    val sessionDir = java.io.File(sessionBaseDir, "sessions/$sid")
                    val existingUi = if (sessionDir.exists()) MessageStateHandler.loadUiMessages(sessionDir) else emptyList()
                    val existingApi = if (sessionDir.exists()) MessageStateHandler.loadApiHistory(sessionDir) else emptyList()

                    // For follow-up turns, use the original task text from the first message
                    val resolvedTaskText = if (existingUi.isNotEmpty()) {
                        existingUi.firstOrNull { it.say == UiSay.TEXT }?.text?.take(200) ?: task.take(200)
                    } else {
                        task.take(200)
                    }

                    val handler = MessageStateHandler(
                        baseDir = sessionBaseDir,
                        sessionId = sid,
                        taskText = resolvedTaskText
                    )

                    // Restore existing messages for follow-up turns (multi-turn session continuity)
                    if (existingUi.isNotEmpty()) handler.setClineMessages(existingUi)
                    if (existingApi.isNotEmpty()) handler.setApiConversationHistory(existingApi)

                    // Add user message for this turn to both persistence files.
                    // Skipped when messageStateHandler is pre-built (resume path adds its own).
                    handler.addToApiConversationHistory(ApiMessage(
                        role = ApiRole.USER,
                        content = listOf(ContentBlock.Text(task)),
                        ts = System.currentTimeMillis()
                    ))
                    val uiMsg = uiMessageOverride ?: UiMessage(
                        ts = System.currentTimeMillis(),
                        type = UiMessageType.SAY,
                        say = UiSay.USER_MESSAGE,
                        text = task
                    )
                    handler.addToClineMessages(uiMsg)
                    handler
                }

                // Expose active handler so AgentController.dismissPlan() can rewrite history.
                activeMessageStateHandler = messageState

                // Initialise session-scoped task store alongside the message state handler.
                // loadFromDisk() runs inside a coroutine scope so it is safe to call here.
                // Create and fully initialise before field assignment so observers never
                // see a partially-initialised store (closes the init-race window).
                val store = TaskStore(baseDir = sessionBaseDir, sessionId = sid)
                store.loadFromDisk()
                taskStore = store

                // Attach TaskStore to ContextManager so renderTaskProgressMarkdown() reads live task state.
                ctx.attachTaskStore(store)

                // Wire onHistoryOverwrite callback so compaction persists truncated history.
                // Ported from Cline's conversationHistoryDeletedRange pattern: after context
                // truncation/summarization, the modified api_conversation_history is overwritten.
                ctx.onHistoryOverwrite = { msgs, _ ->
                    messageState.overwriteApiConversationHistory(
                        msgs.map { it.toApiMessage() }
                    )
                }

                // Write checkpoint counter — create checkpoint after write operations
                var writeCheckpointCounter = 0

                // Resolve default target branch asynchronously — DefaultBranchResolver.resolve()
                // is suspend, but environmentDetailsProvider is a non-suspend lambda.
                // Capture result once at task start; lambda reads the var once it is populated.
                val resolvedDefaultBranch = AtomicReference<String?>(null)
                launch(Dispatchers.IO) {
                    try {
                        val repos = git4idea.repo.GitRepositoryManager.getInstance(project).repositories
                        val primary = repos.firstOrNull() ?: return@launch
                        resolvedDefaultBranch.set(kotlinx.coroutines.withTimeoutOrNull(3000L) {
                            com.workflow.orchestrator.core.util.DefaultBranchResolver
                                .getInstance(project).resolve(primary)
                        })
                    } catch (_: Exception) { /* leave null if branch resolution fails */ }
                }

                val loop = AgentLoop(
                    brain = brain,
                    tools = tools,
                    toolDefinitions = toolDefs,
                    contextManager = ctx,
                    project = project,
                    onStreamChunk = onStreamChunk,
                    onToolCall = onToolCall,
                    planMode = planModeActive.get(),
                    maxOutputTokens = agentSettings.state.maxOutputTokens,
                    toolDefinitionProvider = toolDefinitionProvider,
                    toolResolver = { name -> registry.get(name) },
                    hookManager = if (hookManager.hasAnyHooks()) hookManager else null,
                    sessionId = sid,
                    onTokenUpdate = onTokenUpdate,
                    // Seed cumulative totals from prior turns in this session so the TopBar
                    // display stays monotonic across user messages (bug: fresh loop reset
                    // to 0). The wrapper below captures the loop's cumulative push so
                    // the NEXT executeTask can pick up where this one left off.
                    initialInputTokens = runtime.cumulativeInputTokens.toInt(),
                    initialOutputTokens = runtime.cumulativeOutputTokens.toInt(),
                    initialCostUsd = runtime.cumulativeCostUsd,
                    onSessionStats = { modelId, tokensIn, tokensOut, costUsd ->
                        runtime.cumulativeInputTokens = tokensIn
                        runtime.cumulativeOutputTokens = tokensOut
                        runtime.cumulativeCostUsd = costUsd
                        onSessionStats?.invoke(modelId, tokensIn, tokensOut, costUsd)
                    },
                    onPlanResponse = onPlanResponse,
                    onPlanModeToggle = { enabled ->
                        planModeActive.set(enabled)
                        onPlanModeToggled?.invoke(enabled)
                    },
                    onPlanDiscarded = {
                        // Rewrite the prior plan_mode_respond result in history so the LLM
                        // won't re-surface the discarded plan on the next turn.
                        messageState.rewriteMostRecentToolResult(
                            "plan_mode_respond",
                            "[Plan discarded — do not reference]"
                        )
                        // Then notify the UI (clear the plan card, etc.)
                        onPlanDiscarded?.invoke()
                    },
                    userInputChannel = userInputChannel,
                    approvalGate = approvalGate,
                    sessionApprovalStore = sessionApprovalStore,
                    onWriteCheckpoint = { toolName, args ->
                        // Create named checkpoint after write operations (ported from Cline)
                        writeCheckpointCounter++
                        try {
                            val checkpointId = "cp-${writeCheckpointCounter}-${System.currentTimeMillis()}"
                            val description = "After $toolName: ${args.take(100)}"
                            MessageStateHandler.saveCheckpoint(
                                baseDir = sessionBaseDir,
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
                        // Multi-repo-aware branch resolution:
                        // 1. Primary = current-editor's repo (falls back to configured primary, then
                        //    first known repo). Fixes the `.firstOrNull()` bug where editing a file
                        //    in a non-first repo would surface the wrong branch.
                        // 2. Enumerate ALL repos so multi-repo projects expose per-repo branches to
                        //    the LLM — previously only one unlabelled branch was ever shown.
                        val vcsData = try {
                            val resolver = com.workflow.orchestrator.core.settings.RepoContextResolver.getInstance(project)
                            val primaryRepo = resolver.resolveCurrentEditorRepoOrPrimary()
                            val allRepos = git4idea.repo.GitRepositoryManager.getInstance(project).repositories
                            val primaryBranch = primaryRepo?.currentBranchName
                            val basePath = project.basePath
                            fun labelFor(repo: git4idea.repo.GitRepository): String {
                                val root = repo.root.path
                                if (basePath != null && root.startsWith(basePath)) {
                                    val rel = root.removePrefix(basePath).trimStart('/')
                                    return if (rel.isBlank()) repo.root.name else rel
                                }
                                return repo.root.name
                            }
                            val primaryLabel = primaryRepo?.let { labelFor(it) }
                            val others = if (allRepos.size > 1) {
                                allRepos
                                    .filter { it !== primaryRepo }
                                    .mapNotNull { repo ->
                                        val b = repo.currentBranchName ?: return@mapNotNull null
                                        labelFor(repo) to b
                                    }
                            } else emptyList()
                            Triple(primaryBranch, primaryLabel, others)
                        } catch (_: Exception) { Triple(null, null, emptyList()) }
                        EnvironmentDetailsBuilder.build(
                            project = project,
                            planModeEnabled = planModeActive.get(),
                            contextManager = ctx,
                            activeTicketId = pluginSettings.state.activeTicketId,
                            activeTicketSummary = pluginSettings.state.activeTicketSummary,
                            currentBranch = vcsData.first,
                            defaultTargetBranch = resolvedDefaultBranch.get(),
                            primaryRepoLabel = vcsData.second,
                            otherRepoBranches = vcsData.third,
                        )
                    },
                    steeringQueue = steeringQueue,
                    onSteeringDrained = onSteeringDrained,
                    onAwaitingUserInput = onAwaitingUserInput,
                    fallbackManager = fallbackManager,
                    brainFactory = brainFactory,
                    cachedFallbackChain = cachedFallbackChain,
                    onModelSwitch = onModelSwitch,
                    compactOnTimeoutExhaustion = compactOnTimeoutExhaustion,
                    onCheckpoint = {
                        // No-op: MessageStateHandler now handles per-change persistence
                        // directly (Task 6). This callback is retained as a lifecycle hook
                        // but the old SessionStore-based JSONL append path is removed.
                    },
                    messageStateHandler = messageState,
                    toolExecutionMode = agentSettings.state.toolExecutionMode ?: "accumulate",
                    toolNameProvider = { registry.allToolNames() },
                    paramNameProvider = { registry.allParamNames() },
                    outputSpiller = _outputSpiller,
                    onUserInputReceived = onUserInputReceived,
                )

                // I4: Set activeTask atomically after both loop and job are available
                activeTask.set(ActiveTask(sessionId = sid, loop = loop, job = coroutineContext.job))

                val result = loop.run(task)

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
                fileLogger.logSessionEnd(sid, 0, 0, System.currentTimeMillis() - sessionStartTime)
                onComplete(LoopResult.Cancelled(iterations = 0))
            } catch (e: Exception) {
                log.error("AgentService: task execution failed", e)
                session = session.copy(
                    status = SessionStatus.FAILED,
                    lastMessageAt = System.currentTimeMillis()
                )
                fileLogger.logSessionEnd(sid, 0, 0, System.currentTimeMillis() - sessionStartTime, error = e.message)
                onComplete(LoopResult.Failed(error = e.message ?: "Unknown error", reason = FailureReason.EXCEPTION))
            } finally {
                activeTask.set(null)
                activeMessageStateHandler = null
                taskStore = null
                // Clear API debug dir so the brain doesn't dump after task ends
                (brainRef as? OpenAiCompatBrain)?.setApiDebugDir(null)
                // Clear per-task sub-agent callbacks. Leaving any of these attached
                // would allow a stale reference to the previous session's AgentController
                // / loggers / hook manager to handle events for the next spawn call —
                // cancelled deferreds, dangling modals, cross-session metric contamination.
                (registry.get("agent") as? com.workflow.orchestrator.agent.tools.builtin.SpawnAgentTool)?.let {
                    it.onSubagentProgress = null
                    it.approvalGate = null
                    it.sessionApprovalStore = null
                    it.hookManager = null
                    it.sessionMetrics = null
                    it.fileLogger = null
                    it.onDebugLog = null
                    it.onCheckpoint = null
                }
            }
        }
        return job
    }

    // ── Session Resume ────────────────────────────────────────────────────

    /**
     * Resume a previously interrupted session.
     *
     * Faithful port of Cline's task resumption pattern (resumeTaskFromHistory):
     * 1. Load both JSON files (ui_messages + api_conversation_history)
     * 2. Acquire session lock (prevents double-resume across IDE instances)
     * 3. Trim trailing resume messages and cost-less api_req_started
     * 4. Pop trailing user message if interrupted mid-submission
     * 5. Build taskResumption preamble with time-ago and mode
     * 6. Create MessageStateHandler with restored state
     * 7. Rebuild ContextManager from API history (lossless ApiMessage→ChatMessage)
     * 8. Execute task with rebuilt context
     *
     * @param sessionId ID of the session to resume
     * @param userText optional user message provided at resume time
     * @param onStreamChunk streaming callback
     * @param onToolCall tool progress callback
     * @param onComplete completion callback
     * @param onUiMessagesLoaded callback to push full UI state to webview for rehydration
     * @return the Job, or null if session not found, locked, or has no history
     *
     * @see <a href="https://github.com/cline/cline/blob/main/src/core/Cline.ts">Cline resumeTaskFromHistory</a>
     */
    fun resumeSession(
        sessionId: String,
        userText: String? = null,
        onStreamChunk: (String) -> Unit = {},
        onToolCall: (ToolCallProgress) -> Unit = {},
        onComplete: (LoopResult) -> Unit = {},
        onUiMessagesLoaded: ((List<UiMessage>) -> Unit)? = null,
        onRetry: ((attempt: Int, maxAttempts: Int, reason: String, delayMs: Long) -> Unit)? = null,
        onModelSwitch: ((fromModel: String, toModel: String, reason: String) -> Unit)? = null,
        onPlanResponse: ((planText: String, needsMoreExploration: Boolean) -> Unit)? = null,
        onPlanModeToggled: ((Boolean) -> Unit)? = null,
        onPlanDiscarded: (() -> Unit)? = null,
        userInputChannel: Channel<String>? = null,
        approvalGate: (suspend (toolName: String, args: String, riskLevel: String, allowSessionApproval: Boolean) -> com.workflow.orchestrator.agent.loop.ApprovalResult)? = null,
        onCheckpointSaved: ((sessionId: String) -> Unit)? = null,
        onSubagentProgress: ((agentId: String, update: SubagentProgressUpdate) -> Unit)? = null,
        onTokenUpdate: ((inputTokens: Int, outputTokens: Int) -> Unit)? = null,
        onSessionStats: ((modelId: String, tokensIn: Long, tokensOut: Long, costUsd: Double?) -> Unit)? = null,
        onDebugLog: ((level: String, event: String, detail: String, meta: Map<String, Any?>?) -> Unit)? = null,
        onSessionStarted: ((sessionId: String) -> Unit)? = null,
        steeringQueue: java.util.concurrent.ConcurrentLinkedQueue<SteeringMessage>? = null,
        onSteeringDrained: ((drainedIds: List<String>) -> Unit)? = null,
        sessionApprovalStore: com.workflow.orchestrator.agent.loop.SessionApprovalStore = com.workflow.orchestrator.agent.loop.SessionApprovalStore(),
        onAwaitingUserInput: ((reason: String) -> Unit)? = null,
        onUserInputReceived: ((task: String) -> com.workflow.orchestrator.agent.session.UiMessage?)? = null,
    ): Job? {
        val basePath = project.basePath ?: System.getProperty("user.home")
        val sessionBaseDir = ProjectIdentifier.agentDir(basePath)
        val sessionDir = File(sessionBaseDir, "sessions/$sessionId")
        if (!sessionDir.exists()) {
            log.warn("AgentService.resumeSession: session dir not found for $sessionId")
            return null
        }

        // Acquire session lock — prevents double-resume across IDE instances
        val lock = SessionLock.tryAcquire(sessionDir)
        if (lock == null) {
            log.warn("AgentService.resumeSession: session $sessionId is locked by another instance")
            return null
        }

        // Load persisted state from both JSON files
        var savedUiMessages = MessageStateHandler.loadUiMessages(sessionDir)
        val savedApiHistory = MessageStateHandler.loadApiHistory(sessionDir)

        if (savedApiHistory.isEmpty()) {
            log.warn("AgentService.resumeSession: no api history for $sessionId")
            lock.release()
            return null
        }

        // Trim trailing resume messages and cost-less api_req_started (Cline pattern)
        savedUiMessages = ResumeHelper.trimResumeMessages(savedUiMessages)

        // Push full ui_messages to webview for rehydration
        onUiMessagesLoaded?.invoke(savedUiMessages)

        // Determine resume ask type
        val resumeAskType = ResumeHelper.determineResumeAskType(savedUiMessages)

        // If session was already completed, display the UI messages but do NOT re-execute
        if (resumeAskType == UiAsk.RESUME_COMPLETED_TASK) {
            log.info("AgentService.resumeSession: session $sessionId was already completed, displaying without re-execution")
            lock.release()
            onComplete(LoopResult.Completed(
                summary = "Task was previously completed.",
                iterations = 0,
                tokensUsed = 0,
            ))
            return scope.launch { /* no-op — session already completed, UI messages already pushed */ }
        }

        // Pop trailing user message if interrupted mid-submission
        val popResult = ResumeHelper.popTrailingUserMessage(savedApiHistory)
        var activeApiHistory = popResult.trimmedHistory

        // Clean trailing empty-assistant pollution left by pre-guard sessions.
        // Mirror: ContextManager.pruneTrailingEmptyAssistants runs below on restoreMessages.
        val preCleanSize = activeApiHistory.size
        activeApiHistory = activeApiHistory.dropLastWhile { msg ->
            msg.role == ApiRole.ASSISTANT && (msg.content.isEmpty() || msg.content.all { it is ContentBlock.Text && it.text.isBlank() })
        }
        val droppedEmpties = preCleanSize - activeApiHistory.size
        if (droppedEmpties > 0) {
            log.info("[AgentService] resume cleanup: dropped $droppedEmpties trailing empty-assistant turn(s) from history")
        }

        // Build task resumption preamble
        val lastActivityTs = savedUiMessages.lastOrNull()?.ts ?: System.currentTimeMillis()
        val agoText = ResumeHelper.formatTimeAgo(lastActivityTs)
        val mode = if (planModeActive.get()) "plan" else "act"
        val cwd = project.basePath ?: ""
        val basePreamble = ResumeHelper.buildTaskResumptionPreamble(mode, agoText, cwd, userText)

        // Task 6.2 — append any background-completion events that landed while the
        // session was idle. BackgroundPersistence accumulates them under
        // sessions/{id}/background/pending_completions.json; we splice them into
        // the preamble so the resumed LLM turn sees them in-context, then consume
        // them so they're not re-delivered on a subsequent resume.
        val preamble = run {
            val persistence = com.workflow.orchestrator.agent.tools.background
                .BackgroundPersistence(agentDir.toPath())
            val pending = runCatching { persistence.loadPendingCompletions(sessionId) }
                .getOrElse { err ->
                    log.warn("[AgentService] loadPendingCompletions failed for $sessionId: ${err.message}", err)
                    emptyList()
                }
            if (pending.isEmpty()) {
                basePreamble
            } else {
                val body = pending.joinToString("\n\n") { ev ->
                    "- ${ev.bgId} (${ev.kind}: \"${ev.label.take(80)}\") — " +
                        "exit ${ev.exitCode}, ${ev.state}, ${ev.runtimeMs}ms\n  " +
                        ev.tailContent.lines().takeLast(5).joinToString("\n  ")
                }
                val completionsPreamble = "\n\n[BACKGROUND COMPLETIONS — delivered on resume]\n" +
                    "While the session was paused, these background processes completed:\n\n" +
                    body + "\n"
                // Consume entries only after we've built the combined preamble — if
                // the join fails or the caller aborts, we'd prefer to redeliver than
                // silently lose them. consumeCompletion is best-effort.
                pending.forEach { ev ->
                    runCatching { persistence.consumeCompletion(sessionId, ev.bgId) }
                        .onFailure { err ->
                            log.warn("[AgentService] consumeCompletion failed for $sessionId/${ev.bgId}: ${err.message}", err)
                        }
                }
                log.info("[AgentService] resume pickup: delivered ${pending.size} persisted background completion(s) for $sessionId")
                basePreamble + completionsPreamble
            }
        }

        // Build new user content: preamble + any popped content
        val newUserContent = buildList {
            add(ContentBlock.Text(preamble))
            addAll(popResult.poppedContent)
        }

        // Create MessageStateHandler with restored state (init-only, before concurrent access)
        val taskText = savedUiMessages.firstOrNull()?.text ?: "Resumed session"
        val handler = MessageStateHandler(baseDir = sessionBaseDir, sessionId = sessionId, taskText = taskText)
        handler.setClineMessages(savedUiMessages)
        handler.setApiConversationHistory(activeApiHistory)

        // TASK_RESUME hook — dispatched within scope.launch (IO coroutine), NOT runBlocking (C5 fix).
        // The hook check and dispatch happen inside the launched coroutine to avoid blocking the calling thread.
        val job = scope.launch(Dispatchers.IO) {
            // Add resume ask to UI messages
            handler.addToClineMessages(UiMessage(
                ts = System.currentTimeMillis(),
                type = UiMessageType.ASK,
                ask = resumeAskType,
                text = "Task was interrupted $agoText. Resuming..."
            ))

            // TASK_RESUME hook — now safe to call suspend functions (we're on IO dispatcher)
            if (hookManager.hasHooks(HookType.TASK_RESUME)) {
                val hookResult = hookManager.dispatch(
                    HookEvent(
                        type = HookType.TASK_RESUME,
                        data = mapOf("sessionId" to sessionId, "messageCount" to savedApiHistory.size)
                    )
                )
                if (hookResult is HookResult.Cancel) {
                    log.info("AgentService: TASK_RESUME hook cancelled resume: ${hookResult.reason}")
                    lock.release()
                    onComplete(LoopResult.Cancelled(iterations = 0))
                    return@launch
                }
            }

            // Add the resumption user message to api history
            handler.addToApiConversationHistory(ApiMessage(
                role = ApiRole.USER,
                content = newUserContent,
                ts = System.currentTimeMillis()
            ))

            // Rebuild ContextManager from api history using lossless conversion
            val agentSettings = AgentSettings.getInstance(project)
            val ctx = ContextManager(maxInputTokens = agentSettings.state.maxInputTokens)
            val systemPrompt = SystemPrompt.build(
                projectName = project.name,
                projectPath = project.basePath ?: "",
                planModeEnabled = planModeActive.get(),
                ideContext = ideContext,
                availableShells = allowedShells,
                availableModels = formatModelsForPrompt(ModelCache.getCached())
            )
            ctx.setSystemPrompt(systemPrompt)

            // Convert ApiMessage list to ChatMessage list (lossless C2 fix)
            // Include the resumption user message so the LLM sees it on the next call
            val chatMessages = activeApiHistory.map { it.toChatMessage() } +
                com.workflow.orchestrator.core.ai.dto.ChatMessage(role = "user", content = preamble)
            ctx.restoreMessages(chatMessages)

            // Belt-and-braces: strip anything that slipped through the resume-path filter
            // (e.g. upstream adapter emits an assistant message that `toChatMessage()` renders
            // as blank).
            ctx.pruneTrailingEmptyAssistants()

            log.info("[Agent] Resuming session: $sessionId (${savedApiHistory.size} api messages, interrupted $agoText)")

            // Execute with the restored context and pre-built MessageStateHandler.
            // We call executeTask which launches its own coroutine — we join it to
            // keep this Job alive until completion so the caller can track/cancel it.
            val innerJob = executeTask(
                task = preamble,
                sessionId = sessionId,
                contextManager = ctx,
                messageStateHandler = handler,
                onStreamChunk = onStreamChunk,
                onToolCall = onToolCall,
                onComplete = { result ->
                    lock.release()
                    onComplete(result)
                },
                onRetry = onRetry,
                onModelSwitch = onModelSwitch,
                onPlanResponse = onPlanResponse,
                onPlanModeToggled = onPlanModeToggled,
                onPlanDiscarded = onPlanDiscarded,
                userInputChannel = userInputChannel,
                approvalGate = approvalGate,
                onCheckpointSaved = onCheckpointSaved,
                onSubagentProgress = onSubagentProgress,
                onTokenUpdate = onTokenUpdate,
                onSessionStats = onSessionStats,
                onDebugLog = onDebugLog,
                onSessionStarted = onSessionStarted,
                steeringQueue = steeringQueue,
                onSteeringDrained = onSteeringDrained,
                sessionApprovalStore = sessionApprovalStore,
                onAwaitingUserInput = onAwaitingUserInput,
                onUserInputReceived = onUserInputReceived,
            )
            innerJob.join()
        }
        return job
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
     * @param onComplete completion callback
     * @return the Job for the continued session, or null if checkpoint not found
     */
    fun revertToCheckpoint(
        sessionId: String,
        checkpointId: String,
        onStreamChunk: (String) -> Unit = {},
        onToolCall: (ToolCallProgress) -> Unit = {},
        onComplete: (LoopResult) -> Unit = {}
    ): Job? {
        // Load checkpoint
        val checkpointMessages = MessageStateHandler.loadCheckpoint(agentDir, sessionId, checkpointId)
        if (checkpointMessages == null) {
            log.warn("AgentService.revertToCheckpoint: checkpoint $checkpointId not found for session $sessionId")
            return null
        }

        // Rebuild ContextManager from checkpoint
        val agentSettings = AgentSettings.getInstance(project)
        val ctx = ContextManager(maxInputTokens = agentSettings.state.maxInputTokens)

        ctx.restoreMessages(checkpointMessages)

        // Delete checkpoints after this one (they're invalidated by reversion)
        MessageStateHandler.deleteCheckpointsAfter(agentDir, sessionId, checkpointId)

        log.info("AgentService.revertToCheckpoint: reverted session $sessionId to checkpoint $checkpointId " +
            "(${checkpointMessages.size} messages)")

        // Continue execution from the checkpoint
        return executeTask(
            task = "Continue from where you left off. The conversation has been reverted to an earlier checkpoint.",
            sessionId = sessionId,
            contextManager = ctx,
            onStreamChunk = onStreamChunk,
            onToolCall = onToolCall,
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
        return MessageStateHandler.listCheckpoints(agentDir, sessionId)
    }

    /**
     * Update the title of an existing session (e.g. after Haiku generates a descriptive title).
     * Updates the global index entry for this session with the new title/task text.
     */
    fun updateSessionTitle(sessionId: String, title: String) {
        // Title is stored in the global sessions.json index — reload, update, save
        val indexFile = File(agentDir, "sessions.json")
        try {
            val items = MessageStateHandler.loadGlobalIndex(agentDir).toMutableList()
            if (items.isEmpty()) return
            val idx = items.indexOfFirst { it.id == sessionId }
            if (idx >= 0) {
                items[idx] = items[idx].copy(task = title.take(200))
                val json = kotlinx.serialization.json.Json { ignoreUnknownKeys = true; prettyPrint = true }
                AtomicFileWriter.write(indexFile, json.encodeToString(
                    kotlinx.serialization.builtins.ListSerializer(HistoryItem.serializer()),
                    items
                ))
            }
        } catch (e: Exception) {
            log.warn("AgentService.updateSessionTitle: failed to update title (non-fatal)", e)
        }
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
            val checkpointMessages = MessageStateHandler.loadCheckpoint(agentDir, sessionId, checkpointId) ?: return emptyList()
            val sessionDir = File(agentDir, "sessions/$sessionId")
            val currentMessages = MessageStateHandler.loadApiHistory(sessionDir)
            // Messages after the checkpoint = those beyond checkpointMessages.size
            val afterCheckpoint = currentMessages.drop(checkpointMessages.size)
            // Extract file paths from tool_use blocks in messages
            val files = mutableSetOf<String>()
            for (msg in afterCheckpoint) {
                msg.content.filterIsInstance<ContentBlock.ToolUse>().forEach { toolUse ->
                    if (toolUse.name in AgentLoop.WRITE_TOOLS) {
                        try {
                            val args = kotlinx.serialization.json.Json.parseToJsonElement(toolUse.input)
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
     * @param onComplete completion callback
     * @return the Job for the new session
     */
    fun startHandoffSession(
        handoffContext: String,
        onStreamChunk: (String) -> Unit = {},
        onToolCall: (ToolCallProgress) -> Unit = {},
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
     *
     * Disposes the current session-scoped Disposable — cascading to every
     * outstanding [RunInvocation] so IDE run/test/coverage state (process
     * handlers, listeners, RunContent descriptors) from the previous session
     * is torn down cleanly before the next session starts. Phase 3 / Task 2.2.
     */
    fun resetForNewChat() {
        cancelCurrentTask()
        planModeActive.set(false)
        registry.resetActiveDeferred()
        ProcessRegistry.killAll()
        activeTask.set(null)
        _outputSpiller = null  // Clear session-scoped spiller so the next session gets a fresh one
        taskStore = null       // Clear session-scoped task store
        sessionRuntime.clear() // Drop per-conversation counters + token/cost running totals
        sessionDisposableHolder.resetSession()
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
