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
import com.workflow.orchestrator.agent.loop.ToolCallProgress
import com.workflow.orchestrator.agent.loop.queue.UnifiedMessageQueue
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
import com.workflow.orchestrator.agent.tools.builtin.MonitorTool
import com.workflow.orchestrator.agent.tools.AgentTool
import com.workflow.orchestrator.agent.tools.ToolOutputSpiller
import com.workflow.orchestrator.agent.tools.ToolRegistry
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
import com.workflow.orchestrator.agent.tools.process.ProcessRegistry
import com.workflow.orchestrator.agent.tools.process.ShellResolver
import com.workflow.orchestrator.agent.tools.subagent.AgentConfigLoader
import com.workflow.orchestrator.agent.tools.subagent.SubagentProgressUpdate
import com.workflow.orchestrator.agent.tools.psi.*
import com.workflow.orchestrator.agent.tools.runtime.*
import com.workflow.orchestrator.agent.tools.vcs.ChangelistShelveTool
import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.ModelCache
import com.workflow.orchestrator.core.ai.OpenAiCompatBrain
import com.workflow.orchestrator.core.ai.SourcegraphChatClient
import com.workflow.orchestrator.core.auth.CredentialStore
import com.workflow.orchestrator.core.model.ServiceType
import com.workflow.orchestrator.core.settings.ConnectionSettings
import com.workflow.orchestrator.core.util.ProjectIdentifier
import com.workflow.orchestrator.document.service.TikaDocumentExtractor
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Central agent service — wires the AgentLoop, ToolRegistry, ContextManager,
 * MessageStateHandler, and LLM brain together. Exposes [executeTask] for the UI layer.
 *
 * IntelliJ project-level service: one instance per open project.
 */
@Service(Service.Level.PROJECT)
class AgentService(
    private val project: Project,
    private val cs: CoroutineScope,
) : Disposable {

    private val log = Logger.getInstance(AgentService::class.java)

    val registry = ToolRegistry()

    /** Atomic reference tracking both the loop and job together to avoid race conditions. */
    private data class ActiveTask(val sessionId: String, val loop: AgentLoop, val job: Job)
    private val activeTask = AtomicReference<ActiveTask?>(null)

    /**
     * Guards [activeTask] and [activeMessageStateHandler] mutations across concurrent
     * [executeTask] calls. Without this lock, two coroutines launched simultaneously
     * could both read a null [activeTask] and both proceed to overwrite each other's
     * references — producing mismatched loop/job/handler state and breaking cancellation.
     *
     * Protocol:
     *   1. Acquire lock at the START of the launched coroutine body.
     *   2. Cancel + clear any pre-existing task.
     *   3. Set [activeTask] and [activeMessageStateHandler] under the lock.
     *   4. Release lock before the long-running loop starts (lock is not held during I/O).
     *   5. Re-acquire lock in `finally` to clear both fields on task exit.
     */
    private val activeTaskMutex = Mutex()

    /**
     * Count of live inbound-delegated sessions (Plan 6 Task 8). Incremented on
     * [startDelegatedSession] entry, decremented in the terminal callback before
     * [DelegationInboundService.stopIfTransientAndIdle] is called so the count
     * reflects the just-ended session. Drives transient-bind teardown.
     */
    private val activeDelegatedSessions = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Background-process completion routing + auto-resume message building, extracted into
     * [com.workflow.orchestrator.agent.tools.background.BackgroundCompletionCoordinator] (Phase 3
     * cut F). Subscribes to [com.workflow.orchestrator.agent.tools.background.BackgroundPool] on
     * construction; registered under this service for disposal in `init`. Routes background
     * completions through the unified queue via [enqueueToSession] — the sole collaborator
     * injected after the unified-queue migration (Task 2.2).
     */
    private val backgroundCompletionCoordinator =
        com.workflow.orchestrator.agent.tools.background.BackgroundCompletionCoordinator(
            project = project,
            enqueue = ::enqueueToSession,
        )

    /**
     * Install a capture callback for background-completion steering messages belonging to
     * [sessionId]. Test-only hook — delegates to [BackgroundCompletionCoordinator].
     */
    @org.jetbrains.annotations.TestOnly
    fun setSteeringCapturerForTest(sessionId: String, capture: (String) -> Unit) =
        backgroundCompletionCoordinator.setSteeringCapturerForTest(sessionId, capture)

    /**
     * Task 6.1 — per-session auto-wake guardrail state (enabled toggle, per-session
     * cap, cooldown). Decision is made against [AgentSettings] from
     * [onBackgroundCompletion] when the loop is idle. Guardrail logic lives in a
     * separate helper so it can be unit-tested without instantiating AgentService.
     */
    private val autoWakeGuards = com.workflow.orchestrator.agent.tools.background.AutoWakeGuardState()

    /**
     * Shared auto-wake orchestrator used by BOTH background-process completion and
     * cross-IDE delegation result/question delivery. Reads [AgentSettings] live, routes
     * through [autoWakeGuards] (so the per-session cap/cooldown is shared), and on WAKE
     * invokes [autoWakeListener] via `invokeLater`. Logic lives in [IdleSessionWaker] so it
     * is unit-testable without constructing AgentService.
     */
    private val idleWaker = com.workflow.orchestrator.agent.tools.background.IdleSessionWaker(
        guards = autoWakeGuards,
        settings = {
            val s = AgentSettings.getInstance(project).state
            com.workflow.orchestrator.agent.tools.background.AutoWakeSettings(
                enabled = s.autoWakeOnBackgroundCompletion,
                cap = s.autoWakeMaxPerSession,
                cooldownMs = s.autoWakeCooldownMs,
            )
        },
        listener = { autoWakeListener },
        // BUG #4 — the live loop's session id (null when idle). The waker only fires an
        // auto-wake-resume when the target IS this session (or nothing is active); otherwise
        // it defers (DEFER_ACTIVE_SESSION) so it can't cancel/reset a different live session.
        activeSessionId = { activeTask.get()?.sessionId },
        invoker = { block -> com.intellij.openapi.application.ApplicationManager.getApplication().invokeLater(block) },
        onLog = { log.info(it) },
    )

    // ── Background tool executor (Task 7) ──────────────────────────────────────
    // AgentService owns both the registry (session-keyed index of live handles) and the
    // executor (runs tool blocks in a SupervisorJob scope, delivers completed results back
    // via deliverBackgroundResult). The executor is disposed alongside AgentService.

    private val backgroundToolRegistry =
        com.workflow.orchestrator.agent.tools.background.BackgroundToolRegistry()

    private val backgroundToolExecutor =
        com.workflow.orchestrator.agent.tools.background.BackgroundToolExecutor(
            parentScope = cs,
            registry = backgroundToolRegistry,
            onDeliver = { handle, result -> deliverBackgroundResult(handle, result) },
        )

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

    /**
     * Task 8 — proactive monitor framework, extracted into `AgentMonitorCoordinator` (Phase 3).
     * Owns the per-session `MonitorManager` map + `MonitorPersistence`; wires the MonitorBridge
     * router + 200 ms flush loop on construction and tears the router down on [dispose]. The
     * side effects it needs are injected: the live-loop lookup ([activeLoopForSession]), the
     * shared [idleWaker], and the agent dir (lazy — `agentDir` is `lateinit`, set in
     * [registerAllTools], so it is passed as a provider). The methods below are thin delegators
     * so existing callers (AgentController, MonitorTool, resumeSession) are unchanged.
     */
    private val monitorCoordinator = com.workflow.orchestrator.agent.monitor.AgentMonitorCoordinator(
        project = project,
        cs = cs,
        agentDirProvider = { agentDir },
        idleWaker = idleWaker,
        activeLoopForSession = ::activeLoopForSession,
        // Task 2.4 — plain enqueue primitive (NO auto-wake); the coordinator's wakeIdle
        // callback fires idleWaker.wake() separately after enqueuing, preserving the
        // per-monitor wake-budget/flood accounting contract.
        enqueueToQueue = { sid, msg -> queueForSession(sid).enqueue(msg) },
        // Phase 3 Task 3.1 — live card delivery (focused sessions only).
        emitCard = ::emitAsyncEventCard,
    )

    /** @see AgentMonitorCoordinator.ensureMonitorManager */
    fun ensureMonitorManager(sessionId: String) = monitorCoordinator.ensureMonitorManager(sessionId)

    /** @see AgentMonitorCoordinator.disposeMonitorsForSession */
    fun disposeMonitorsForSession(sessionId: String) = monitorCoordinator.disposeMonitorsForSession(sessionId)

    /** @see AgentMonitorCoordinator.forgetMonitor */
    fun forgetMonitor(sessionId: String, id: String) = monitorCoordinator.forgetMonitor(sessionId, id)

    /** @see AgentMonitorCoordinator.markMonitorsDormantForSession */
    fun markMonitorsDormantForSession(sessionId: String) = monitorCoordinator.markMonitorsDormantForSession(sessionId)

    /** @see AgentMonitorCoordinator.clearPersistedMonitors */
    fun clearPersistedMonitors(sessionId: String) = monitorCoordinator.clearPersistedMonitors(sessionId)

    // ── Async-event card seam (Phase 3 Task 3.1) ─────────────────────────────────
    // Placed OUTSIDE the sentinel range (delegatedIncomingTaskText..mapLoopResultToDelegationResult)
    // so source-text contract tests in DelegationConversationNarrationTest are not affected.

    @Volatile private var asyncEventCardListener: ((String, com.workflow.orchestrator.agent.session.AsyncEventCardData) -> Unit)? = null

    fun setAsyncEventCardListener(l: (String, com.workflow.orchestrator.agent.session.AsyncEventCardData) -> Unit) {
        asyncEventCardListener = l
    }

    /** Persist a card and notify the controller (live push happens there iff the session is on screen). */
    fun emitAsyncEventCard(sessionId: String, card: com.workflow.orchestrator.agent.session.AsyncEventCardData) {
        asyncEventCardListener?.invoke(sessionId, card)
    }

    /**
     * Document-extraction progress sink wired by [AgentController] after construction.
     *
     * The [SessionDocumentArtifactService] calls this lambda once per page (stage "tables")
     * and once on finalizing. [AgentController] sets it to its [pushDocumentProgress]
     * method so live progress is streamed to the JCEF chat UI without re-constructing
     * the service. Null = no-op (headless tests, pre-controller-init).
     *
     * Thread-safe: volatile field; written from EDT (controller init), read from Dispatchers.IO
     * (extraction coroutine).
     */
    @Volatile
    var onDocumentProgress: ((com.workflow.orchestrator.core.model.DocumentExtractionProgress) -> Unit)? = null

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

        // Initialize hook system (ported from Cline's HookFactory + getAllHooksDirs)
        val hookRunner = HookRunner(workingDir = basePath)
        hookManager = HookManager(hookRunner)
        hookManager.loadFromConfigFile(basePath, project)

        registerAllTools()

        // Task 7: Load dynamic agent configurations and register lifecycle
        val configLoader = AgentConfigLoader.getInstance()
        configLoader.loadFromDisk()
        val configs = configLoader.getAllCachedConfigsWithToolNames()
        if (configs.isNotEmpty()) {
            log.info("[AgentService] Loaded ${configs.size} dynamic agent config(s): ${configs.keys.toList()}")
        }
        // B1: the loader is an APP-wide singleton — its disposal is owned by the app-level
        // AgentConfigLoaderLifecycle service (instantiating it registers platform disposal),
        // NOT by this project-scoped service. A project close must not kill other projects' configs.
        com.intellij.openapi.application.ApplicationManager.getApplication()
            .getService(com.workflow.orchestrator.agent.tools.subagent.AgentConfigLoaderLifecycle::class.java)

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

        // Task 5.4 — BackgroundPool completion routing (steering / persist + auto-wake) is owned
        // by [backgroundCompletionCoordinator] (extracted Phase 3 cut F); register it for disposal
        // so its BackgroundPool listener is torn down with this service.
        com.intellij.openapi.util.Disposer.register(this, backgroundCompletionCoordinator)

        // Plan 1 Task 11 — Re-register (or unregister) delegation_* tools when the
        // outbound setting is toggled at runtime, so the LLM sees them only when the
        // feature is on (§3.3). No restart required.
        project.messageBus.connect().subscribe(
            com.workflow.orchestrator.core.settings.CrossIdeDelegationSettingsListener.TOPIC,
            object : com.workflow.orchestrator.core.settings.CrossIdeDelegationSettingsListener {
                override fun inboundSettingChanged(enabled: Boolean) = Unit
                override fun outboundSettingChanged(enabled: Boolean) {
                    reregisterCrossIdeDelegationTools()
                }
            },
        )

        // Task 8 — the MonitorBridge router, MonitorPool forget-callback, and 200 ms flush loop
        // are wired by [monitorCoordinator]'s constructor (extracted into AgentMonitorCoordinator).
    }

    /**
     * Guarded auto-wake of an idle session — delegates to the testable [idleWaker]
     * ([IdleSessionWaker]). Shared by background-process completion (the
     * `BackgroundCompletionCoordinator`) and cross-IDE delegation result/question
     * delivery ([enqueueNudgeForSession]) so both async-completion paths behave identically.
     */
    private fun autoWakeIdleSession(sessionId: String, syntheticText: String, source: String) =
        idleWaker.wake(sessionId, syntheticText, source)

    /** Return the active loop if it is running for [sessionId], else null. */
    private fun activeLoopForSession(sessionId: String): AgentLoop? {
        val task = activeTask.get() ?: return null
        return if (task.sessionId == sessionId) task.loop else null
    }

    // ── Per-session queue ownership (Task 2.1) ─────────────────────────────────────────────────
    // The single-instance invariant: EVERY path that constructs an AgentLoop for a session AND
    // every producer that enqueues into that session's queue MUST go through queueForSession().
    // Never call UnifiedMessageQueue(...) directly outside this method.

    private val queuePersistence by lazy {
        com.workflow.orchestrator.agent.loop.queue.QueuePersistence(agentDir.toPath())
    }
    private val sessionQueues =
        java.util.concurrent.ConcurrentHashMap<String, com.workflow.orchestrator.agent.loop.queue.UnifiedMessageQueue>()

    /** Return (or lazily create) the per-session [UnifiedMessageQueue] for [sessionId]. */
    fun queueForSession(sessionId: String): com.workflow.orchestrator.agent.loop.queue.UnifiedMessageQueue =
        sessionQueues.getOrPut(sessionId) {
            com.workflow.orchestrator.agent.loop.queue.UnifiedMessageQueue(sessionId, queuePersistence)
        }

    /** Enqueue an async message for [sessionId]; auto-wake the session if it is idle and the source policy allows it. */
    fun enqueueToSession(sessionId: String, msg: com.workflow.orchestrator.agent.loop.queue.QueuedMessage) {
        queueForSession(sessionId).enqueue(msg)
        if (activeLoopForSession(sessionId) == null) {
            val policy = com.workflow.orchestrator.agent.loop.queue.QueueSourceRegistry.policyFor(msg.kind)
            if (policy.autoWakesIdle) autoWakeIdleSession(sessionId, syntheticText = "", source = msg.kind.name)
        }
    }

    /**
     * Inject a cross-IDE delegation result / clarifying-question nudge into [sessionId].
     *
     * Routes through the unified queue as [com.workflow.orchestrator.agent.loop.queue.QueueSourceKind.DELEGATION]:
     * the queue's durable persistence (pending_queue.json) and [enqueueToSession]'s idle
     * auto-wake own the persist-then-wake contract that previously lived here. If the loop
     * is live the item is drained at the next iteration boundary (same as before); if the
     * loop is idle, [DelegationQueuePolicy.autoWakesIdle] triggers [autoWakeIdleSession]
     * and [DelegationQueuePolicy.durable] ensures the item survives a guard-rejected or
     * deferred wake — it is replayed via the queue drain on the next resume.
     *
     * Safe to call from any thread.
     */
    fun enqueueNudgeForSession(sessionId: String, text: String) {
        enqueueToSession(
            sessionId,
            com.workflow.orchestrator.agent.loop.queue.QueuedMessage(
                id = "delg-${System.nanoTime()}",
                kind = com.workflow.orchestrator.agent.loop.queue.QueueSourceKind.DELEGATION,
                body = text,
                timestamp = System.currentTimeMillis(),
                priority = com.workflow.orchestrator.agent.loop.queue.DelegationQueuePolicy.priority,
            ),
        )
    }

    /**
     * Returns the delegation metadata for an active delegated session, or null.
     *
     * Plan 4 spec §5.5.
     */
    fun findDelegationMetadata(sessionId: String): com.workflow.orchestrator.agent.session.DelegationMetadata? =
        perSessionStates[sessionId]?.delegated

    /**
     * Persist a cross-IDE delegation conversation card ([UiSay.DELEGATION_CARD]) into
     * [sessionId]'s ui_messages.json so a reopened delegated session shows the full
     * conversation (incoming task + question/answer pairs + result), not just the agent's
     * work. Legs (b)/(c)/(d) of the IDE-B narration (2026-06-01).
     *
     * Persists via the active [MessageStateHandler] when [sessionId] is the live session
     * (the delegated session always is at narration time). When [flipAskedQuestionId] is
     * set (the ANSWERED leg), also flips the matching persisted ASKED card to resolved so
     * history doesn't render it stuck on "waiting". Fire-and-forget on [cs]; null-safe
     * (no-op) when no active handler matches — tests/headless paths are unaffected.
     */
    fun appendDelegationCardToSession(
        sessionId: String,
        card: com.workflow.orchestrator.agent.session.DelegationCardData,
        flipAskedQuestionId: String? = null,
    ) {
        val handler = activeMessageStateHandler ?: return
        if (handler.sessionId != sessionId) return
        cs.launch(Dispatchers.IO) {
            try {
                if (flipAskedQuestionId != null) {
                    handler.markDelegationQuestionAnswered(flipAskedQuestionId)
                }
                handler.addToClineMessages(
                    com.workflow.orchestrator.agent.session.UiMessage(
                        ts = System.currentTimeMillis(),
                        type = com.workflow.orchestrator.agent.session.UiMessageType.SAY,
                        say = com.workflow.orchestrator.agent.session.UiSay.DELEGATION_CARD,
                        delegationCardData = card,
                    )
                )
            } catch (e: Exception) {
                log.warn("[Agent] appendDelegationCardToSession: persist failed for $sessionId", e)
            }
        }
    }

    /**
     * Persist an [UiSay.ASYNC_EVENT] background/monitor card into [sessionId]'s ui_messages.json
     * (UI-only — never api history). Sibling of [appendDelegationCardToSession]: active-session-only;
     * no-op when no active handler matches (the idle case is covered by resume synthesis).
     */
    fun appendAsyncEventCardToSession(
        sessionId: String,
        card: com.workflow.orchestrator.agent.session.AsyncEventCardData,
    ) {
        val handler = activeMessageStateHandler ?: return
        if (handler.sessionId != sessionId) return
        cs.launch(Dispatchers.IO) {
            try {
                handler.addToClineMessages(
                    com.workflow.orchestrator.agent.session.UiMessage(
                        ts = System.currentTimeMillis(),
                        type = com.workflow.orchestrator.agent.session.UiMessageType.SAY,
                        say = com.workflow.orchestrator.agent.session.UiSay.ASYNC_EVENT,
                        asyncEventData = card,
                    )
                )
            } catch (e: Exception) {
                log.warn("[Agent] appendAsyncEventCardToSession: persist failed for $sessionId", e)
            }
        }
    }

    /** Deliver a finished background tool's result into its session queue (auto-wakes if idle) + UI card. */
    private fun deliverBackgroundResult(
        handle: com.workflow.orchestrator.agent.tools.background.BackgroundToolHandle,
        result: com.workflow.orchestrator.agent.tools.ToolResult,
    ) {
        val nowMs = System.currentTimeMillis()
        val body = "Tool '${handle.toolName}' (id=${handle.toolCallId}) finished:\n${result.content}"
        // Surface the (already grep/spill/truncate-processed) output tail + spill link on the card, so a
        // backgrounded tool that spilled to disk is as inspectable as a background-process card. Card
        // construction lives in AsyncEventCardPresenter alongside the other producers (fromBackground/fromMonitor).
        val card = com.workflow.orchestrator.agent.ui.AsyncEventCardPresenter.fromToolResult(
            toolCallId = handle.toolCallId,
            toolName = handle.toolName,
            isError = result.isError,
            summary = result.summary,
            details = result.content.take(BACKGROUND_CARD_DETAIL_CHARS),
            spillPath = result.spillPath,
            occurredAt = nowMs,
        )
        enqueueToSession(
            handle.sessionId,
            com.workflow.orchestrator.agent.loop.queue.QueuedMessage(
                id = "bg-${handle.toolCallId}-$nowMs",
                kind = com.workflow.orchestrator.agent.loop.queue.QueueSourceKind.BACKGROUND,
                body = body,
                timestamp = nowMs,
                priority = com.workflow.orchestrator.agent.loop.queue.BackgroundQueuePolicy.priority,
                coalesceKey = handle.toolCallId,
                meta = mapOf("card" to com.workflow.orchestrator.agent.ui.AsyncEventCardPresenter.encodeCard(card)),
            ),
        )
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

    /**
     * Session-scoped [com.workflow.orchestrator.agent.session.AttachmentStore].
     *
     * Multimodal-agent Phase 3 — hoisted out of the per-`wrapBrainWithRouter`
     * local so the agent loop's tool-invocation wrapper can install a
     * [com.workflow.orchestrator.agent.tool.SessionAttachmentAccess] coroutine
     * element pointing at the SAME instance the [BrainRouter] holds. Without
     * this single-instance contract, paste-image (BrainRouter side) and
     * tool-image (tool side) would write to different `AttachmentStore`
     * objects — and although both point at the same on-disk directory, the
     * single-instance invariant keeps the "exactly one store per session"
     * design unambiguous and protects against future caching layers.
     *
     * Initialised on the first [wrapBrainWithRouter] call within a task,
     * reused by subsequent recycle/fallback wraps for the same session,
     * cleared in [resetForNewChat] so each chat gets a fresh store.
     */
    @Volatile private var activeAttachmentStore:
        com.workflow.orchestrator.agent.session.AttachmentStore? = null

    /** Model ID of the currently active parent brain. Subagents inherit this as their default model. */
    @Volatile private var currentBrainModelId: String? = null

    // Per-task wiring "live" fields for SpawnAgentTool — populated at the start of
    // executeTask so the SpawnAgentTool's lambdas resolve to the current task's callbacks.
    @Volatile private var liveOnModelSwitch: ((String, String, String) -> Unit)? = null
    @Volatile private var liveBrainFactory: (suspend (String, String?) -> com.workflow.orchestrator.core.ai.LlmBrain)? = null
    @Volatile private var liveCachedFallbackChain: List<String>? = null

    /**
     * Bug 3 — user's pending model change. Set by [requestModelChange] from the
     * AgentController model dropdown handler. Polled by [AgentLoop] at the top of
     * every iteration boundary. The loop applies the change via [brainFactory],
     * resets fallback state, and clears this ref via the `onModelChangeApplied`
     * callback so the same change isn't re-applied. Volatile so the loop coroutine
     * sees writes from the EDT/UI thread without locking.
     */
    private val pendingModelChange = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /**
     * Bug 3 — entry point for AgentController.changeModel. Records a pending model
     * change that will be applied at the next iteration boundary inside the active
     * AgentLoop. Updates [currentBrainModelId] eagerly so the top-bar context
     * indicator reflects the new model immediately.
     *
     * D6 (audit finding agent-runtime:F-8): previously used two separate writes
     * (`pendingModelChange.set` + `currentBrainModelId = modelId`) that could
     * interleave under concurrent calls, leaving `currentBrainModelId` and
     * `pendingModelChange` pointing at different models.
     *
     * Fix: use `updateAndGet` so exactly one caller "wins" for `pendingModelChange`,
     * and update `currentBrainModelId` only after the atomic operation completes —
     * guaranteeing the two fields are always consistent from the caller's perspective.
     * (The loop side reads `pendingModelChange.get()` and then CAS-clears it on apply,
     * which is already correct and requires no change.)
     */
    fun requestModelChange(modelId: String) {
        if (modelId.isBlank()) return
        // D6: updateAndGet atomically replaces whatever is pending with the new model.
        // This is equivalent to set() for a single-slot ref, but makes the intent
        // explicit and provides a hook for a retry loop if the semantics ever evolve.
        pendingModelChange.updateAndGet { modelId }
        currentBrainModelId = modelId
        log.info("[Agent] Model change requested by user: $modelId — will apply on next iteration")
    }

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
        /**
         * Cached default-branch resolution for this session (F-24).
         * `null` → not yet resolved.  `AtomicReference<String?>` with a sentinel
         * distinguishes "resolved to null" from "not resolved yet":
         *   - compareAndSet(null, RESOLVING_SENTINEL) → winner resolves the branch
         *   - Any other non-null value → already resolved (may be empty string for
         *     "resolution failed/no repos").
         *
         * Using an AtomicReference<Optional<String>> avoids the null-vs-absent
         * ambiguity without adding a Optional dependency — we encode absence as
         * the sentinel constant and resolved-null as an empty String.
         */
        val defaultBranch: java.util.concurrent.atomic.AtomicReference<String?> =
            java.util.concurrent.atomic.AtomicReference(null)
    }

    private val sessionRuntime = java.util.concurrent.ConcurrentHashMap<String, SessionRuntimeState>()

    private fun getOrCreateSessionRuntime(sessionId: String): SessionRuntimeState =
        sessionRuntime.computeIfAbsent(sessionId) { SessionRuntimeState() }

    // ── Brain ──────────────────────────────────────────────────────────────

    /**
     * Brain construction + model-selection precedence, extracted into [BrainFactory] (Phase 3
     * cut D). The tool/param name sets are passed as providers so the factory depends only on the
     * registry data it uses. [createBrain] delegates here, keeping its callers unchanged.
     */
    private val brainFactory = com.workflow.orchestrator.agent.brain.BrainFactory(
        project = project,
        toolNames = { registry.allToolNames() },
        paramNames = { com.workflow.orchestrator.agent.tools.background.BackgroundEligibility.withReservedParams(registry.allParamNames()) },
    )

    /**
     * Creates a fresh LLM brain for each task execution.
     * Never cached — always picks up the latest settings (model, URL, token).
     *
     * @see BrainFactory.create
     */
    private suspend fun createBrain(modelOverride: String? = null): LlmBrain =
        brainFactory.create(modelOverride)

    /**
     * Wrap a freshly-created [LlmBrain] in a Phase 6 [BrainRouter] so the
     * agent loop's `brain.chatStream` call site routes through the hybrid
     * dispatch:
     *
     *   - text-only / text+tools     → existing OpenAI-compat path
     *   - image-only                 → SourcegraphCompletionsStreamClient (`/.api/completions/stream`)
     *   - image+tools                → two-step workaround
     *
     * Per-session isolation: [sessionDir] is the active session's directory
     * (`{agent}/sessions/{sid}`). The wrapped [com.workflow.orchestrator.agent.session.AttachmentStore]
     * lives only for the duration of this brain instance — recycle/fallback
     * brains receive a fresh router pointed at the same session dir, never
     * a stale store.
     *
     * [onBadgeFire] runs on the active [MessageStateHandler] when the
     * two-step workaround completes successfully — sets `analyzedImageBadge=true`
     * on the most recent assistant `UiMessage` so the React webview renders
     * the `📷 image analyzed` strip.
     */
    /**
     * Lazily-constructed, session-scoped [com.workflow.orchestrator.core.ai.ModelCatalogService].
     *
     * Phase 6 review followup — promoted from per-`wrapBrainWithRouter` cold
     * instances (Phase 6 first cut) to a single shared instance kicked off
     * once per executeTask boundary so the catalog warms up and subsequent
     * `supportsVision()` lookups by [com.workflow.orchestrator.agent.loop.AgentLoop]'s
     * vision-aware fallback path return authoritative values rather than
     * always-false (the cold-cache result).
     *
     * Lifetime: lives as long as the [AgentService] itself. Multiple
     * `wrapBrainWithRouter` calls within a single session AND across sessions
     * share the same instance. The catalog has its own 1-hour TTL + per-call
     * mutex so re-warm is automatic.
     *
     * Construction is parameterized on [sgUrl] / [tokenProvider] which can
     * change across sessions when the user reconfigures Sourcegraph credentials;
     * to avoid stale binding, we re-key the lazy holder when those change.
     */
    /**
     * Phase 7 followup F-P6FU-2 — keyed solely on `sgUrl`. The `tokenProvider`
     * lambda is no longer part of the key (the original design's `Pair<String,
     * () -> String?>` had a dead lambda half; the lookup only ever compared the
     * URL). Token rotation is covered by the catalog's own 1-hour TTL plus
     * `getCatalog(force = true)`. Tested by [SharedCatalogHolderTest].
     */
    private val sharedCatalogHolder by lazy {
        SharedCatalogHolder(
            scope = cs,
            factory = { sgUrl, tokenProvider ->
                com.workflow.orchestrator.core.ai.ModelCatalogService(
                    baseUrl = sgUrl,
                    tokenProvider = tokenProvider,
                )
            },
            warmUp = { service ->
                // Warm-up fetch: the first image-bearing fallback would otherwise
                // see a cold catalog (`supportsVision()` returning false for every
                // model) and skip the vision filter. runCatching inside the holder
                // swallows propagation; pinned by `SharedCatalogHolderTest`.
                service.getCatalog()
            },
        )
    }

    private fun getOrCreateSharedCatalog(
        sgUrl: String,
        tokenProvider: () -> String?,
    ): com.workflow.orchestrator.core.ai.ModelCatalogService =
        sharedCatalogHolder.get(sgUrl, tokenProvider)

    /**
     * Multimodal-agent Phase 7 — public accessor for the shared catalog. Used
     * by [com.workflow.orchestrator.agent.ui.AgentController.loadModelList] to
     * enrich the dropdown payload (capacity strip, capability badges,
     * deprecated marker). Re-uses the same warm-up infrastructure as the
     * routing layer so the catalog is populated by the time the picker opens.
     */
    fun getSharedModelCatalog(): com.workflow.orchestrator.core.ai.ModelCatalogService? {
        val sgUrl = ConnectionSettings.getInstance().state.sourcegraphUrl.trimEnd('/')
        if (sgUrl.isBlank()) return null
        val store = CredentialStore()
        val tokenProvider: () -> String? = { store.getToken(ServiceType.SOURCEGRAPH) }
        return getOrCreateSharedCatalog(sgUrl, tokenProvider)
    }

    /**
     * v0.83.44 — factory for an AgentController-side [ContextManager] that
     * is correctly wired to the shared model catalog so compaction and
     * utilization track the active model's per-tier `maxInputTokens` from
     * Sourcegraph (e.g. Sonnet → 132K, Sonnet-thinking → 93K). Replaces the
     * pre-v0.83.44 path where AgentController constructed a `ContextManager`
     * with the now-removed `AgentSettings.maxInputTokens` field.
     */
    fun newContextManager(): ContextManager = ContextManager(
        maxInputTokens = ContextManager.FALLBACK_MAX_INPUT_TOKENS,
        modelCatalogService = getSharedModelCatalog(),
        currentModelRef = { currentBrainModelId },
        effectiveContextWindow = effectiveContextWindow,
    )

    @get:JvmName("effectiveContextWindowInternal")
    private val effectiveContextWindow: com.workflow.orchestrator.agent.model.EffectiveContextWindow by lazy {
        com.workflow.orchestrator.agent.model.EffectiveContextWindow(
            windowLookup = { id -> sharedCatalogHolder.peek()?.getContextWindow(id) },
            overrides = {
                com.workflow.orchestrator.agent.settings.AgentSettings
                    .getInstance(project).state.maxTokenOverridesSnapshot()
            },
        )
    }

    fun getEffectiveContextWindow(): com.workflow.orchestrator.agent.model.EffectiveContextWindow = effectiveContextWindow

    /**
     * v0.83.44 — exposes the live `currentBrainModelId` so AgentController's
     * progress-bar callback can read the active model's `maxInputTokens`
     * from the catalog after every API turn, including model fallback swaps.
     */
    fun getCurrentBrainModelId(): String? = currentBrainModelId

    private fun wrapBrainWithRouter(
        brain: LlmBrain,
        sessionDir: java.nio.file.Path,
        sgUrl: String,
        tokenProvider: () -> String?,
        onBadgeFire: () -> Unit,
        catalog: com.workflow.orchestrator.core.ai.ModelCatalogService,
    ): LlmBrain {
        // Multimodal-agent Phase 3 — hoist the AttachmentStore so AgentLoop's
        // tool-invocation wrapper sees the SAME instance the BrainRouter
        // routes through. Reused across recycle/fallback wraps within a
        // session; first call initialises, [resetForNewChat] clears.
        val attachmentStore = activeAttachmentStore
            ?: com.workflow.orchestrator.agent.session.AttachmentStore(sessionDir)
                .also { activeAttachmentStore = it }
        val streamClient = com.workflow.orchestrator.core.ai.SourcegraphCompletionsStreamClient(
            baseUrl = sgUrl,
            tokenProvider = tokenProvider,
            modelCatalogService = catalog,
        )
        return com.workflow.orchestrator.agent.loop.BrainRouter(
            openAiCompatBrain = brain,
            streamClient = streamClient,
            attachmentStore = attachmentStore,
            modelRefProvider = { brain.modelId },
            onAnalyzedImageBadge = onBadgeFire,
            imageEnabledProvider = {
                com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project).state.enableImageInput
            },
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
     * Manually compact the conversation context (user-triggered).
     * Creates a temporary brain for LLM summarization (Stage 3) which is unlocked
     * under `force=true` — see [ContextManager.compact].
     *
     * @param force when true, runs the full 3-stage pipeline (dedup + QUARTER truncation
     *   + LLM summarization) in a single click for a "huge drop" without waiting for
     *   utilization to climb. Re-injects active skill, plan, and tasks post-compaction.
     * @return pair of (tokensBefore, tokensAfter), or null if utilization is too low to compact
     *   (only returned when `force=false`)
     */
    suspend fun compactContext(contextManager: ContextManager, force: Boolean = false): Pair<Int, Int>? {
        if (!force && !contextManager.shouldCompact()) return null

        val brain = createBrain()
        return when (val result = contextManager.compact(brain, hookManager, force = force, iterationsSinceLastUser = Int.MAX_VALUE)) {
            is ContextManager.CompactResult.Compacted -> result.tokensBefore to result.tokensAfter
            is ContextManager.CompactResult.Failed -> {
                log.warn("[AgentService] compactContext failed: ${result.reason}; applying slidingWindow(0.3) as safety net")
                contextManager.slidingWindow(0.3)
                null
            }
            is ContextManager.CompactResult.Cancelled -> {
                log.info("[AgentService] compactContext cancelled by PreCompact hook: ${result.reason}")
                null
            }
            is ContextManager.CompactResult.Skipped -> null
        }
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
        safeRegisterCore { DeleteFileTool() }
        safeRegisterCore { AttemptCompletionTool() }
        safeRegisterCore { TaskReportTool() }
        safeRegisterCore { AskQuestionsTool() }
        safeRegisterCore { PlanModeRespondTool() }
        safeRegisterCore { EnablePlanModeTool() }
        safeRegisterCore { DiscardPlanTool() }
        safeRegisterCore { UseSkillTool() }
        safeRegisterCore { NewTaskTool() }
        safeRegisterCore { RenderArtifactTool() }
        // Web tools — conditionally registered based on user settings.
        // The tool schema never reaches the LLM (and costs tokens) when the toggle is off.
        // reregisterConditionalTools() re-evaluates these at runtime when the user changes
        // the toggles in WebSettingsConfigurable without restarting the IDE.
        val pluginState = project.service<com.workflow.orchestrator.core.settings.PluginSettings>().state
        if (pluginState.enableWebFetch) {
            safeRegisterCore { com.workflow.orchestrator.agent.tools.builtin.WebFetchTool() }
        }
        if (pluginState.enableWebSearch) {
            safeRegisterCore { com.workflow.orchestrator.agent.tools.builtin.WebSearchTool() }
        }

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

        // Local IDE build/import problems — distinct from remote CI (bamboo_*) builds.
        safeRegisterCore { BuildProblemsTool() }

        // Feedback tool — only registered when the feature is enabled so its schema
        // never reaches the LLM (and costs tokens) when the setting is off.
        if (AgentSettings.getInstance(project).state.agentFeedbackEnabled) {
            safeRegisterCore { FeedbackTool(project.name) }
        }

        // tool_search itself is core (the LLM needs it to discover deferred tools)
        safeRegisterCore { ToolSearchTool(registry) }

        // Sub-agent delegation tool — progress callback wired at task level
        safeRegisterCore { SpawnAgentTool(
            brainProvider = { modelOverride -> createBrain(modelOverride ?: currentBrainModelId) },
            toolRegistry = registry,
            project = project,
            configLoader = AgentConfigLoader.getInstance(),
            ideContext = ideContext,
            outputSpiller = { _outputSpiller },
            attachmentStoreProvider = { activeAttachmentStore },
            brainFactory = { liveBrainFactory },
            cachedFallbackChain = { liveCachedFallbackChain },
            onModelSwitch = { from, to, reason -> liveOnModelSwitch?.invoke(from, to, reason) },
            modelCatalogService = { sharedCatalogHolder.peek() },
            parentSessionIdProvider = { activeTask.get()?.sessionId },
            subagentMessageStateHandlerFactory = { parentId, agentId ->
                val basePath = project.basePath ?: System.getProperty("user.home")
                val agentBaseDir = ProjectIdentifier.agentDir(basePath)
                MessageStateHandler(
                    baseDir = agentBaseDir,
                    sessionId = "$parentId/subagents/$agentId",
                    taskText = "sub-agent $agentId",
                )
            },
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
        safeRegisterDeferred("Code Intelligence") { WalkthroughTool() }

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
        safeRegisterDeferred("Utilities") { MonitorTool({ currentSessionId }, cs) }

        // Cross-IDE delegation meta-tool — registered only when outbound is enabled.
        // §3.3 of the spec: when off, the LLM does not see the tool at all.
        // Plan 5: 5 per-action tools consolidated into a single `delegation` meta-tool
        // with an `action` enum, mirroring runtime_exec / jira / sonar / debug_step.
        if (com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project)
                .state.enableOutboundCrossIdeDelegation
        ) {
            safeRegisterDeferred("Delegation") {
                com.workflow.orchestrator.agent.tools.delegation.DelegationTool()
            }
        }

        // File — binary/structured document reading (PDF, DOCX, XLSX, PPTX, RTF, ODT, CSV …)
        // Falls in the deferred tier so the full Tika + POI dependency is only paid when
        // the LLM explicitly searches for "document" or "pdf" or "xlsx" tools.
        //
        // Task 9: DocumentTool now delegates to DocumentArtifactService (single-flight + background
        // extraction + disk caching). The service is wired here with the project's CoroutineScope
        // (cs) so background extraction jobs survive across individual read calls.

        val docExtractor = TikaDocumentExtractor(
            maxCharsProvider = {
                val n = PluginSettings.getInstance(project).state.documentMaxChars
                if (n <= 0) Int.MAX_VALUE else n
            },
        )
        val docArtifactStore = com.workflow.orchestrator.document.service.DocumentArtifactStore(docExtractor)
        val docArtifactService = com.workflow.orchestrator.agent.tools.integration.SessionDocumentArtifactService(
            store = docArtifactStore,
            cs = cs,
            cacheDirProvider = { com.workflow.orchestrator.agent.tools.integration.SessionDocumentArtifactService.defaultCacheDirProvider() },
            jobBudgetMs = PluginSettings.getInstance(project).state.documentExtractionJobTimeoutMs,
            progressSink = { p -> onDocumentProgress?.invoke(p) },
        )
        safeRegisterDeferred("File") {
            DocumentTool(
                artifactService = docArtifactService,
                timeoutMs = PluginSettings.getInstance(project).state.documentExtractionJobTimeoutMs + 60_000L,
            )
        }
        // view_image — registered only when visual support is enabled. When the master flag
        // is off the tool never appears in the LLM's tool list, so it cannot be called.
        // A defence-in-depth body guard inside ViewImageTool.execute() also short-circuits
        // when master is off in case of a hot-reload race (mirrors plan-mode write-tool filtering).
        if (com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project).state.enableImageInput) {
            safeRegisterDeferred("File") { ViewImageTool() }
        }

        // Debug tools (require AgentDebugController)
        // XDebugger-based tools work for both Java/Kotlin and Python debug sessions.
        val hasDebugSupport = ToolRegistrationFilter.shouldRegisterJavaDebugTools(ideContext) ||
            ToolRegistrationFilter.shouldRegisterPythonDebugTools(ideContext)
        if (hasDebugSupport) {
            registerDebugTools()
        } else {
            log.info("Skipping debug tools — neither Java nor Python plugin available")
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

        // view_image — gated on the visual-support master kill switch so that
        // flipping `enableImageInput` in Settings re-registers (or unregisters)
        // the tool without an IDE restart. Without this, the init-time check at
        // registerAllTools() would freeze the registration state for the
        // session and OFF→ON would require a restart to expose view_image.
        val pluginSettings = com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project)
        if (pluginSettings.state.enableImageInput) {
            if (registry.getTool("view_image") == null) safeRegisterDeferred("File") { ViewImageTool() }
        } else {
            if (registry.getTool("view_image") != null) registry.unregisterDeferred("view_image")
        }

        // Web tools (core, not deferred) — re-evaluated when the user toggles
        // enableWebFetch / enableWebSearch in WebSettingsConfigurable without restarting.
        // Uses unregisterCore because web tools are core-tier (always sent to LLM when on).
        if (pluginSettings.state.enableWebFetch) {
            if (!registry.has("web_fetch")) safeRegisterCore { com.workflow.orchestrator.agent.tools.builtin.WebFetchTool() }
        } else {
            if (registry.has("web_fetch")) registry.unregisterCore("web_fetch")
        }
        if (pluginSettings.state.enableWebSearch) {
            if (!registry.has("web_search")) safeRegisterCore { com.workflow.orchestrator.agent.tools.builtin.WebSearchTool() }
        } else {
            if (registry.has("web_search")) registry.unregisterCore("web_search")
        }
    }

    /**
     * Re-evaluates the outbound cross-IDE delegation setting and adds or
     * removes the delegation_* tools accordingly. Called from the
     * [com.workflow.orchestrator.core.settings.CrossIdeDelegationSettingsListener]
     * when the outbound toggle changes, so a flip in Settings takes effect
     * mid-session without an IDE restart.
     *
     * Idempotent: add when absent + enabled, remove when present + disabled.
     *
     * Spec: §3.3 of docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md.
     */
    fun reregisterCrossIdeDelegationTools() {
        val enabled = com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project)
            .state.enableOutboundCrossIdeDelegation
        if (enabled) {
            if (registry.getTool("delegation") == null) {
                safeRegisterDeferred("Delegation") {
                    com.workflow.orchestrator.agent.tools.delegation.DelegationTool()
                }
            }
        } else {
            if (registry.getTool("delegation") != null) registry.unregisterDeferred("delegation")
        }
    }

    /**
     * Activate a deferred tool by name (e.g. "read_document") so it is available
     * to the LLM on the next turn without an explicit tool_search. No-op if the
     * tool is already active or unknown. Used by file-attachment ingestion.
     */
    fun activateDeferredTool(toolName: String) {
        try {
            registry.activateDeferred(toolName)
        } catch (e: Throwable) {
            log.warn("activateDeferredTool($toolName) failed", e)
        }
    }

    /**
     * Computes the snapshot of cross-IDE delegation targets surfaced into the
     * system prompt's Capabilities section. Returns empty when outbound delegation
     * is off (defensive — caller already gates, but avoids accidental socket probes
     * if a future caller forgets). Swallows provider failures so prompt build never
     * fails on a misconfigured environment.
     *
     * Called once per `executeTask` / resume in the surrounding suspend context;
     * the result is captured into the (sync) `systemPromptBuilder` lambda so we
     * don't re-probe sockets on every tool-set change. Stale-within-a-task is
     * acceptable: the prompt explicitly tells the LLM to call
     * `delegation(action="list_targets")` for an authoritative live view.
     *
     * Plan 5.1 — pairs with [SystemPrompt.DelegationTarget].
     */
    private suspend fun computeDelegationTargetsForPrompt(
        project: com.intellij.openapi.project.Project
    ): List<com.workflow.orchestrator.agent.prompt.SystemPrompt.DelegationTarget> {
        val outboundEnabled = com.workflow.orchestrator.core.settings.PluginSettings
            .getInstance(project).state.enableOutboundCrossIdeDelegation
        if (!outboundEnabled) return emptyList()

        val recents = try {
            com.workflow.orchestrator.agent.tools.delegation.DelegationTool
                .defaultRecentsProvider(project)
        } catch (e: Exception) {
            log.warn("[AgentService] delegation recents probe failed for prompt: ${e.message}")
            emptyList()
        }
        // Recents already cover paths in this IDE's recents list; discovered fills in
        // socket-glob hits for paths NOT in recents (e.g. a sibling Toolbox flavor).
        val discovered = try {
            com.workflow.orchestrator.agent.tools.delegation.DelegationTool
                .defaultDiscoveredProvider(project)
        } catch (e: Exception) {
            log.warn("[AgentService] delegation discovery failed for prompt: ${e.message}")
            emptyList()
        }
        // Dedup discovered against recents, drop "missing" paths, map to prompt targets.
        return com.workflow.orchestrator.agent.tools.delegation.DelegationTargetComposer
            .compose(recents, discovered)
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
        // Dialect-drift cleanup: redact any contaminated assistant turns in the
        // persisted history before the retry replays it. Mirrors the empty-turn
        // pattern above. If anything is redacted, MessageStateHandler raises the
        // one-shot dialect-drift flag so the next system prompt picks up the
        // corrective <system-reminder>.
        val dialectRewritten = handler.redactDialectXmlInHistory()
        if (dialectRewritten > 0) {
            log.info("[AgentService] retry cleanup: $dialectRewritten on-disk assistant turn(s) had dialect tool-call XML redacted")
        }
        return diskRemoved + dialectRewritten
    }

    /**
     * Execute a task in the agent loop. Returns a Job for cancellation.
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
        /**
         * Multimodal-agent: image attachments uploaded for this turn. Each ref
         * is appended to the user [ApiMessage] as a [ContentBlock.ImageRef]
         * block. [BrainRouter] detects the resulting image-bearing message and
         * routes through `/.api/completions/stream` (vision-capable path).
         * Bytes for each sha256 must already be on disk under
         * `sessions/{sessionId}/attachments/<sha256>.<ext>` (written by
         * [com.workflow.orchestrator.agent.ui.AttachmentUploadHandler]).
         * Empty list = text-only turn.
         */
        attachments: List<ContentBlock.ImageRef> = emptyList(),
        contextManager: ContextManager? = null,
        onStreamChunk: (String) -> Unit = {},
        onToolCall: (ToolCallProgress) -> Unit = {},
        onComplete: (LoopResult) -> Unit = {},
        /**
         * Callback fired when the LLM presents a plan via plan_mode_respond.
         * Used by the UI to render the plan card. Does NOT exit the loop.
         */
        onPlanResponse: ((planText: String, needsMoreExploration: Boolean, append: Boolean) -> Unit)? = null,
        /**
         * Callback fired when a plan_mode_respond call is truncated mid-emission.
         * The emitted <response> content (the plan prefix) is passed so the caller can
         * pre-populate the accumulator before the LLM's append=true continuation arrives.
         */
        onPlanPartialContent: ((partialContent: String) -> Unit)? = null,
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
        pendingChannelImageRefs: (() -> List<com.workflow.orchestrator.agent.session.ContentBlock.ImageRef>)? = null,
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
         * Bug 5 — UI signal fired when auto-compaction starts and ends. Mirrors the
         * existing manual-compaction UX: webview locks input + shows overlay so the
         * user knows why the loop is paused for the LLM-summary round-trip.
         */
        onCompactionState: ((active: Boolean, phase: String) -> Unit)? = null,
        /**
         * Callback fired when the loop switches to a different model via fallback.
         * Used by the UI to update the model chip and show a status message.
         */
        onModelSwitch: ((fromModel: String, toModel: String, reason: String) -> Unit)? = null,
        /**
         * Optional callback fired synchronously before the agent loop coroutine starts.
         * Provides the session ID so callers can track the session early.
         * Called on the thread that invokes executeTask.
         */
        onSessionStarted: ((sessionId: String) -> Unit)? = null,
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
        onUserInputReceived: ((task: String) -> com.workflow.orchestrator.agent.session.UiMessage?)? = null,
        /**
         * Fired once the loop's [ContextManager] is resolved (the existing one if passed,
         * or a freshly built one). Lets the caller (AgentController) hold the live manager
         * so the NEXT user message is added to the correct context instead of a fresh empty
         * one — the root-cause fix for the post-handoff/post-resume chat wipe.
         */
        onContextManagerReady: ((ContextManager) -> Unit)? = null,
        /** Forwarded to [AgentLoop.onHandoffProposed] — renders the new_task preview card. */
        onHandoffProposed: ((context: String) -> Unit)? = null,
        /**
         * Optional fan-out for the live `edit_file` streaming-diff preview. When
         * provided, the AgentLoop instantiates a
         * [com.workflow.orchestrator.agent.preview.StreamingEditTracker] that pushes
         * partial diffs into the chat panel via this callback. Null in sub-agents
         * and tests (no UI surface). The setting flag
         * [com.workflow.orchestrator.core.settings.PluginSettings.State.enableStreamingEditPreview]
         * is checked inside [AgentLoop.onChunk]; this parameter is the wire that
         * lets AgentController push to the JCEF bridge without a module cycle.
         */
        streamingEditCallback: com.workflow.orchestrator.agent.loop.StreamingEditCallback? = null,
        /**
         * Non-null when this session is started by an incoming cross-IDE delegation
         * ([startDelegatedSession]). Populates [Session.delegated] on the live in-memory
         * session object and writes the metadata into the sessions.json HistoryItem via
         * [MessageStateHandler.updateSessionDelegationMetadata] so the session-list UI
         * can show a delegation badge without a secondary file read.
         *
         * F2 + F6: spec §9.1.
         */
        delegationMetadata: com.workflow.orchestrator.agent.session.DelegationMetadata? = null,
    ): Job {
        val sid = sessionId ?: UUID.randomUUID().toString()

        // Seed plan-mode from the persisted HistoryItem so a session that was
        // paused in plan mode resumes in plan mode (F1/F2 fix).
        val persistedPlanMode =
            MessageStateHandler.findHistoryItem(agentDir, sid)?.planModeEnabled ?: false

        var session = Session(
            id = sid,
            title = task.take(100),
            status = SessionStatus.ACTIVE,
            planModeEnabled = persistedPlanMode,
            // F6: populate delegated on the live in-memory Session so any code that
            // reads currentSession?.delegated sees the metadata immediately.
            delegated = delegationMetadata,
        )
        currentSessionId = session.id
        sessionStateFor(session.id)
            .planModeActive.set(session.planModeEnabled)
        onSessionStarted?.invoke(sid)

        val sessionMetrics = SessionMetrics()
        val sessionStartTime = System.currentTimeMillis()

        val job = cs.launch(Dispatchers.IO) {
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
                val rawBrain = createBrain()
                // Wire API debug dumps — save request/response JSON per session
                val basePath = project.basePath ?: System.getProperty("user.home")
                // Multimodal-agent Phase 6 — wrap in BrainRouter for hybrid routing
                // (text-only / image-only / image+tools two-step). The wrapped brain
                // is what the AgentLoop sees; routing is invisible to text-only
                // turns, which dominate today's traffic.
                val sessionDirPath = java.io.File(
                    ProjectIdentifier.agentDir(basePath),
                    "sessions/$sid"
                ).toPath()
                java.nio.file.Files.createDirectories(sessionDirPath)
                val sgUrlForRouter = ConnectionSettings.getInstance().state.sourcegraphUrl.trimEnd('/')
                val routerCredentialStore = CredentialStore()
                val tokenProviderForRouter: () -> String? =
                    { routerCredentialStore.getToken(ServiceType.SOURCEGRAPH) }
                // Phase 6 review followup — single shared catalog, warmed up at
                // construction so AgentLoop's vision-aware fallback (Task 6.3a)
                // sees authoritative supportsVision values on the first
                // image-bearing fallback, not always-false from cold cache.
                val sharedCatalog = getOrCreateSharedCatalog(sgUrlForRouter, tokenProviderForRouter)
                // Phase 7 followup F-P6-3 — recency guard for the analyzedImageBadge
                // flag. `indexOfLast { it.say == UiSay.TEXT }` alone has no recency
                // floor: if the badge fires AFTER `BrainRouter` step-2 completes but
                // BEFORE the new turn's assistant UiMessage has been written, it can
                // (rarely) flag the PREVIOUS turn's assistant text. We snapshot the
                // turn-start timestamp here and use it as a floor — only flag a
                // message whose `ts >= turnStartTs`.
                val turnStartTs = System.currentTimeMillis()
                val onBadgeFire: () -> Unit = onBadgeFire@{
                    // The most recent assistant UiMessage carries the assistant text;
                    // flag it with analyzedImageBadge=true so the React webview renders
                    // the `📷 image analyzed` strip. Best-effort: if no assistant
                    // UiMessage exists yet (rare race), this is a no-op.
                    val msgs = activeMessageStateHandler?.getClineMessages() ?: return@onBadgeFire
                    val lastIdx = msgs.indexOfLast { it.say == UiSay.TEXT && it.ts >= turnStartTs }
                    if (lastIdx < 0) return@onBadgeFire
                    val updated = msgs[lastIdx].copy(analyzedImageBadge = true)
                    cs.launch(Dispatchers.IO) {
                        runCatching {
                            activeMessageStateHandler?.updateClineMessage(lastIdx, updated)
                        }.onFailure { e ->
                            log.warn("[Agent:Phase6] failed to flag analyzedImageBadge on UiMessage", e)
                        }
                    }
                }
                val brain = wrapBrainWithRouter(
                    brain = rawBrain,
                    sessionDir = sessionDirPath,
                    sgUrl = sgUrlForRouter,
                    tokenProvider = tokenProviderForRouter,
                    onBadgeFire = onBadgeFire,
                    catalog = sharedCatalog,
                )
                brainRef = brain
                currentBrainModelId = brain.modelId
                log.info("[Agent] Task started: sessionId=$sid, model=${brain.modelId}")
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
                val agentSettings = AgentSettings.getInstance(project)
                // API-debug request/response dumps are OFF by default. Each request dump is a full
                // copy of the request body (200-280 KB on a long session) written to
                // sessions/{id}/api-debug/ on EVERY call, which contends with the IDE on
                // antivirus-scanned / OneDrive-synced disks. `apiDebugDir` is the SINGLE gate —
                // null disables dumping at the brain (initial + recycled), the recycle breadcrumb,
                // and the sub-agent path alike. Opt in via AI Agent > Advanced > Diagnostics.
                val apiDebugDir: java.io.File? =
                    sessionDebugDir.takeIf { agentSettings.state.writeApiDebugDumps }
                // Wire api-debug + shared call counter on the underlying brain.
                // After Phase 6, `brain` is a BrainRouter wrapping OpenAiCompatBrain,
                // so we use `rawBrain` here (held above the wrap-call) to reach the
                // OpenAiCompatBrain instance directly.
                if (rawBrain is OpenAiCompatBrain) {
                    rawBrain.setApiDebugDir(apiDebugDir)
                    rawBrain.setSharedApiCallCounter(sharedApiCounter)
                    if (apiDebugDir != null) {
                        log.debug("[Agent] API debug dir: ${apiDebugDir.absolutePath}/api-debug/")
                    }
                }

                // Network error strategy — the L2-tier-escalation gating decision is the pure
                // [com.workflow.orchestrator.agent.loop.NetworkRecoveryPolicy] (Phase 3 cut B).
                val strategy = com.workflow.orchestrator.agent.loop.NetworkRecoveryPolicy
                    .effectiveStrategy(agentSettings.state.networkErrorStrategy)

                // Bug 2 — when strategy is "none", the user explicitly opted out of any
                // automatic model switching. Disable L2 tier escalation so the loop never
                // silently changes the user's chosen model. Same-tier brain recycling
                // (fresh OkHttp pool on dead-socket) is unaffected — it preserves the model.
                val fallbackResolution = com.workflow.orchestrator.agent.loop.NetworkRecoveryPolicy
                    .resolveFallbackChain(strategy) {
                        ModelCache.buildFallbackChain(ModelCache.getCached())
                    }
                when (fallbackResolution.reason) {
                    com.workflow.orchestrator.agent.loop.NetworkRecoveryPolicy
                        .FallbackChainResolution.Reason.STRATEGY_NONE ->
                        log.info("[Agent] Network error strategy = 'none' — L2 tier escalation disabled")
                    com.workflow.orchestrator.agent.loop.NetworkRecoveryPolicy
                        .FallbackChainResolution.Reason.CHAIN_AVAILABLE -> {
                        val tiers = fallbackResolution.chain?.map { it.substringAfterLast("::") }
                        log.info("[Agent] Fallback chain available: $tiers")
                    }
                    com.workflow.orchestrator.agent.loop.NetworkRecoveryPolicy
                        .FallbackChainResolution.Reason.CHAIN_TOO_SHORT ->
                        log.info("[Agent] Fallback chain has ≤1 model — L2 tier escalation disabled")
                }
                val cachedFallbackChain: List<String>? = fallbackResolution.chain

                val compactOnTimeoutExhaustion =
                    com.workflow.orchestrator.agent.loop.NetworkRecoveryPolicy.compactOnTimeoutExhaustion(strategy)

                // Counter for recycle marker filenames (recycle-001.txt, recycle-002.txt, ...).
                // Increments every time the factory is invoked, regardless of reason
                // (model fallback OR same-tier recycle).
                val recycleMarkerCounter = java.util.concurrent.atomic.AtomicInteger(0)

                // brainFactory is now ALWAYS built. Used by AgentLoop for both:
                //   - L2 tier escalation (when an alternate tier is available)
                //   - Same-tier brain recycling on stream/timeout errors (always available now,
                //     fixes broken socket / dead ConnectionPool / stale activeCall ref)
                val fbConnections = ConnectionSettings.getInstance()
                val fbUrl = fbConnections.state.sourcegraphUrl.trimEnd('/')
                val fbCredentialStore = CredentialStore()
                val fbTokenProvider = { fbCredentialStore.getToken(ServiceType.SOURCEGRAPH) }
                val brainFactory: suspend (String, String?) -> LlmBrain = { modelId: String, reason: String? ->
                    val currentToolNames = registry.allToolNames()
                    val currentParamNames = com.workflow.orchestrator.agent.tools.background.BackgroundEligibility.withReservedParams(registry.allParamNames())
                    val newBrain = OpenAiCompatBrain(
                        sourcegraphUrl = fbUrl,
                        tokenProvider = fbTokenProvider,
                        model = modelId,
                        toolNameSet = currentToolNames,
                        paramNameSet = currentParamNames
                    ).also { b ->
                        b.setApiDebugDir(apiDebugDir)
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
                        // Only write the breadcrumb when api-debug dumps are on — otherwise there is
                        // no api-debug/ directory to annotate (apiDebugDir == sessionDebugDir here).
                        apiDebugDir?.let { dbgDir ->
                            val markerFile = java.io.File(
                                dbgDir,
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
                        }
                        log.info("[Agent] Brain recycled (#$recycleIdx) — model=$modelId, after call #$lastCallNum, reason: ${reason?.take(120)}")
                    } catch (e: Exception) {
                        log.debug("[Agent] Failed to write recycle marker: ${e.message}")
                    }

                    // Multimodal-agent Phase 6 — wrap recycled/fallback brain too,
                    // so image+tools turns continue to route correctly through the
                    // hybrid path even after a recycle/fallback. Per-session
                    // AttachmentStore is reconstructed against the SAME session dir
                    // (no cross-session leakage). Shared catalog ensures the
                    // recycled brain reads the SAME warmed-up vision-capability data
                    // (no per-recycle cold-fetch cost).
                    wrapBrainWithRouter(
                        brain = newBrain,
                        sessionDir = sessionDirPath,
                        sgUrl = fbUrl,
                        tokenProvider = fbTokenProvider,
                        onBadgeFire = onBadgeFire,
                        catalog = sharedCatalog,
                    )
                }

                // Build context manager — v0.83.44: catalog-driven max-input-tokens.
                // The legacy `agentSettings.state.maxInputTokens` field was removed;
                // budget now follows the active model via Sourcegraph's catalog.
                val ctx = contextManager ?: ContextManager(
                    maxInputTokens = ContextManager.FALLBACK_MAX_INPUT_TOKENS,
                    modelCatalogService = sharedCatalogHolder.peek(),
                    currentModelRef = { currentBrainModelId },
                    effectiveContextWindow = effectiveContextWindow,
                )
                // Report the resolved manager so the controller can hold it across turns.
                onContextManagerReady?.invoke(ctx)

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

                // MEMORY INDEX: Load per-project MEMORY.md for injection into Section 10.
                // Ensure the memory directory exists so the LLM's first create_file into it
                // (and edit_file against a not-yet-created MEMORY.md) cannot NoSuchFileException.
                // Seed an empty MEMORY.md when missing so `MemoryIndex.load` returns non-null
                // on first session — the prompt's `Contents of <path>:` block (Section 10b)
                // is the only place the LLM sees the memory directory's absolute path, and
                // that block is gated on the index file existing.
                val memoryDirPath = java.io.File(agentDir, "memory").toPath()
                try {
                    java.nio.file.Files.createDirectories(memoryDirPath)
                } catch (_: Exception) {
                    // Non-fatal: if we cannot create the dir (permission, read-only FS),
                    // memory just stays unavailable for the session.
                }
                com.workflow.orchestrator.agent.memory.MemoryIndex.seedIfMissing(memoryDirPath)
                val memoryIndexPath = memoryDirPath.resolve("MEMORY.md").toString()

                // Forward-reference holder for [messageState] (declared further down in
                // this function): the systemPromptBuilder lambda is defined before
                // `messageState` and `ctx` are created, but the lambda is only INVOKED
                // after both exist. We populate this holder once `messageState` is built;
                // until then `consumeDialectDriftFlag()` returns false via the elvis.
                var messageStateRef: MessageStateHandler? = null

                // Snapshot of cross-IDE delegation targets, captured once per executeTask in
                // the surrounding suspend context. Stays stable across the lambda's many
                // rebuilds (tool-set changes) so we don't re-probe sockets every iteration.
                // The list goes stale if a new IDE comes online mid-task — acceptable because
                // the prompt explicitly tells the LLM to call `delegation(action="list_targets")`
                // for an authoritative live view.
                val delegationTargetsSnapshot: List<SystemPrompt.DelegationTarget> =
                    computeDelegationTargetsForPrompt(project)

                // Build system prompt — XML tool definitions added dynamically below.
                // MEMORY.md is RE-READ on every rebuild (not captured at session start) so
                // mid-session saves via `edit_file MEMORY.md` are visible to the LLM on its
                // very next iteration. The lambda runs whenever the tool set changes; the
                // file read is small (≤200 lines) and unconditional misses are tolerated.
                val researchDirPath = com.workflow.orchestrator.core.util.ProjectIdentifier.researchDir(projectPath).toPath()
                val systemPromptBuilder = { toolDefsMarkdown: String? ->
                    val freshMemoryIndex = com.workflow.orchestrator.agent.memory.MemoryIndex.load(memoryDirPath)
                    val freshResearchIndex = com.workflow.orchestrator.agent.research.ResearchIndex.load(researchDirPath)
                    // hasWebTools is re-evaluated on every prompt rebuild so a settings toggle
                    // mid-session (via reregisterConditionalTools) is reflected immediately.
                    val hasWebTools = registry.has("web_fetch") || registry.has("web_search")
                    // D6 (audit finding agent-runtime:F-9): snapshot the dialectDriftFlag ONCE
                    // per prompt build into a local before passing it to SystemPrompt.build().
                    // consumeDialectDriftFlag() is already atomic (AtomicBoolean.getAndSet), but
                    // the explicit local variable makes it impossible to accidentally read it twice
                    // if SystemPrompt.build's parameter list is ever refactored.
                    // NOTE: must remain the LAST local before SystemPrompt.build() — pinned by
                    // AgentServiceModelChangeCasTest (snapshot-within-300-chars invariant).
                    val dialectDriftSnapshot = messageStateRef?.consumeDialectDriftFlag() ?: false
                    SystemPrompt.build(
                        projectName = projectName,
                        projectPath = projectPath,
                        planModeEnabled = isPlanModeActive(),
                        // perf/token-context-optimization rank 3: drop the full Act-vs-Plan
                        // section in act mode (~1.1K tokens), leaving a one-line breadcrumb.
                        // Re-included automatically when the user switches to plan mode.
                        includePlanModeSection = isPlanModeActive(),
                        includePlanModeHintWhenGated = true,
                        additionalContext = projectInstructions,
                        availableSkills = availableSkills,
                        activeSkillContent = ctx.getActiveSkill(),
                        taskProgress = ctx.renderTaskProgressMarkdown(),
                        deferredToolCatalog = deferredCatalog,
                        toolDefinitionsMarkdown = toolDefsMarkdown,
                        memoryIndex = freshMemoryIndex,
                        memoryIndexPath = memoryIndexPath,
                        researchIndex = freshResearchIndex,
                        researchIndexPath = researchDirPath.toString(),
                        ideContext = ideContext,
                        availableShells = allowedShells,
                        availableModels = formatModelsForPrompt(ModelCache.getCached()),
                        hasWebTools = hasWebTools,
                        // One-shot — fires once per drift detection, then resets.
                        // True when the write-time guard rejected an assistant turn OR
                        // `redactDialectXmlInHistory` rewrote one at resume / retry.
                        dialectDriftDetected = dialectDriftSnapshot,
                        delegationOutboundEnabled = com.workflow.orchestrator.core.settings.PluginSettings
                            .getInstance(project).state.enableOutboundCrossIdeDelegation,
                        delegationTargets = delegationTargetsSnapshot,
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
                    val isPlanMode = isPlanModeActive()
                    // #5 — a delegated session is ACT-ONLY (mirrors sub-agents'
                    // includePlanModeSection=false). There is no local human to approve an
                    // interactive plan over a remotely-driven session, so the plan tools are
                    // filtered out entirely: the LLM never sees enable_plan_mode or
                    // plan_mode_respond, and the plan callbacks in the SessionUiCallbacks bundle are
                    // simply never exercised on the delegated path. The delegation marker is stamped
                    // on the per-session state before the loop starts (startDelegatedSession /
                    // resumeDelegatedSession), so it is set on the very first provider call.
                    val isDelegatedSession = currentSessionState()?.delegated != null
                    // Tool-visibility predicate (use_skill gating + act-only delegated + plan/act
                    // split) is the pure com.workflow.orchestrator.agent.tools.ToolDefinitionFilter
                    // (Phase 3 cut B, incision 3).
                    val defs = registry.getActiveTools().values
                        .filter { tool ->
                            com.workflow.orchestrator.agent.tools.ToolDefinitionFilter.shouldInclude(
                                toolName = tool.name,
                                isPlanMode = isPlanMode,
                                isDelegatedSession = isDelegatedSession,
                                hasSkills = hasSkills,
                                writeToolNames = writeToolNames,
                            )
                        }
                        .map { AgentTool.injectOutputParams(it.toToolDefinition()) }

                    // Update system prompt when tool set changes (plan mode switch, deferred tool load)
                    val defsHash = defs.map { it.function.name }.hashCode()
                    if (defsHash != lastXmlToolDefsHash) {
                        lastXmlToolDefsHash = defsHash
                        val markdown = com.workflow.orchestrator.core.ai.ToolPromptBuilder.build(defs)
                        val fullPrompt = systemPromptBuilder(markdown)
                        // RANK-1 MEASUREMENT (perf/token-context-optimization): size the
                        // first-message baseline so prompt-trim work targets real numbers.
                        // Tool-defs (§6c) is the largest unmeasured line item; the full prompt
                        // is the figure the user sees as ~45-50K. ~4 chars/token rough estimate.
                        // Fires on every rebuild: first message, plan-mode toggle, deferred load.
                        log.info(
                            "[PromptSize] tools=${defs.size} planMode=$isPlanMode " +
                                "toolDefs=${markdown.length}ch(~${markdown.length / 4}tok) " +
                                "systemPrompt=${fullPrompt.length}ch(~${fullPrompt.length / 4}tok)"
                        )
                        // RANK-4 targeting: per-tool §6c size, heaviest first, so the
                        // description trim targets the tools that actually dominate.
                        val perTool = com.workflow.orchestrator.core.ai.ToolPromptBuilder.perToolSizes(defs)
                        log.info(
                            "[PromptSizeByTool] " + perTool.joinToString(" ") { (n, c) -> "$n=${c}ch" }
                        )
                        ctx.setSystemPrompt(fullPrompt)
                    }

                    defs
                }

                // Wire sub-agent progress callback and settings for this task execution.
                // All parent-session callbacks — approvalGate, hookManager, sessionMetrics,
                // fileLogger, onDebugLog — are forwarded so sub-agents honour
                // the same approval UX, hooks, and observability as the main agent.
                // Without this plumbing, delegating a write tool to a sub-agent would
                // bypass the modal; delegating any tool would leave its PRE/POST_TOOL_USE
                // hooks silent and its timings off the scorecard.
                val spawnAgentTool = registry.get("agent") as? com.workflow.orchestrator.agent.tools.builtin.SpawnAgentTool
                if (spawnAgentTool != null) {
                    // v0.83.44 — sub-agent context budget follows the parent's
                    // active model via the catalog; falls back to the same
                    // FALLBACK_MAX_INPUT_TOKENS the orchestrator uses on cold cache.
                    spawnAgentTool.contextBudget = ctx.effectiveMaxInputTokens()
                    spawnAgentTool.maxOutputTokens = agentSettings.state.maxOutputTokens
                    // Gated: null when api-debug dumps are off, so sub-agents write nothing to disk.
                    spawnAgentTool.sessionDebugDir = apiDebugDir
                    spawnAgentTool.toolExecutionMode = agentSettings.state.toolExecutionMode ?: "accumulate"
                    spawnAgentTool.approvalGate = approvalGate
                    spawnAgentTool.sessionApprovalStore = sessionApprovalStore
                    spawnAgentTool.hookManager = hookManager
                    spawnAgentTool.sessionMetrics = sessionMetrics
                    spawnAgentTool.fileLogger = fileLogger
                    spawnAgentTool.onDebugLog = onDebugLog
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
                // Captured from inside the `run { ... }` block if a new user-message UiMessage
                // is created there. Stays 0L when [messageStateHandler] is pre-built (resume path).
                // Used to key the per-user-message checkpoint dir and threaded into AgentLoop
                // via [currentUserMessageTsProvider].
                var userMessageTs: Long = 0L
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
                    // Multimodal: prepend Text(task), then append one ImageRef per attachment.
                    // BrainRouter.hasImageParts() iterates content blocks looking for
                    // ContentBlock.ImageRef and routes the call to the vision endpoint.
                    val userContent = buildList<ContentBlock> {
                        add(ContentBlock.Text(task))
                        addAll(attachments)
                    }
                    if (attachments.isNotEmpty()) {
                        log.info("[Agent] User turn carries ${attachments.size} image attachment(s): " +
                            attachments.joinToString(",") { "${it.sha256.take(12)}…/${it.mime}/${it.size}B" })
                    }
                    handler.addToApiConversationHistory(ApiMessage(
                        role = ApiRole.USER,
                        content = userContent,
                        ts = System.currentTimeMillis()
                    ))
                    val uiMsg = uiMessageOverride ?: UiMessage(
                        ts = System.currentTimeMillis(),
                        type = UiMessageType.SAY,
                        say = UiSay.USER_MESSAGE,
                        text = task,
                        attachments = attachments.takeIf { it.isNotEmpty() },
                    )
                    handler.addToClineMessages(uiMsg)
                    userMessageTs = uiMsg.ts
                    handler
                }

                // F2: write delegation metadata into the sessions.json HistoryItem so
                // the session-list UI can show a badge without a secondary file read.
                // This call is a no-op (skipped) for non-delegated sessions.
                if (delegationMetadata != null) {
                    messageState.updateSessionDelegationMetadata(delegationMetadata)
                }

                // Per-user-message file checkpoint store. Captures pre-edit bytes of files
                // touched during this turn into `sessions/{sid}/checkpoints/msg-{ts}/files/`.
                // Drives the bottom-bar aggregate diff and the per-message time-travel revert.
                val checkpointStore = com.workflow.orchestrator.agent.checkpoint.SessionCheckpointStore(
                    sessionDir = java.io.File(sessionBaseDir, "sessions/$sid")
                )
                if (userMessageTs > 0L) {
                    checkpointStore.beginUserMessage(messageTs = userMessageTs, userText = task)
                }

                // Seal the handler against further init-only mutations, then expose it
                // for concurrent access. markPublished() must be called before
                // activeMessageStateHandler is visible to other coroutines (F-17 fix).
                messageState.markPublished()
                // Expose active handler so AgentController.dismissPlan() can rewrite history.
                activeMessageStateHandler = messageState
                // Wire the forward-reference holder so the systemPromptBuilder lambda
                // (defined above before messageState existed) can consume the
                // dialect-drift flag on each rebuild.
                messageStateRef = messageState

                // Initialise session-scoped task store alongside the message state handler.
                // loadFromDisk() runs inside a coroutine scope so it is safe to call here.
                // Create and fully initialise before field assignment so observers never
                // see a partially-initialised store (closes the init-race window).
                val store = TaskStore(baseDir = sessionBaseDir, sessionId = sid)
                store.loadFromDisk()
                taskStore = store

                // Attach TaskStore to ContextManager so renderTaskProgressMarkdown() reads live task state.
                ctx.attachTaskStore(store)

                // Wire session-documents provider so the manifest of exact attachment/download
                // paths is re-injected after compaction. Scanned from the session directory
                // (ground truth) every time compaction runs — no stale registry needed.
                ctx.setSessionDocumentsProvider {
                    com.workflow.orchestrator.agent.session.DocumentManifestScanner.scan(sessionDirPath)
                }

                // Wire onHistoryOverwrite callback so compaction persists truncated history.
                // Ported from Cline's conversationHistoryDeletedRange pattern: after context
                // truncation/summarization, the modified api_conversation_history is overwritten.
                ctx.onHistoryOverwrite = { msgs, _ ->
                    messageState.overwriteApiConversationHistory(
                        msgs.map { it.toApiMessage() }
                    )
                }

                // Resolve default target branch asynchronously — DefaultBranchResolver.resolve()
                // is suspend. environmentDetailsProvider is now also a suspend lambda (D8b),
                // but resolution is fire-and-forget so the provider doesn't block on git per
                // invocation.
                //
                // F-24: Cache the result at session level (SessionRuntimeState.defaultBranch)
                // so repeated executeTask calls within the same session (multi-turn
                // conversations) do NOT re-issue a 3 s git probe on every user message.
                // The underlying DefaultBranchResolver has its own IntelliJ-platform cache, but
                // we still paid 3 s × N turns in the worst case when the cache is cold or the
                // resolver decides to re-probe.  The session-level cache is populated once per
                // session (first turn) and reused verbatim on all subsequent turns.
                val resolvedDefaultBranch = runtime.defaultBranch
                if (resolvedDefaultBranch.get() == null) {
                    launch(Dispatchers.IO) {
                        try {
                            val repos = git4idea.repo.GitRepositoryManager.getInstance(project).repositories
                            val primary = repos.firstOrNull() ?: return@launch
                            val branch = kotlinx.coroutines.withTimeoutOrNull(3000L) {
                                com.workflow.orchestrator.core.util.DefaultBranchResolver
                                    .getInstance(project).resolve(primary)
                            }
                            // Store empty string as "resolved but no branch found" sentinel so
                            // subsequent turns skip the launch entirely.
                            resolvedDefaultBranch.compareAndSet(null, branch ?: "")
                        } catch (_: Exception) {
                            resolvedDefaultBranch.compareAndSet(null, "") // mark resolved-empty
                        }
                    }
                }

                // Wire live fields for SpawnAgentTool — keeps the per-task callbacks
                // accessible to LLM-spawned sub-agents constructed by SpawnAgentTool.
                // NOTE: onRetry/onCompactionState are intentionally NOT forwarded — a sub-agent's
                // retry/compaction routes to its own card (SubagentRunner→onProgress), not the
                // orchestrator's main chat. Forwarding them here was the leak.
                liveOnModelSwitch = onModelSwitch
                liveBrainFactory = brainFactory
                liveCachedFallbackChain = cachedFallbackChain

                val loop = AgentLoop(
                    brain = brain,
                    tools = tools,
                    toolDefinitions = toolDefs,
                    contextManager = ctx,
                    project = project,
                    onStreamChunk = onStreamChunk,
                    onToolCall = onToolCall,
                    planMode = isPlanModeActive(),
                    // LIVE plan-mode signal so the write-tool execution guard tracks Approve→ACT
                    // mid-loop (the snapshot above goes stale once the user switches mode).
                    planModeProvider = { isPlanModeActive() },
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
                    onHandoffProposed = onHandoffProposed,
                    onPlanPartialContent = onPlanPartialContent,
                    onPlanModeToggle = { enabled ->
                        setPlanModeActive(enabled)
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
                    pendingChannelImageRefs = pendingChannelImageRefs,
                    approvalGate = approvalGate,
                    sessionApprovalStore = sessionApprovalStore,
                    onDebugLog = onDebugLog,
                    onRetry = onRetry,
                    onCompactionState = onCompactionState,
                    fileLogger = fileLogger,
                    sessionMetrics = sessionMetrics,
                    environmentDetailsProvider = {
                        val pluginSettings = PluginSettings.getInstance(project)
                        // Per-repo branch enumeration — flat list, no "primary" distinction.
                        // The agent picks the right repo per-call from user-action signals
                        // (checked changes, focused PR, file-arg). environment_details only
                        // enumerates what is currently checked out where.
                        val repoBranches: List<Pair<String, String>> = try {
                            val allRepos = git4idea.repo.GitRepositoryManager.getInstance(project).repositories
                            val basePath = project.basePath
                            fun labelFor(repo: git4idea.repo.GitRepository): String {
                                val root = repo.root.path
                                if (basePath != null && root.startsWith(basePath)) {
                                    val rel = root.removePrefix(basePath).trimStart('/')
                                    return if (rel.isBlank()) repo.root.name else rel
                                }
                                return repo.root.name
                            }
                            allRepos.mapNotNull { repo ->
                                val b = repo.currentBranchName ?: return@mapNotNull null
                                labelFor(repo) to b
                            }
                        } catch (_: Exception) { emptyList() }
                        EnvironmentDetailsBuilder.build(
                            project = project,
                            planModeEnabled = isPlanModeActive(),
                            contextManager = ctx,
                            activeTicketId = pluginSettings.state.activeTicketId,
                            activeTicketSummary = pluginSettings.state.activeTicketSummary,
                            // Convert empty-string sentinel back to null for the caller.
                            defaultTargetBranch = resolvedDefaultBranch.get()?.ifEmpty { null },
                            repoBranches = repoBranches,
                            // Surfaces this session's still-running background processes
                            // + their new output since last turn.
                            sessionId = sid,
                            backgroundTasks = backgroundToolRegistry.list(sid).map {
                                Triple(it.toolCallId, it.toolName, System.currentTimeMillis() - it.startedAt)
                            },
                        )
                    },
                    // Task 2.1: resolve from the service's per-session map so that both the loop
                    // drain path and any async producer (enqueueToSession) share ONE instance.
                    messageQueue = queueForSession(sid),
                    onSteeringDrained = onSteeringDrained,
                    onAwaitingUserInput = onAwaitingUserInput,
                    brainFactory = brainFactory,
                    cachedFallbackChain = cachedFallbackChain,
                    // Phase 6 review followup — when the in-flight payload contains
                    // image parts, the loop's L2 tier-escalation branch filters the chain
                    // to vision-capable models via ModelCatalogService.supportsVision().
                    // sharedCatalog is warmed up at session start so the first
                    // image-bearing escalation decision sees authoritative data, not
                    // always-false from cold cache.
                    modelCatalogService = sharedCatalog,
                    onModelSwitch = onModelSwitch,
                    compactOnTimeoutExhaustion = compactOnTimeoutExhaustion,
                    pendingModelChangeProvider = { pendingModelChange.get() },
                    onModelChangeApplied = { applied ->
                        pendingModelChange.compareAndSet(applied, null)
                    },
                    messageStateHandler = messageState,
                    toolExecutionMode = agentSettings.state.toolExecutionMode ?: "accumulate",
                    toolNameProvider = { registry.allToolNames() },
                    paramNameProvider = { com.workflow.orchestrator.agent.tools.background.BackgroundEligibility.withReservedParams(registry.allParamNames()) },
                    outputSpiller = _outputSpiller,
                    backgroundExecutor = backgroundToolExecutor,
                    backgroundCap = agentSettings.state.maxBackgroundedToolsPerSession,
                    backgroundEnabled = { AgentSettings.getInstance(project).state.allowToolsRunInBackground },
                    backgroundInFlightCount = { backgroundToolRegistry.countForSession(sid) },
                    onUserInputReceived = onUserInputReceived,
                    // Multimodal-agent Phase 3 — provide the session-scoped
                    // AttachmentStore so AgentLoop wraps every tool invocation
                    // in a SessionAttachmentAccess coroutine element. Tools
                    // resolving via :core/AttachmentSink see the SAME store
                    // BrainRouter routes through.
                    attachmentStoreProvider = { activeAttachmentStore },
                    feedbackEnabled = agentSettings.state.agentFeedbackEnabled,
                    proactiveMemoryUpdatesEnabled = agentSettings.state.proactiveMemoryUpdatesEnabled,
                    memoryDirPath = memoryDirPath.toAbsolutePath().toString(),
                    autoApproveMemoryOperations = agentSettings.state.autoApproveMemoryOperations,
                    checkpointStore = checkpointStore,
                    currentUserMessageTsProvider = { userMessageTs },
                    streamingEditCallback = streamingEditCallback,
                    networkProbe = com.workflow.orchestrator.core.network.NetworkStateService.getInstanceOrNull(),
                    llmProbeUrl = com.workflow.orchestrator.core.settings.ConnectionSettings.getInstance()
                        .state.sourcegraphUrl.trimEnd('/').ifBlank { null },
                )

                // D1: Set activeTask + activeMessageStateHandler atomically under Mutex.
                // Previously activeTask.set ran inside the launched coroutine AFTER the Job
                // returned to the caller, allowing a second concurrent executeTask call to
                // race and overwrite both fields before the first one could write them.
                // Now: acquire the mutex, cancel any pre-existing task, write both fields,
                // then release immediately — the long-running loop executes outside the lock.
                activeTaskMutex.withLock {
                    activeTask.get()?.let { prev ->
                        log.info("[AgentService] D1: cancelling previous task ${prev.sessionId} for new task $sid")
                        prev.loop.cancel()
                        prev.job.cancel()
                    }
                    activeTask.set(ActiveTask(sessionId = sid, loop = loop, job = coroutineContext.job))
                }

                val result = loop.run(task, attachments)

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
            } catch (e: ClosedReceiveChannelException) {
                // Defense-in-depth: AgentController now closes userInputChannel with a
                // CancellationException cause, so this branch should be unreachable.
                // Keep it in case a future close site forgets the cause — without this,
                // a bare channel close surfaces as "task execution failed" instead of
                // Cancelled.
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
                // F3 fix: release per-session state so perSessionStates doesn't
                // grow unbounded over the lifetime of the IDE window.
                releaseSessionState(sid)
                // D1: clear both fields under the same mutex so a concurrent executeTask
                // that is waiting to register never sees a partially-cleared state.
                activeTaskMutex.withLock {
                    activeTask.set(null)
                    activeMessageStateHandler = null
                }
                taskStore = null
                // Clear API debug dir so the brain doesn't dump after task ends.
                // BrainRouter wraps OpenAiCompatBrain — unwrap first so the underlying
                // brain's debug dir gets cleared.
                val brainToClear = (brainRef as? com.workflow.orchestrator.agent.loop.BrainRouter)?.underlyingOpenAiCompat
                    ?: brainRef
                (brainToClear as? OpenAiCompatBrain)?.setApiDebugDir(null)
                // F-25: Clear the output spiller so a failed/cancelled task doesn't leave
                // a stale ToolOutputSpiller pointing at the previous session's tool-output
                // directory.  Without this, any tool that calls outputSpiller between
                // task-end and the next resetForNewChat writes into the wrong session dir.
                _outputSpiller = null
                // Clear attachment store for the same reason — stale store would add
                // image attachments to a directory that belongs to the dead session.
                activeAttachmentStore = null
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
        onCompactionState: ((active: Boolean, phase: String) -> Unit)? = null,
        onModelSwitch: ((fromModel: String, toModel: String, reason: String) -> Unit)? = null,
        onPlanResponse: ((planText: String, needsMoreExploration: Boolean, append: Boolean) -> Unit)? = null,
        onPlanPartialContent: ((partialContent: String) -> Unit)? = null,
        onPlanModeToggled: ((Boolean) -> Unit)? = null,
        onPlanDiscarded: (() -> Unit)? = null,
        userInputChannel: Channel<String>? = null,
        pendingChannelImageRefs: (() -> List<com.workflow.orchestrator.agent.session.ContentBlock.ImageRef>)? = null,
        approvalGate: (suspend (toolName: String, args: String, riskLevel: String, allowSessionApproval: Boolean) -> com.workflow.orchestrator.agent.loop.ApprovalResult)? = null,
        onSubagentProgress: ((agentId: String, update: SubagentProgressUpdate) -> Unit)? = null,
        onTokenUpdate: ((inputTokens: Int, outputTokens: Int) -> Unit)? = null,
        onSessionStats: ((modelId: String, tokensIn: Long, tokensOut: Long, costUsd: Double?) -> Unit)? = null,
        onDebugLog: ((level: String, event: String, detail: String, meta: Map<String, Any?>?) -> Unit)? = null,
        onSessionStarted: ((sessionId: String) -> Unit)? = null,
        onSteeringDrained: ((drainedIds: List<String>) -> Unit)? = null,
        sessionApprovalStore: com.workflow.orchestrator.agent.loop.SessionApprovalStore = com.workflow.orchestrator.agent.loop.SessionApprovalStore(),
        onAwaitingUserInput: ((reason: String) -> Unit)? = null,
        onUserInputReceived: ((task: String) -> com.workflow.orchestrator.agent.session.UiMessage?)? = null,
        streamingEditCallback: com.workflow.orchestrator.agent.loop.StreamingEditCallback? = null,
        onContextManagerReady: ((ContextManager) -> Unit)? = null,
        onHandoffProposed: ((context: String) -> Unit)? = null,
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

        currentSessionId = sessionId
        // Seed per-session plan-mode from the persisted HistoryItem so that the
        // resumed session is in the correct mode from the very first iteration
        // (F1/F2 fix — executeTask will reinforce this value again via its own
        // persistedPlanMode lookup, but setting it here ensures isPlanModeActive()
        // returns the right value for any code that runs before executeTask).
        val resumedPlanMode =
            MessageStateHandler.findHistoryItem(agentDir, sessionId)?.planModeEnabled ?: false
        sessionStateFor(sessionId)
            .planModeActive.set(resumedPlanMode)

        // Trim trailing resume messages and cost-less api_req_started (Cline pattern)
        savedUiMessages = ResumeHelper.trimResumeMessages(savedUiMessages)

        // Push full ui_messages to webview for rehydration
        onUiMessagesLoaded?.invoke(savedUiMessages)

        // Determine resume ask type
        val resumeAskType = ResumeHelper.determineResumeAskType(savedUiMessages)

        // If session was already completed AND the user hasn't typed a follow-up,
        // display the UI messages and stop — no re-execution. When the user *has*
        // typed a follow-up message, fall through to the normal resume path so
        // the message is appended as a new turn and the loop continues.
        if (resumeAskType == UiAsk.RESUME_COMPLETED_TASK && userText.isNullOrBlank()) {
            log.info("AgentService.resumeSession: session $sessionId was already completed, displaying without re-execution")
            lock.release()
            onComplete(LoopResult.Completed(
                summary = "Task was previously completed.",
                iterations = 0,
                tokensUsed = 0,
            ))
            return cs.launch(Dispatchers.IO) { /* no-op — session already completed, UI messages already pushed */ }
        }

        // Pop trailing user message if interrupted mid-submission
        val popResult = ResumeHelper.popTrailingUserMessage(savedApiHistory)
        var activeApiHistory = popResult.trimmedHistory

        // Clean trailing empty-assistant pollution left by pre-guard sessions.
        // Mirror: ContextManager.pruneTrailingEmptyAssistants runs below on restoreMessages.
        val emptyDrop = ResumeHelper.dropTrailingEmptyAssistants(activeApiHistory)
        activeApiHistory = emptyDrop.history
        val droppedEmpties = emptyDrop.droppedCount
        if (droppedEmpties > 0) {
            log.info("[AgentService] resume cleanup: dropped $droppedEmpties trailing empty-assistant turn(s) from history")
        }

        // Dialect-drift cleanup on resume — redact incompatible-format tool-call
        // XML (Anthropic <invoke>, Hermes <tool_call>{json}) inline on the
        // in-memory list before we seed it into the handler below. Same rationale
        // as the retry path in cleanEmptyArtifactsBeforeRetry — see
        // DialectDriftDetector header. The persisted file is updated when the
        // handler runs its first save (the resumption user-message append at the
        // bottom of this function will overwrite the file with the cleaned list).
        var dialectDriftDetectedOnResume = false
        val dialectRedaction = ResumeHelper.redactDialectDriftInHistory(activeApiHistory)
        val redactedCount = dialectRedaction.redactedCount
        if (redactedCount > 0) {
            log.info("[AgentService] resume cleanup: redacted dialect tool-call XML in $redactedCount assistant turn(s)")
            dialectDriftDetectedOnResume = true
            activeApiHistory = dialectRedaction.history
        }

        // Build task resumption preamble
        val lastActivityTs = savedUiMessages.lastOrNull()?.ts ?: System.currentTimeMillis()
        val agoText = ResumeHelper.formatTimeAgo(lastActivityTs)
        val mode = if (isPlanModeActive()) "plan" else "act"
        val cwd = project.basePath ?: ""
        val basePreamble = ResumeHelper.buildTaskResumptionPreamble(
            mode = mode,
            agoText = agoText,
            cwd = cwd,
            userText = userText,
            wasPreviouslyCompleted = (resumeAskType == UiAsk.RESUME_COMPLETED_TASK),
        )

        // Task 4.1 — drain the unified queue ONCE; reuse `drainedGroups` for BOTH the preamble
        // text (here) and the async-event card synthesis (inside the cs.launch job below).
        // The drain call clears the queue; a second drain call would lose items.
        val drainedGroups = runCatching { queueForSession(sessionId).drainGrouped() }.getOrElse { err ->
            log.warn("[AgentService] unified queue drain failed for $sessionId — continuing without queue items: ${err.message}", err)
            emptyList()
        }
        if (drainedGroups.isNotEmpty()) {
            log.info("[AgentService] resume pickup: delivered ${drainedGroups.sumOf { it.ids.size }} queued item(s) from unified queue for $sessionId")
        }
        val queueDrain = drainedGroups.joinToString("\n") { it.framedText }
        val baseWithQueue = if (queueDrain.isBlank()) basePreamble else basePreamble + queueDrain

        // LEGACY: retire next release
        // Task 6.2 — append any background-completion events that landed while the
        // session was idle. BackgroundPersistence accumulates them under
        // sessions/{id}/background/pending_completions.json; we splice them into
        // the preamble so the resumed LLM turn sees them in-context, then consume
        // them so they're not re-delivered on a subsequent resume.
        val withBgCompletions = run {
            val persistence = com.workflow.orchestrator.agent.tools.background
                .BackgroundPersistence(agentDir.toPath())
            val pending = runCatching { persistence.loadPendingCompletions(sessionId) }
                .getOrElse { err ->
                    log.warn("[AgentService] loadPendingCompletions failed for $sessionId: ${err.message}", err)
                    emptyList()
                }
            val completionsPreamble = ResumeHelper.formatBackgroundCompletionsSection(pending)
            if (completionsPreamble.isEmpty()) {
                baseWithQueue
            } else {
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
                baseWithQueue + completionsPreamble
            }
        }

        // LEGACY: retire next release
        // BUG #2 — append any cross-IDE delegation result/question nudges that landed while
        // the session was idle but whose auto-wake was rejected (cooldown/cap/disabled/
        // no-listener) or DEFERRED because a different session was active (BUG #4). Mirrors
        // the background-completion pickup above: splice into the preamble, then consume so
        // they're delivered exactly once.
        val preamble = run {
            val nudgePersistence = com.workflow.orchestrator.agent.tools.background
                .DelegationNudgePersistence(agentDir.toPath())
            val pendingNudges = runCatching { nudgePersistence.loadPendingNudges(sessionId) }
                .getOrElse { err ->
                    log.warn("[AgentService] loadPendingNudges failed for $sessionId: ${err.message}", err)
                    emptyList()
                }
            val nudgesPreamble = ResumeHelper.formatDelegationNudgesSection(pendingNudges)
            if (nudgesPreamble.isEmpty()) {
                withBgCompletions
            } else {
                pendingNudges.forEach { n ->
                    runCatching { nudgePersistence.consumeNudge(sessionId, n.id) }
                        .onFailure { err ->
                            log.warn("[AgentService] consumeNudge failed for $sessionId/${n.id}: ${err.message}", err)
                        }
                }
                log.info("[AgentService] resume pickup: delivered ${pendingNudges.size} persisted delegation nudge(s) for $sessionId")
                withBgCompletions + nudgesPreamble
            }
        }

        // LEGACY: retire next release
        // Task 6F — append any monitor notifications that were persisted by the idle-wake path
        // (Task 6E) while the session was paused.  Mirrors the background-completion and
        // delegation-nudge pickup blocks above: splice into the preamble, then clear so they
        // are delivered exactly once.  Wrapped in runCatching so a persistence failure never
        // aborts the resume.
        val finalPreamble = runCatching {
            val pendingNotifications = monitorCoordinator.loadPendingNotifications(sessionId)
            val notificationsPreamble = ResumeHelper.formatMonitorNotificationsSection(pendingNotifications)
            if (notificationsPreamble.isEmpty()) {
                preamble
            } else {
                monitorCoordinator.clearPendingNotifications(sessionId)
                log.info("[AgentService] resume pickup: delivered ${pendingNotifications.size} persisted monitor notification(s) for $sessionId")
                preamble + notificationsPreamble
            }
        }.getOrElse { err ->
            log.warn("[AgentService] loadPendingNotifications failed for $sessionId — using base preamble: ${err.message}", err)
            preamble
        }

        // Build new user content: finalPreamble + any popped content
        val newUserContent = buildList {
            add(ContentBlock.Text(finalPreamble))
            addAll(popResult.poppedContent)
        }

        // Create MessageStateHandler with restored state (init-only, before concurrent access)
        val taskText = savedUiMessages.firstOrNull()?.text ?: "Resumed session"
        val handler = MessageStateHandler(baseDir = sessionBaseDir, sessionId = sessionId, taskText = taskText)
        handler.setClineMessages(savedUiMessages)
        handler.setApiConversationHistory(activeApiHistory)

        // Plan 4: rehydrate persisted outbound handles so CHANNEL_RESUME can fire
        // lazily on the first delegation_* tool call that references them.
        try {
            project.getService(
                com.workflow.orchestrator.agent.delegation.DelegationOutboundService::class.java
            ).loadPersistedHandles(
                sessionDir = sessionDir.toPath(),
                delegatorSessionId = sessionId,
            )
        } catch (e: Exception) {
            log.warn("AgentService.resumeSession: loadPersistedHandles failed for $sessionId", e)
        }

        // TASK_RESUME hook — dispatched within cs.launch (IO coroutine), C5 fix:
        // the hook check and dispatch happen inside the launched coroutine to avoid blocking the calling thread.
        val job = cs.launch(Dispatchers.IO) {
            // Task 6F — re-arm persisted monitors BEFORE the TASK_RESUME hook so any monitor
            // event that fires before the hook completes is delivered on the steering queue
            // rather than dropped.  reArmMonitors is suspend (pool.register uses a Mutex) so
            // it executes here inside the IO coroutine.  Its own runCatching wrapper guarantees
            // a re-arm failure can never prevent the hook or loop from starting.
            monitorCoordinator.reArmMonitors(sessionId)

            // Add resume ask to UI messages
            handler.addToClineMessages(UiMessage(
                ts = System.currentTimeMillis(),
                type = UiMessageType.ASK,
                ask = resumeAskType,
                text = "Task was interrupted $agoText. Resuming..."
            ))

            // Task 4.1 — async-event cards (queue-backed idle path, review B1). The session is NOT
            // active here (activeMessageStateHandler is set later in executeTask), so append onto
            // THIS resume-local `handler` directly — not appendAsyncEventCardToSession (which would
            // no-op). Dedup against the in-memory savedUiMessages so a card already shown live on
            // the focused session isn't duplicated. addToClineMessages is suspend → callable here.
            runCatching {
                val existingIds = savedUiMessages.mapNotNull { it.asyncEventData?.id }.toSet()
                val items = drainedGroups
                    .filter {
                        it.kind == com.workflow.orchestrator.agent.loop.queue.QueueSourceKind.BACKGROUND ||
                        it.kind == com.workflow.orchestrator.agent.loop.queue.QueueSourceKind.MONITOR
                    }
                    .flatMap { it.items }
                com.workflow.orchestrator.agent.ui.AsyncEventResumeSynthesis.cardsToAppend(items, existingIds).forEach { card ->
                    handler.addToClineMessages(UiMessage(
                        ts = System.currentTimeMillis(),
                        type = UiMessageType.SAY,
                        say = UiSay.ASYNC_EVENT,
                        asyncEventData = card,
                    ))
                }
            }.onFailure { log.warn("[AgentService] async-event resume synthesis failed for $sessionId: ${it.message}", it) }

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
                    // If the user typed a follow-up alongside the resume, surface it back
                    // in the chat so the cancellation isn't silently swallowing their input.
                    // Mirrors the fix shape from 56906e668 (completed-task resume drop).
                    val cancelNote = ResumeHelper.buildResumeCancelledNote(hookResult.reason, userText)
                    handler.addToClineMessages(UiMessage(
                        ts = System.currentTimeMillis(),
                        type = UiMessageType.SAY,
                        say = UiSay.ERROR,
                        text = cancelNote,
                    ))
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

            // Rebuild ContextManager from api history — v0.83.44 catalog-driven budget.
            val ctx = ContextManager(
                maxInputTokens = ContextManager.FALLBACK_MAX_INPUT_TOKENS,
                modelCatalogService = sharedCatalogHolder.peek(),
                currentModelRef = { currentBrainModelId },
                effectiveContextWindow = effectiveContextWindow,
            )
            val resumeResearchDirPath = com.workflow.orchestrator.core.util.ProjectIdentifier.researchDir(basePath).toPath()
            val resumeResearchIndex = com.workflow.orchestrator.agent.research.ResearchIndex.load(resumeResearchDirPath)
            val systemPrompt = SystemPrompt.build(
                projectName = project.name,
                projectPath = project.basePath ?: "",
                planModeEnabled = isPlanModeActive(),
                ideContext = ideContext,
                availableShells = allowedShells,
                availableModels = formatModelsForPrompt(ModelCache.getCached()),
                hasWebTools = registry.has("web_fetch") || registry.has("web_search"),
                researchIndex = resumeResearchIndex,
                researchIndexPath = resumeResearchDirPath.toString(),
                // One-shot — fires only if the resume-path cleanup above redacted turns.
                // (executeTask's own systemPromptBuilder will consume the flag for any
                // further drift caught at write-time during this session.)
                dialectDriftDetected = dialectDriftDetectedOnResume,
                delegationOutboundEnabled = com.workflow.orchestrator.core.settings.PluginSettings
                    .getInstance(project).state.enableOutboundCrossIdeDelegation,
                delegationTargets = computeDelegationTargetsForPrompt(project),
            )
            ctx.setSystemPrompt(systemPrompt)

            // Convert ApiMessage list to ChatMessage list (lossless C2 fix)
            // Include the resumption user message so the LLM sees it on the next call
            val chatMessages = activeApiHistory.map { it.toChatMessage() } +
                com.workflow.orchestrator.core.ai.dto.ChatMessage(role = "user", content = finalPreamble)
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
                task = finalPreamble,
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
                onCompactionState = onCompactionState,
                onModelSwitch = onModelSwitch,
                onPlanResponse = onPlanResponse,
                onPlanPartialContent = onPlanPartialContent,
                onPlanModeToggled = onPlanModeToggled,
                onPlanDiscarded = onPlanDiscarded,
                userInputChannel = userInputChannel,
                pendingChannelImageRefs = pendingChannelImageRefs,
                approvalGate = approvalGate,
                onSubagentProgress = onSubagentProgress,
                onTokenUpdate = onTokenUpdate,
                onSessionStats = onSessionStats,
                onDebugLog = onDebugLog,
                onSessionStarted = onSessionStarted,
                onSteeringDrained = onSteeringDrained,
                sessionApprovalStore = sessionApprovalStore,
                onAwaitingUserInput = onAwaitingUserInput,
                onUserInputReceived = onUserInputReceived,
                streamingEditCallback = streamingEditCallback,
                onContextManagerReady = onContextManagerReady,
                onHandoffProposed = onHandoffProposed,
            )
            innerJob.join()
        }
        return job
    }

    /**
     * Update the title of an existing session (e.g. after Haiku generates a descriptive title).
     * Updates the global index entry for this session with the new title/task text.
     *
     * Delegates to [MessageStateHandler.updateSessionTitle] so the same cross-process
     * file lock that protects deleteSession / toggleFavorite / updateGlobalIndex
     * also serializes this write.
     */
    fun updateSessionTitle(sessionId: String, title: String) {
        try {
            MessageStateHandler.updateSessionTitle(agentDir, sessionId, title)
        } catch (e: Exception) {
            log.warn("AgentService.updateSessionTitle: failed to update title (non-fatal)", e)
        }
    }

    /**
     * Best-effort human title for an existing session, read from the persisted HistoryItem
     * (`HistoryItem.task`). Used by the busy-decline descriptor (PART 2) so IDE-B can name the
     * in-flight task it is busy with. Returns null on any miss (no index entry, IO failure) —
     * callers fall back to the generic wording, so nothing regresses.
     */
    fun currentSessionTitle(sessionId: String): String? =
        try {
            MessageStateHandler.findHistoryItem(agentDir, sessionId)?.task
        } catch (e: Exception) {
            log.warn("AgentService.currentSessionTitle: failed to read title (non-fatal)", e)
            null
        }

    // ── Checkpoint v2 — Reverts and Diff ──────────────────────────────────

    /**
     * Revert a session to a specific user message. Restores files via the
     * SessionCheckpointStore, truncates persisted UI + api history, and returns
     * the data the controller needs to push UI state.
     *
     * Caller (AgentController) is responsible for:
     *  - Pushing truncated ui_messages to the webview via _loadSessionState
     *  - Pushing the returned userText to the chat input via restoreInputText
     *  - Cancelling any in-flight job before calling this (prepareForReplay)
     */
    suspend fun revertToUserMessage(sessionId: String, messageTs: Long): com.workflow.orchestrator.agent.checkpoint.RevertResult {
        val basePath = project.basePath ?: System.getProperty("user.home")
        val sessionBaseDir = ProjectIdentifier.agentDir(basePath)
        val sessionDir = java.io.File(sessionBaseDir, "sessions/$sessionId")

        // 1. Compute droppedApiCount from the UI message's conversationHistoryIndex.
        val existingUi = MessageStateHandler.loadUiMessages(sessionDir)
        val targetUi = existingUi.firstOrNull { it.ts == messageTs }
        val keepApiCount = targetUi?.conversationHistoryIndex ?: 0
        val existingApi = MessageStateHandler.loadApiHistory(sessionDir)
        val droppedApiCount = (existingApi.size - keepApiCount).coerceAtLeast(0)

        // 2. Restore files via the checkpoint store.
        // E3: supply project root so revert validates every restored path stays within the project.
        val projectRootFile = project.basePath?.let { java.io.File(it) }
        val store = com.workflow.orchestrator.agent.checkpoint.SessionCheckpointStore(
            sessionDir = sessionDir,
            projectRoot = projectRootFile,
        )
        val result = store.revertToMessage(messageTs)

        // 3. Truncate persisted UI + api history.
        val handler = MessageStateHandler(baseDir = sessionBaseDir, sessionId = sessionId, taskText = "")
        if (existingUi.isNotEmpty()) handler.setClineMessages(existingUi)
        if (existingApi.isNotEmpty()) handler.setApiConversationHistory(existingApi)
        handler.truncateMessagesAtTs(messageTs, droppedApiCount)   // direct suspend call, no runBlockingCancellable

        if (result.skippedPaths.isNotEmpty()) {
            log.warn("AgentService.revertToUserMessage: ${result.skippedPaths.size} path(s) skipped (out-of-root / symlink): ${result.skippedPaths}")
        }
        log.info("AgentService.revertToUserMessage: session=$sessionId ts=$messageTs restored=${result.restoredFiles.size} deleted=${result.deletedFiles.size} skipped=${result.skippedPaths.size}")
        return result
    }

    /** Single-file revert. No history truncation, no chat-input push. */
    fun revertFileToBaseline(sessionId: String, absolutePath: String): Boolean {
        val basePath = project.basePath ?: System.getProperty("user.home")
        val sessionDir = java.io.File(ProjectIdentifier.agentDir(basePath), "sessions/$sessionId")
        // E3: supply project root so single-file revert validates the path is in-project.
        val projectRootFile = project.basePath?.let { java.io.File(it) }
        val store = com.workflow.orchestrator.agent.checkpoint.SessionCheckpointStore(
            sessionDir = sessionDir,
            projectRoot = projectRootFile,
        )
        return store.revertFileToBaseline(absolutePath).reverted
    }

    /** Returns the session's current baseline-to-current diff. Cheap to call after every write tool. */
    fun getAggregateDiff(sessionId: String): com.workflow.orchestrator.agent.checkpoint.AggregateDiff {
        val basePath = project.basePath ?: System.getProperty("user.home")
        val sessionDir = java.io.File(ProjectIdentifier.agentDir(basePath), "sessions/$sessionId")
        val store = com.workflow.orchestrator.agent.checkpoint.SessionCheckpointStore(sessionDir = sessionDir)
        return store.aggregateDiff()
    }

    /** Earliest user-message checkpoint ts for the session, or null if no checkpoints exist. */
    fun firstUserMessageTs(sessionId: String): Long? {
        val basePath = project.basePath ?: System.getProperty("user.home")
        val sessionDir = java.io.File(ProjectIdentifier.agentDir(basePath), "sessions/$sessionId")
        val store = com.workflow.orchestrator.agent.checkpoint.SessionCheckpointStore(sessionDir = sessionDir)
        return store.listMessageCheckpoints().firstOrNull()?.messageTs
    }

    /** Read persisted UI messages for a session — exposed for AgentController to push to the webview after a revert. */
    fun loadUiMessages(sessionId: String): List<com.workflow.orchestrator.agent.session.UiMessage> {
        val basePath = project.basePath ?: System.getProperty("user.home")
        val sessionDir = java.io.File(ProjectIdentifier.agentDir(basePath), "sessions/$sessionId")
        return MessageStateHandler.loadUiMessages(sessionDir)
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
        onComplete: (LoopResult) -> Unit = {},
        onSessionStarted: ((sessionId: String) -> Unit)? = null,
        onContextManagerReady: ((ContextManager) -> Unit)? = null,
        onHandoffProposed: ((context: String) -> Unit)? = null,
        userInputChannel: Channel<String>? = null,
        pendingChannelImageRefs: (() -> List<com.workflow.orchestrator.agent.session.ContentBlock.ImageRef>)? = null,
    ): Job {
        // The handoff context becomes the task for the new session
        val preamble = "Continue from the previous session. Here is the preserved context:\n\n$handoffContext"

        return executeTask(
            task = preamble,
            sessionId = null, // new session ID
            contextManager = null, // fresh context
            onStreamChunk = onStreamChunk,
            onToolCall = onToolCall,
            onComplete = onComplete,
            onSessionStarted = onSessionStarted,
            onContextManagerReady = onContextManagerReady,
            onHandoffProposed = onHandoffProposed,
            userInputChannel = userInputChannel,
            pendingChannelImageRefs = pendingChannelImageRefs,
        )
    }

    // ── Cross-IDE Delegation ────────────────────────────────────────────────

    /**
     * Starts a first-class delegated [AgentSession] driven by an incoming cross-IDE
     * delegation request (Plan 1 Task 7).
     *
     * Lifecycle:
     * 1. Generate a new session ID (UUID).
     * 2. Call [executeTask] with [request] as the initial user prompt and the generated
     *    session ID, passing [delegationMetadata] so the HistoryItem in sessions.json is
     *    populated with the delegation marker (spec §9.1) and the live [Session.delegated]
     *    field is set on the in-memory session object. No separate delegation.json sidecar.
     * 3. An [onComplete] callback captures the terminal [LoopResult].
     * 4. Map the [LoopResult] to a [DelegationMessage.Result] and invoke [onResult].
     *
     * Threading: launched on [Dispatchers.IO]; [onResult] is called from the same context
     * after the job completes.
     *
     * Spec: §6.1, §7, §9.1.
     * F2: metadata written to HistoryItem.delegated (replaces delegation.json sidecar).
     * F6: metadata threaded into Session.delegated via executeTask's delegationMetadata param.
     */
    /**
     * Start a delegated agent session (Plan 2 Task 4).
     *
     * Returns the local session ID synchronously so the caller
     * ([DelegationInboundService.handleConnect]) has the ID in hand before the
     * agent loop launches — required to run the Answer-read loop with the correct
     * session ID.
     *
     * The [replyWith] callback is registered with [DelegationInboundService] before
     * the loop starts so [AskQuestionsTool] can call [DelegationInboundService.routeQuestion]
     * on the first iteration without a race.
     */
    fun startDelegatedSession(
        request: String,
        delegationMetadata: com.workflow.orchestrator.agent.session.DelegationMetadata,
        replyWith: suspend (com.workflow.orchestrator.core.delegation.DelegationMessage) -> Unit,
        onResult: suspend (com.workflow.orchestrator.core.delegation.DelegationMessage.Result) -> Unit,
        /**
         * Full controller→loop UI-callback bundle (the single source of truth built by
         * [com.workflow.orchestrator.agent.ui.AgentController.buildSessionUiCallbacks]). Every field
         * is forwarded into [executeTask] below — so a future callback added to the bundle flows to
         * the delegated path automatically, instead of being silently dropped (the bug class this
         * structural fix closes). Pinned by
         * [com.workflow.orchestrator.agent.delegation.SessionUiCallbacksParityTest].
         */
        callbacks: com.workflow.orchestrator.agent.ui.SessionUiCallbacks,
        onJobCreated: ((kotlinx.coroutines.Job) -> Unit)? = null,
    ): String {
        val sid = UUID.randomUUID().toString()
        val startTime = System.currentTimeMillis()
        log.info(
            "[Agent] startDelegatedSession: sessionId=$sid, delegator=${delegationMetadata.delegatorIde}, " +
                "repo=${delegationMetadata.delegatorRepo}, request='${request.take(60)}'"
        )

        // Plan 6 Task 8: count this delegated session as live; decremented + checked
        // for transient-bind teardown in the terminal callback's finally below.
        activeDelegatedSessions.incrementAndGet()

        // Stamp the per-session state with delegation metadata BEFORE the loop starts
        // so AskQuestionsTool can detect the delegated context on the very first iteration.
        sessionStateFor(sid).also { it.delegated = delegationMetadata }

        // Register the reply channel so routeQuestion can find it when the tool fires.
        val inbound = project.getService(
            com.workflow.orchestrator.agent.delegation.DelegationInboundService::class.java
        )
        inbound.registerSessionChannel(sid, replyWith)

        // Run the agent loop. executeTask returns a Job immediately; we capture the
        // LoopResult via the onComplete callback using a CompletableDeferred so we can
        // map it to a DelegationMessage.Result and call onResult after the job finishes.
        val loopResultDeferred = kotlinx.coroutines.CompletableDeferred<LoopResult>()

        // F5 fix: if executeTask throws synchronously (before its own finally runs), undo
        // the setup we did above so perSessionStates and sessionChannels don't leak.
        val job = try {
            executeTask(
                task = request,
                sessionId = sid,
                delegationMetadata = delegationMetadata,
                uiMessageOverride = delegatedIncomingUiMessageOverride(delegationMetadata, request),
                // Forward EVERY field of the bundle — parity-locked by SessionUiCallbacksParityTest.
                onStreamChunk = callbacks.onStreamChunk,
                onToolCall = callbacks.onToolCall,
                // #1: chain the controller's delegated finalize (spinner/tool-chain cleanup, no
                // generic completion card) with the socket result delivery. loopResultDeferred +
                // onResult below are UNCHANGED — the result card is still the single terminal card.
                onComplete = { result ->
                    // Orphan-cancel: mark the channel terminal BEFORE result delivery / socket
                    // teardown, so a normal completion-close is not misread as an orphaning drop.
                    inbound.markTerminal(sid)
                    callbacks.onComplete(result)
                    loopResultDeferred.complete(result)
                },
                onRetry = callbacks.onRetry,
                onCompactionState = callbacks.onCompactionState,
                onModelSwitch = callbacks.onModelSwitch,
                onPlanResponse = callbacks.onPlanResponse,
                onPlanPartialContent = callbacks.onPlanPartialContent,
                onPlanModeToggled = callbacks.onPlanModeToggled,
                onPlanDiscarded = callbacks.onPlanDiscarded,
                approvalGate = callbacks.approvalGate,
                sessionApprovalStore = callbacks.sessionApprovalStore,
                onSubagentProgress = callbacks.onSubagentProgress,
                onTokenUpdate = callbacks.onTokenUpdate,
                onSessionStats = callbacks.onSessionStats,
                onDebugLog = callbacks.onDebugLog,
                onSessionStarted = callbacks.onSessionStarted,
                onSteeringDrained = callbacks.onSteeringDrained,
                onAwaitingUserInput = callbacks.onAwaitingUserInput,
                onUserInputReceived = callbacks.onUserInputReceived,
                streamingEditCallback = callbacks.streamingEditCallback,
                onHandoffProposed = callbacks.onHandoffProposed,
            )
        } catch (e: Throwable) {
            // F5 fix: synchronous setup failed — roll back what we did above to avoid leaks.
            releaseSessionState(sid)
            inbound.unregisterSessionChannel(sid)
            // Plan 6 Task 8: the terminal callback below never runs on synchronous
            // failure, so decrement + check teardown here to avoid a count leak.
            inbound.stopIfTransientAndIdle(activeDelegatedSessions.decrementAndGet())
            throw e
        }
        // Orphan-cancel: attach the loop Job to the inbound channel so a CancelTask (or a
        // non-terminal socket EOF) from IDE-A can cancel it. The job did not exist at
        // registerSessionChannel time — wire it the instant executeTask returns it.
        inbound.attachJob(sid, job)
        onJobCreated?.invoke(job)

        cs.launch(Dispatchers.IO) {
            try {
                job.join()
                val loopResult = loopResultDeferred.await()
                val durationSeconds = (System.currentTimeMillis() - startTime) / 1000

                val delegationResult = mapLoopResultToDelegationResult(loopResult, durationSeconds)
                log.info("[Agent] Delegated session $sid finished: status=${delegationResult.status}, duration=${durationSeconds}s")
                onResult(delegationResult)
            } catch (e: kotlinx.coroutines.CancellationException) {
                val durationSeconds = (System.currentTimeMillis() - startTime) / 1000
                onResult(delegationResultForDeliveryFailure(e, durationSeconds))
                throw e
            } catch (e: Exception) {
                if (isBenignDeliveryDisconnect(e)) {
                    // The session SUCCEEDED; only the terminal-result delivery failed because
                    // IDE-A already hung up (closed the handle / let a `wait` lapse / cancelled).
                    // Do NOT mislabel this as FAILED and do NOT log at ERROR.
                    log.info("[Agent] IDE-A disconnected before result delivery for session $sid")
                } else {
                    log.error("[Agent] Delegated session $sid failed unexpectedly", e)
                    val durationSeconds = (System.currentTimeMillis() - startTime) / 1000
                    // Defense-in-depth: the fallback delivery itself can hit a second
                    // closed-channel write on a gone peer. Wrap it so a benign double-fault
                    // can never escape uncaught into the coroutine's handler.
                    try {
                        onResult(delegationResultForDeliveryFailure(e, durationSeconds))
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (deliveryError: Exception) {
                        if (isBenignDeliveryDisconnect(deliveryError)) {
                            log.info("[Agent] IDE-A disconnected before FAILED-result delivery for session $sid")
                        } else {
                            log.warn("[Agent] failed to deliver FAILED result for session $sid", deliveryError)
                        }
                    }
                }
            } finally {
                inbound.unregisterSessionChannel(sid)
                // Plan 6 Task 8: this delegated session has ended. Decrement first, then
                // pass the post-decrement count so a transient ("Allow once") inbound bind
                // is torn down once no delegated sessions remain. No-op for persistent binds.
                inbound.stopIfTransientAndIdle(activeDelegatedSessions.decrementAndGet())
            }
        }

        return sid
    }

    /**
     * Resume a previously-COMPLETED delegated agent session and continue it with a follow-up
     * user turn (Fix 3 — true continuation). The resurrection counterpart of [startDelegatedSession]:
     * it mirrors the delegated-session lifecycle (count, re-stamp delegation metadata, register the
     * reply channel, map the terminal [LoopResult] → [DelegationMessage.Result], tear down) but drives
     * [resumeSession] with `userText = userTurnText` instead of [executeTask] — so the SAME persisted
     * IDE-B conversation is re-opened, the follow-up turn appended, and the loop continued.
     *
     * Called from [com.workflow.orchestrator.agent.ui.AgentController.resumeDelegatedSession]
     * (controller-routed so the busy-gate + live "Delegated by…" banner apply), which is itself driven
     * by [DelegationInboundService.handleChannelResume] when IDE-A reattaches to a closed delegation.
     *
     * @throws IllegalStateException if [resumeSession] cannot drive the session (locked by another
     * instance, missing on disk, or no api history) — propagated so the caller replies a clear error
     * IDE-A can map, rather than silently swallowing.
     */
    fun resumeDelegatedSession(
        sessionId: String,
        userTurnText: String,
        delegationMetadata: com.workflow.orchestrator.agent.session.DelegationMetadata,
        replyWith: suspend (com.workflow.orchestrator.core.delegation.DelegationMessage) -> Unit,
        onResult: suspend (com.workflow.orchestrator.core.delegation.DelegationMessage.Result) -> Unit,
        /**
         * Full controller→loop UI-callback bundle (single source of truth). Every field is forwarded
         * into [resumeSession] below; parity-locked by
         * [com.workflow.orchestrator.agent.delegation.SessionUiCallbacksParityTest].
         */
        callbacks: com.workflow.orchestrator.agent.ui.SessionUiCallbacks,
        onJobCreated: ((kotlinx.coroutines.Job) -> Unit)? = null,
    ) {
        val startTime = System.currentTimeMillis()
        log.info(
            "[Agent] resumeDelegatedSession: sessionId=$sessionId, delegator=${delegationMetadata.delegatorIde}, " +
                "repo=${delegationMetadata.delegatorRepo}, follow-up='${userTurnText.take(60)}'"
        )

        // Count this resumed delegated session as live (mirrors startDelegatedSession Task 8).
        activeDelegatedSessions.incrementAndGet()

        // Re-stamp the per-session state with delegation metadata BEFORE the loop continues so the
        // delegated context (AskQuestionsTool routing, banner) is detected on the very first iteration.
        sessionStateFor(sessionId).also { it.delegated = delegationMetadata }

        // Register the reply channel so routeQuestion can find it when the tool fires.
        val inbound = project.getService(
            com.workflow.orchestrator.agent.delegation.DelegationInboundService::class.java
        )
        inbound.registerSessionChannel(sessionId, replyWith)

        val loopResultDeferred = kotlinx.coroutines.CompletableDeferred<LoopResult>()

        // Drive resumeSession with the follow-up turn. A non-null userText makes resumeSession fall
        // through the RESUME_COMPLETED_TASK branch and continue the loop (its core enabler).
        val job: kotlinx.coroutines.Job? = try {
            resumeSession(
                sessionId = sessionId,
                userText = userTurnText,
                // Forward EVERY field of the bundle — parity-locked by SessionUiCallbacksParityTest.
                onStreamChunk = callbacks.onStreamChunk,
                onToolCall = callbacks.onToolCall,
                // #1: chain controller's delegated finalize with socket result delivery (unchanged).
                onComplete = { result ->
                    // Orphan-cancel: mark terminal before result delivery / socket teardown.
                    inbound.markTerminal(sessionId)
                    callbacks.onComplete(result)
                    loopResultDeferred.complete(result)
                },
                onRetry = callbacks.onRetry,
                onCompactionState = callbacks.onCompactionState,
                onModelSwitch = callbacks.onModelSwitch,
                onPlanResponse = callbacks.onPlanResponse,
                onPlanPartialContent = callbacks.onPlanPartialContent,
                onPlanModeToggled = callbacks.onPlanModeToggled,
                onPlanDiscarded = callbacks.onPlanDiscarded,
                approvalGate = callbacks.approvalGate,
                sessionApprovalStore = callbacks.sessionApprovalStore,
                onSubagentProgress = callbacks.onSubagentProgress,
                onTokenUpdate = callbacks.onTokenUpdate,
                onSessionStats = callbacks.onSessionStats,
                onDebugLog = callbacks.onDebugLog,
                onSessionStarted = callbacks.onSessionStarted,
                onSteeringDrained = callbacks.onSteeringDrained,
                onAwaitingUserInput = callbacks.onAwaitingUserInput,
                onUserInputReceived = callbacks.onUserInputReceived,
                streamingEditCallback = callbacks.streamingEditCallback,
                onHandoffProposed = callbacks.onHandoffProposed,
            )
        } catch (e: Throwable) {
            // Synchronous setup failed — roll back what we did above to avoid leaks.
            releaseSessionState(sessionId)
            inbound.unregisterSessionChannel(sessionId)
            inbound.stopIfTransientAndIdle(activeDelegatedSessions.decrementAndGet())
            throw e
        }

        if (job == null) {
            // resumeSession returned null → locked / missing dir / no api history. Roll back and
            // propagate a CLEAR failure so the caller (handleChannelResume) replies an error IDE-A
            // maps — do NOT silently swallow.
            releaseSessionState(sessionId)
            inbound.unregisterSessionChannel(sessionId)
            inbound.stopIfTransientAndIdle(activeDelegatedSessions.decrementAndGet())
            throw IllegalStateException(
                "resume_failed: session $sessionId could not be resumed " +
                    "(locked by another instance, missing on disk, or no conversation history)"
            )
        }
        // Orphan-cancel: attach the resumed loop Job so a CancelTask / non-terminal EOF cancels it.
        inbound.attachJob(sessionId, job)
        onJobCreated?.invoke(job)

        cs.launch(Dispatchers.IO) {
            try {
                job.join()
                val loopResult = loopResultDeferred.await()
                val durationSeconds = (System.currentTimeMillis() - startTime) / 1000
                val delegationResult = mapLoopResultToDelegationResult(loopResult, durationSeconds)
                log.info("[Agent] Resumed delegated session $sessionId finished: " +
                    "status=${delegationResult.status}, duration=${durationSeconds}s")
                onResult(delegationResult)
            } catch (e: kotlinx.coroutines.CancellationException) {
                val durationSeconds = (System.currentTimeMillis() - startTime) / 1000
                onResult(delegationResultForDeliveryFailure(e, durationSeconds))
                throw e
            } catch (e: Exception) {
                if (isBenignDeliveryDisconnect(e)) {
                    // Resumed session SUCCEEDED; IDE-A hung up before result delivery — benign.
                    log.info("[Agent] IDE-A disconnected before result delivery for resumed session $sessionId")
                } else {
                    log.error("[Agent] Resumed delegated session $sessionId failed unexpectedly", e)
                    val durationSeconds = (System.currentTimeMillis() - startTime) / 1000
                    try {
                        onResult(delegationResultForDeliveryFailure(e, durationSeconds))
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                        throw ce
                    } catch (deliveryError: Exception) {
                        if (isBenignDeliveryDisconnect(deliveryError)) {
                            log.info("[Agent] IDE-A disconnected before FAILED-result delivery for resumed session $sessionId")
                        } else {
                            log.warn("[Agent] failed to deliver FAILED result for resumed session $sessionId", deliveryError)
                        }
                    }
                }
            } finally {
                inbound.unregisterSessionChannel(sessionId)
                inbound.stopIfTransientAndIdle(activeDelegatedSessions.decrementAndGet())
            }
        }
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
            backgroundToolExecutor.cancelAllForSession(task.sessionId)

            // Plan 3 cascade-cancel: close every open child delegation channel for
            // the session being canceled. Idempotent — outbound service tracks
            // closed handles and ignores repeats.
            val cancelingSessionId = currentSessionId
            if (cancelingSessionId != null) {
                try {
                    val outbound = project.getService(
                        com.workflow.orchestrator.agent.delegation.DelegationOutboundService::class.java
                    )
                    val closed = outbound.cancelAllForSession(cancelingSessionId, reason = "parent_canceled")
                    if (closed.isNotEmpty()) {
                        log.info("[AgentService] cascade-cancel closed ${closed.size} delegation handle(s) " +
                            "for session $cancelingSessionId: ${closed.joinToString(",")}")
                    }
                } catch (e: Exception) {
                    log.warn("[AgentService] cascade-cancel failed for session $cancelingSessionId", e)
                }
            }

            // TASK_CANCEL hook — observation only, fire-and-forget
            // Cline: TaskCancel is non-cancellable (observation only)
            if (hookManager.hasHooks(HookType.TASK_CANCEL)) {
                cs.launch(Dispatchers.IO) {
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
        // F3/F6 fix: capture and release the per-session state BEFORE
        // cancelCurrentTask(), which may null out currentSessionId.
        // This ensures releaseSessionState is always called even if
        // setPlanModeActive() would no-op because currentSessionId was
        // already cleared by a concurrent cancel.
        val sid = currentSessionId
        if (sid != null) releaseSessionState(sid)
        cancelCurrentTask()
        // NOT redundant with cancelCurrentTask's cancel: that one is activeTask-gated, so when the session
        // is idle (loop already exited after attempt_completion) it no-ops — yet a tool backgrounded before
        // completion is still running. New-chat must cancel those idle-session background jobs too.
        if (sid != null) backgroundToolExecutor.cancelAllForSession(sid)
        registry.resetActiveDeferred()
        ProcessRegistry.killAll()
        activeTask.set(null)
        _outputSpiller = null  // Clear session-scoped spiller so the next session gets a fresh one
        taskStore = null       // Clear session-scoped task store
        // Multimodal-agent Phase 3 — clear hoisted AttachmentStore so the
        // next session constructs a fresh one against its own session dir.
        activeAttachmentStore = null
        sessionRuntime.clear() // Drop per-conversation counters + token/cost running totals
        sessionDisposableHolder.resetSession()
    }

    /** UI "Move to background": detach the running tool so the loop stops awaiting it (it runs on). */
    fun moveToolToBackground(toolCallId: String): Boolean = backgroundToolExecutor.detach(toolCallId)

    // ── Dispose ────────────────────────────────────────────────────────────

    override fun dispose() {
        // Task 8 — drop the process-global MonitorBridge router FIRST (via the coordinator's
        // dispose) so neither this Project nor the capturing lambda (which retains the
        // coordinator/AgentService) leaks for the IDE's lifetime.
        monitorCoordinator.dispose()
        // Close the file logger first — before cancelCurrentTask() so the final
        // "session ended" log entries are flushed and the file descriptor is released.
        // Wrapped in try/catch: a logger failure on dispose must never surface to the
        // platform and cause a secondary error on project close.
        // Note: fileLogger is by lazy — if it was never accessed, close() is a no-op
        // because the lazy value was never initialized and the wrapper call is safe.
        try {
            fileLogger.close()
        } catch (e: Exception) {
            log.warn("[AgentService] Failed to close AgentFileLogger on dispose", e)
        }
        cancelCurrentTask()
        backgroundToolExecutor.dispose()
        ProcessRegistry.killAll()
        debugController?.dispose()
    }

    /**
     * Per-session mutable state, keyed by session ID. Populated lazily by
     * [sessionStateFor]. Cleared by [releaseSessionState] when a session ends.
     *
     * Phase 0 of the cross-IDE delegation feature — see
     * docs/superpowers/specs/2026-05-22-cross-ide-agent-delegation-design.md §6.1.
     */
    private val perSessionStates = java.util.concurrent.ConcurrentHashMap<
        String,
        com.workflow.orchestrator.agent.session.PerSessionAgentState
    >()

    /** Session ID currently driving this AgentService — set by [executeTask] / resume paths. */
    @Volatile
    private var currentSessionId: String? = null

    /**
     * Returns (creating if absent) the per-session state for the given session ID.
     * Used by tools and the loop that have a session ID in hand.
     *
     * Note: the state is always created with the default plan-mode (false). Callers
     * that need to seed a specific value must call `.planModeActive.set(value)` on
     * the returned instance explicitly. This prevents the previous `initialPlanMode`
     * parameter from being silently ignored when `computeIfAbsent` hits an existing
     * entry (F5 fix).
     */
    fun sessionStateFor(
        sessionId: String,
    ): com.workflow.orchestrator.agent.session.PerSessionAgentState =
        perSessionStates.computeIfAbsent(sessionId) {
            com.workflow.orchestrator.agent.session.PerSessionAgentState(it)
        }

    /**
     * Returns the per-session state for the session currently being driven, or
     * null if no session is active (e.g., between tasks). Used by call sites
     * that historically read `planModeActive.get()` directly.
     *
     * @Synchronized with [releaseSessionState] so neither observes a torn state
     * where currentSessionId is non-null but the map entry is already gone.
     */
    @Synchronized
    fun currentSessionState(): com.workflow.orchestrator.agent.session.PerSessionAgentState? =
        currentSessionId?.let { perSessionStates[it] }

    /**
     * Removes per-session state. Call when a session is closed/disposed.
     *
     * @Synchronized with [currentSessionState] so both methods observe a
     * consistent snapshot of (currentSessionId, perSessionStates).
     */
    @Synchronized
    fun releaseSessionState(sessionId: String) {
        perSessionStates.remove(sessionId)
        if (currentSessionId == sessionId) currentSessionId = null
    }

    /**
     * Current plan-mode flag, sourced from per-session state. Returns false
     * if no session is active (defensive default).
     */
    fun isPlanModeActive(): Boolean =
        currentSessionState()?.planModeActive?.get() ?: false

    /**
     * Set plan-mode for the current session. Updates the in-memory flag and
     * asynchronously persists to `sessions.json` so that a subsequent resume
     * sees the correct mode.
     */
    fun setPlanModeActive(enabled: Boolean) {
        val state = currentSessionState()
        if (state == null) {
            log.warn("setPlanModeActive($enabled) called with no active session — ignoring")
            return
        }
        state.planModeActive.set(enabled)
        // Persist to disk on a background coroutine so the UI thread is never
        // blocked by the file lock. Fire-and-forget — failures are non-fatal.
        val handler = activeMessageStateHandler ?: return
        cs.launch(Dispatchers.IO) {
            try {
                handler.updateSessionPlanMode(enabled)
            } catch (e: Exception) {
                log.warn("AgentService: failed to persist plan-mode toggle (non-fatal)", e)
            }
        }
    }

    companion object {
        /** Max chars of a backgrounded tool's output shown in its async-event card's detail pane. */
        private const val BACKGROUND_CARD_DETAIL_CHARS = 2000

        fun getInstance(project: Project): AgentService =
            project.service<AgentService>()

        /**
         * True when an exception thrown while delivering a delegated session's terminal
         * [com.workflow.orchestrator.core.delegation.DelegationMessage.Result] to IDE-A is a
         * benign "peer hung up, nothing to deliver" condition (IDE-A already closed its end of
         * the socket — closed the handle / let a `wait` lapse / cancelled). Reuses the single
         * source of truth in [com.workflow.orchestrator.core.delegation.DelegationFraming.isPeerDisconnect]
         * so the agent never diverges from the `:core` reply-boundary classification.
         *
         * When true, the detached completion coroutine logs at INFO and does NOT emit a FAILED
         * result — the session actually succeeded; only delivery had nowhere to land.
         */
        fun isBenignDeliveryDisconnect(e: Throwable): Boolean =
            com.workflow.orchestrator.core.delegation.DelegationFraming.isPeerDisconnect(e)

        /**
         * Pure mapping from a [LoopResult] produced by IDE-B's agent loop to the
         * [DelegationMessage.Result] that is sent back to IDE-A.
         *
         * Extracted from [startDelegatedSession] so it can be tested without
         * standing up a full agent context.  The full [LoopResult.Completed.summary]
         * and [LoopResult.SessionHandoff.context] are forwarded verbatim — no
         * truncation — so IDE-A's agent receives a complete answer.
         */
        /**
         * Incoming-task bubble text for a delegated session (IDE-B leg (a)). The
         * persisted `uiMessageOverride` and the live bubble that
         * [com.workflow.orchestrator.agent.ui.AgentController.runDelegatedNow] pushes
         * MUST use this exact text so the live render and the history render match.
         *
         * The old "[⬇ Delegated task · from {repo}]" text prefix was DROPPED — the
         * delegation is now conveyed by the per-bubble tint + accent stripe + the
         * "delegated · {repo}" pill (which carries the repo name), so the prefix was
         * redundant. This returns just the verbatim task text. The [metadata] param is
         * retained for signature stability and so callers stamp the delegated flag/repo
         * onto the UiMessage themselves (see [delegatedIncomingUiMessageOverride]).
         */
        fun delegatedIncomingTaskText(
            @Suppress("UNUSED_PARAMETER") metadata: com.workflow.orchestrator.agent.session.DelegationMetadata,
            request: String,
        ): String = request

        /**
         * The persisted leg-a USER_MESSAGE for a delegated session — carries the
         * `delegated`/`delegatorRepo` flags so reopening the session from history
         * renders the delegated pill + tint on the opening bubble (matching the live
         * render produced by `AgentCefPanel.startSessionDelegated`).
         */
        fun delegatedIncomingUiMessageOverride(
            metadata: com.workflow.orchestrator.agent.session.DelegationMetadata,
            request: String,
        ): com.workflow.orchestrator.agent.session.UiMessage =
            com.workflow.orchestrator.agent.session.UiMessage(
                ts = System.currentTimeMillis(),
                type = com.workflow.orchestrator.agent.session.UiMessageType.SAY,
                say = com.workflow.orchestrator.agent.session.UiSay.USER_MESSAGE,
                text = delegatedIncomingTaskText(metadata, request),
                delegated = true,
                delegatorRepo = metadata.delegatorRepo,
            )

        fun mapLoopResultToDelegationResult(
            loopResult: LoopResult,
            durationSeconds: Long,
        ): com.workflow.orchestrator.core.delegation.DelegationMessage.Result =
            when (loopResult) {
                is LoopResult.Completed -> com.workflow.orchestrator.core.delegation.DelegationMessage.Result(
                    status = com.workflow.orchestrator.core.delegation.DelegationMessage.ResultStatus.COMPLETED,
                    summary = loopResult.summary,
                    durationSeconds = durationSeconds,
                )
                is LoopResult.SessionHandoff -> com.workflow.orchestrator.core.delegation.DelegationMessage.Result(
                    status = com.workflow.orchestrator.core.delegation.DelegationMessage.ResultStatus.COMPLETED,
                    summary = loopResult.context,
                    durationSeconds = durationSeconds,
                )
                is LoopResult.Cancelled -> com.workflow.orchestrator.core.delegation.DelegationMessage.Result(
                    status = com.workflow.orchestrator.core.delegation.DelegationMessage.ResultStatus.CANCELED,
                    reason = "Session cancelled",
                    durationSeconds = durationSeconds,
                )
                is LoopResult.Failed -> com.workflow.orchestrator.core.delegation.DelegationMessage.Result(
                    status = com.workflow.orchestrator.core.delegation.DelegationMessage.ResultStatus.FAILED,
                    reason = loopResult.error,
                    durationSeconds = durationSeconds,
                )
            }

        /**
         * Pure mapping from a delivery-time failure (thrown while awaiting/delivering a delegated
         * session's terminal result) to the [DelegationMessage.Result] sent back to the delegator.
         * A [kotlinx.coroutines.CancellationException] → `CANCELED` (reason = its message); any
         * other throwable → `FAILED` (reason = its message, falling back to the qualified class
         * name when null). Extracted from the four near-identical catch-block constructions in
         * [startDelegatedSession] / [resumeDelegatedSession] so the mapping is unit-testable and
         * the two wrappers can't drift. The caller still owns rethrowing a `CancellationException`
         * after delivery and the benign-disconnect short-circuit.
         *
         * Placed AFTER [mapLoopResultToDelegationResult] on purpose: `DelegationConversationNarrationTest`
         * slices the source between `fun delegatedIncomingTaskText` and `fun mapLoopResultToDelegationResult`
         * and asserts that region never says "IDE-A"/"IDE-B"; keeping this function past that sentinel
         * preserves the slice boundary.
         */
        fun delegationResultForDeliveryFailure(
            e: Throwable,
            durationSeconds: Long,
        ): com.workflow.orchestrator.core.delegation.DelegationMessage.Result =
            if (e is kotlinx.coroutines.CancellationException) {
                com.workflow.orchestrator.core.delegation.DelegationMessage.Result(
                    status = com.workflow.orchestrator.core.delegation.DelegationMessage.ResultStatus.CANCELED,
                    reason = e.message,
                    durationSeconds = durationSeconds,
                )
            } else {
                com.workflow.orchestrator.core.delegation.DelegationMessage.Result(
                    status = com.workflow.orchestrator.core.delegation.DelegationMessage.ResultStatus.FAILED,
                    reason = e.message ?: e::class.qualifiedName,
                    durationSeconds = durationSeconds,
                )
            }
    }
}
