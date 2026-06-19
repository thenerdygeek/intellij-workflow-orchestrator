package com.workflow.orchestrator.agent.monitor

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.loop.AgentLoop
import com.workflow.orchestrator.agent.loop.queue.MonitorQueuePolicy
import com.workflow.orchestrator.agent.loop.queue.QueueSourceKind
import com.workflow.orchestrator.agent.loop.queue.QueuedMessage
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.agent.tools.background.IdleSessionWaker
import com.workflow.orchestrator.agent.tools.integration.ServiceLookup
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.services.BitbucketService
import com.workflow.orchestrator.core.services.JiraService
import com.workflow.orchestrator.core.services.SonarService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

/**
 * Per-session coordinator for the proactive [monitor framework][MonitorManager] — extracted
 * from `AgentService` (Phase 3 architecture decomposition). Owns all monitor state that no other
 * cluster of `AgentService` touches: the per-session [MonitorManager] map and the
 * [MonitorPersistence] handle. Companion to [MonitorManager] / [MonitorPool] / [MonitorBridge] /
 * [MonitorPersistence].
 *
 * The side effects that reach back into the loop are injected so this class is unit-instantiable
 * (unlike its previous home, a `@Service(PROJECT)` god-class that needs the full platform):
 *  - [activeLoopForSession] — looks up the live [AgentLoop] for a session (drives each manager's
 *    `isLoopLive` / `deliverToLoop`);
 *  - [idleWaker] — the shared idle-waker, so monitor wakes honour the SAME global guard
 *    cap/cooldown as background-completion and delegation wakes;
 *  - [agentDirProvider] — supplies the agent base dir lazily. `AgentService.agentDir` is
 *    `lateinit` (set during tool registration, after construction), so this MUST be a provider —
 *    [monitorPersistence] reads it only on first access, well after registration.
 *
 * Construction wires the process-global [MonitorBridge] router; the 200 ms flush loop starts
 * lazily with the first monitor and stops when the last session's monitors are disposed. [dispose]
 * tears the router down. `AgentService` constructs one instance and delegates its monitor methods
 * to it, so existing callers (`AgentController`, `MonitorTool`, `resumeSession`) are unchanged.
 */
class AgentMonitorCoordinator(
    private val project: Project,
    private val cs: CoroutineScope,
    private val agentDirProvider: () -> File,
    private val idleWaker: IdleSessionWaker,
    private val activeLoopForSession: (String) -> AgentLoop?,
    private val enqueueToQueue: (sessionId: String, msg: QueuedMessage) -> Unit = { _, _ -> },
) : Disposable {

    private val log = Logger.getInstance(AgentMonitorCoordinator::class.java)

    /**
     * Task 8 — per-session [MonitorManager] coordinators. Created lazily on first event
     * for a session, dropped via [disposeMonitorsForSession] when the session is left.
     */
    private val monitorManagers = java.util.concurrent.ConcurrentHashMap<String, MonitorManager>()

    private var flushJob: Job? = null

    /** Visible for tests: whether the 200ms coalesce-flush loop is currently live. */
    internal fun isFlushLoopRunning(): Boolean = flushJob?.isActive == true

    /**
     * P1-6: the flush loop used to start in `init` and tick 5x/s for the project lifetime
     * even with zero monitors. It now starts lazily with the first MonitorManager and is
     * cancelled when the last session's managers are disposed.
     */
    @Synchronized
    private fun ensureFlushLoop() {
        if (flushJob?.isActive == true) return
        // Re-check under the lock: a concurrent disposeMonitorsForSession may have removed
        // the just-created manager — don't start a loop that has nothing to flush.
        if (monitorManagers.isEmpty()) return
        flushJob = cs.launch(Dispatchers.IO) {
            while (isActive) {
                // Per-manager isolation: one bad manager's flush must not starve the others.
                monitorManagers.values.forEach { m ->
                    runCatching { m.flushDue() }.onFailure { log.warn("monitor flush failed: ${it.message}", it) }
                }
                delay(FLUSH_INTERVAL_MS)
            }
        }
    }

    @Synchronized
    private fun stopFlushLoopIfIdle() {
        if (monitorManagers.isEmpty()) {
            flushJob?.cancel()
            flushJob = null
        }
    }

    /**
     * Lazy [MonitorPersistence] over the agent dir. Constructed on first access so it is
     * always available after the owning service has set its `agentDir`. Mirrors the
     * `BackgroundPersistence` pattern: the provided dir's path is the sessions base dir.
     */
    private val monitorPersistence: MonitorPersistence by lazy { MonitorPersistence(agentDirProvider().toPath()) }

    init {
        // Task 8 — route MonitorBridge events into the per-session MonitorManager.
        // The coalesce flush loop is NOT started here — see ensureFlushLoop() (P1-6:
        // starts with the first manager, stops when the last session's managers go).
        // Get-only: a late event for a disposed session must NOT recreate its manager
        // (zombie-session resurrection). The manager is pre-created at monitor-start via
        // ensureMonitorManager(); MonitorTool.start calls it before the source emits.
        MonitorBridge.setRouter(project) { sessionId, event ->
            monitorManagers[sessionId]?.onEvent(event)
        }
        // When MonitorPool prunes an old EXITED handle, forget its per-id MonitorManager state
        // (pending/wakeBudget/dormant/autoStopped/recentTimestamps) so the slot doesn't leak.
        MonitorPool.getInstance(project).forgetCallback = { sid, id -> forgetMonitor(sid, id) }
    }

    /**
     * Build a kind=MONITOR [QueuedMessage] from coalesced [text]. No coalesceKey is set because
     * the monitorId is not available at this callback level; [MonitorManager] coalesces upstream
     * so queue-level deduplication is intentionally skipped.
     */
    private fun monitorMsg(text: String): QueuedMessage = QueuedMessage(
        id = "mon-${System.nanoTime()}",
        kind = QueueSourceKind.MONITOR,
        body = text,
        timestamp = System.currentTimeMillis(),
        priority = MonitorQueuePolicy.priority,
        coalesceKey = null,
    )

    /**
     * Build (or fetch) the [MonitorManager] for [sessionId]. All side effects are injected
     * so monitor routing reuses the live-loop steering path and the shared idle-waker
     * (guard cap/cooldown honoured across background + delegation + monitor wakes).
     */
    private fun monitorManagerFor(sessionId: String): MonitorManager =
        monitorManagers.computeIfAbsent(sessionId) {
            val st = AgentSettings.getInstance(project).state
            MonitorManager(
                config = MonitorConfig(
                    coalesceWindowMs = st.monitorCoalesceWindowMs,
                    wakeBudgetPerMonitor = st.monitorWakeBudgetPerMonitor,
                    floodThresholdPerMin = st.monitorFloodThresholdPerMin,
                ),
                clock = { System.currentTimeMillis() },
                // Documented TOCTOU: if the loop exits between isLoopLive() and deliverToLoop(),
                // the `?.` drops the message safely — acceptable, the loop was terminating anyway.
                isLoopLive = { activeLoopForSession(sessionId) != null },
                deliverToLoop = { text -> enqueueToQueue(sessionId, monitorMsg(text)) },
                wakeIdle = { text ->
                    // Task 2.4 — durable persist via the queue (MonitorQueuePolicy.durable=true)
                    // replaces appendPendingNotification. The queue's QueuePersistence atomically
                    // writes pending_queue.json before the wake is attempted, so the notification
                    // survives a SKIP_GUARD or DEFER route (Task 6F's resume reader is Task 2.5).
                    enqueueToQueue(sessionId, monitorMsg(text))
                    // The blank synthetic text is the carrier for the wake signal; the actual
                    // notification body is already in the queue item above. The WakeOutcome
                    // MUST still be returned so the per-monitor wake-budget/flood accounting works.
                    wakeOutcomeFor(idleWaker.wake(sessionId, "", "monitor"))
                },
                onFloodStop = { id ->
                    MonitorPool.getInstance(project).stop(sessionId, id)
                    monitorManagers[sessionId]?.forget(id)
                    monitorPersistence.remove(sessionId, id)
                },
            )
        }.also { ensureFlushLoop() }

    /**
     * Pre-create the [MonitorManager] for [sessionId] at monitor-start. Paired with the
     * get-only bridge router (`monitorManagers[sessionId]?.onEvent`) so a late event from a
     * just-killed process cannot resurrect a manager for a disposed session.
     */
    fun ensureMonitorManager(sessionId: String) { monitorManagerFor(sessionId) }

    /** Drop a session's [MonitorManager] and kill its live monitor sources. */
    fun disposeMonitorsForSession(sessionId: String) {
        monitorManagers.remove(sessionId)
        MonitorPool.getInstance(project).killAll(sessionId)
        stopFlushLoopIfIdle()
    }

    /** Forget a single monitor's coalesce/wake/flood state after it is stopped. */
    fun forgetMonitor(sessionId: String, id: String) {
        monitorManagers[sessionId]?.forget(id)
    }

    /** Mark all monitors for [sessionId] dormant on abnormal loop exit (max-iter/cancel/fail). */
    fun markMonitorsDormantForSession(sessionId: String) { monitorManagers[sessionId]?.markAllDormant() }

    /**
     * Clear persisted monitor specs and pending notifications for [sessionId].
     *
     * Called on new-chat / session-switch transitions so a fresh session does not inherit stale
     * monitors from the previous session.  Both files are deleted idempotently; a missing file
     * is not an error.  The whole body is runCatching-wrapped so a persistence failure never
     * propagates to the caller (mirrors [disposeMonitorsForSession]'s silent contract).
     */
    fun clearPersistedMonitors(sessionId: String) {
        runCatching {
            monitorPersistence.clear(sessionId)
            monitorPersistence.clearPendingNotifications(sessionId)
        }.onFailure { e ->
            log.warn("[AgentMonitorCoordinator] clearPersistedMonitors failed for $sessionId: ${e.message}", e)
        }
    }

    /** Pending monitor notifications persisted while [sessionId] was paused (drained on resume). */
    fun loadPendingNotifications(sessionId: String): List<String> =
        monitorPersistence.loadPendingNotifications(sessionId)

    /** Clear the persisted pending notifications for [sessionId] after they are drained on resume. */
    fun clearPendingNotifications(sessionId: String) {
        monitorPersistence.clearPendingNotifications(sessionId)
    }

    /**
     * Re-arm all monitors that were persisted to [MonitorPersistence] for [sessionId].
     *
     * Called on session RESUME so watches that were active before the session was paused/killed
     * continue without the LLM having to re-issue `monitor(action=start)`.  Each monitor is
     * re-armed with the SAME id so prior `monitor stop <id>` references still resolve.
     *
     * The entire body is wrapped in runCatching so a re-arm failure never aborts the resume
     * (spec contract: re-arm failure MUST NOT break resume).
     *
     * ### Task 6B dormancy reset
     * Before starting the source we call `manager.forget(spec.id)` to clear any stale dormancy
     * state that was stamped on the id during an abnormal loop exit (markAllDormant).  Without
     * this reset a re-armed monitor would remain dormant and could never fire an idle-wake on
     * the freshly resumed session.
     */
    suspend fun reArmMonitors(sessionId: String) {
        runCatching {
            val specs = monitorPersistence.load(sessionId)
            if (specs.isEmpty()) return@runCatching
            log.info("reArmMonitors: re-arming ${specs.size} monitor(s) for $sessionId")
            val pool = MonitorPool.getInstance(project)
            // Provider lambdas mirror MonitorTool's constructor defaults exactly.
            val bambooProvider: (Project) -> BambooService? = { p -> ServiceLookup.bamboo(p) }
            val bitbucketProvider: (Project) -> BitbucketService? = { p -> ServiceLookup.bitbucket(p) }
            val jiraProvider: (Project) -> JiraService? = { p -> ServiceLookup.jira(p) }
            val sonarProvider: (Project) -> SonarService? = { p -> ServiceLookup.sonar(p) }
            val eventBusProvider: (Project) -> SharedFlow<WorkflowEvent>? =
                { p -> p.service<EventBus>().events }

            for (spec in specs) {
                // Build the shell onExit callback that mirrors MonitorTool.startShell's onExit lambda.
                val onShellExit: (Int?) -> Unit = { code ->
                    val sev = if (code == 0) Severity.NOTABLE else Severity.ALERT
                    MonitorBridge.emit(
                        project,
                        sessionId,
                        MonitorEvent(spec.id, sev, "process exited (code=${code ?: "unknown"})"),
                    )
                    pool.markExited(sessionId, spec.id, code)
                    monitorPersistence.remove(sessionId, spec.id)
                }
                val result = MonitorSourceFactory.build(
                    spec, project, cs,
                    bambooProvider, bitbucketProvider, jiraProvider, sonarProvider, eventBusProvider,
                    onShellExit = onShellExit,
                )
                when (result) {
                    is MonitorSourceFactory.BuildResult.Failed -> {
                        log.warn("reArmMonitors: build failed for ${spec.id} (${spec.sourceType}): ${result.error}")
                        // Don't re-persist the failure; just skip this monitor.
                    }
                    is MonitorSourceFactory.BuildResult.Built -> {
                        val src = result.source
                        val handle = MonitorHandle(src, sessionId, System.currentTimeMillis())
                        try {
                            pool.register(sessionId, handle)
                        } catch (e: MonitorPool.MaxConcurrentReached) {
                            src.stop()
                            log.warn("reArmMonitors: cap reached for $sessionId, skipping ${spec.id}: ${e.message}")
                            continue
                        }
                        // Pre-create the manager BEFORE start so the bridge router is live.
                        ensureMonitorManager(sessionId)
                        // Task 6B: reset any stale dormancy state so the re-armed monitor can
                        // wake the freshly resumed session (markAllDormant left it dormant on
                        // the previous abnormal exit; forget() clears all per-id state).
                        monitorManagers[sessionId]?.forget(spec.id)
                        // P1-8 (W5-C1 review): mirror MonitorTool.startBamboo — a terminal
                        // self-stop marks the pool handle EXITED. Persistence intentionally
                        // NOT removed (first-poll rule keeps waiting-for-next-build semantics
                        // across resumes).
                        (src as? PollingSource<*>)?.onSelfStop = { pool.markExited(sessionId, spec.id, null) }
                        src.start { event ->
                            handle.appendLine(event.formatLine())
                            MonitorBridge.emit(project, sessionId, event)
                        }
                        log.info("reArmMonitors: re-armed ${spec.id} (${spec.sourceType}) for $sessionId")
                    }
                }
            }
        }.onFailure { e ->
            log.warn("reArmMonitors failed for $sessionId — resume continues unaffected: ${e.message}", e)
        }
    }

    /**
     * Drop the process-global [MonitorBridge] router so neither this Project nor the capturing
     * lambda leaks for the IDE's lifetime. Called FIRST from the owning service's `dispose()`.
     */
    override fun dispose() {
        MonitorBridge.clearRouter(project)
    }

    companion object {
        /** Cadence of the coalesce-window flush loop — how often each manager's `flushDue()` runs. */
        private const val FLUSH_INTERVAL_MS = 200L
    }
}
