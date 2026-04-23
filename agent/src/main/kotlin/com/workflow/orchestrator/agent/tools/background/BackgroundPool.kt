package com.workflow.orchestrator.agent.tools.background

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.settings.AgentSettings
import com.workflow.orchestrator.core.events.BackgroundProcessSnapshotDto
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Project-level registry of background process handles, keyed by sessionId.
 *
 * Parented to the project Disposable so IDE/project close cascades kill all pools.
 * NOT parented to SessionDisposableHolder because that resets on stop / new chat —
 * session-transition kill is handled explicitly in AgentController.
 */
@Service(Service.Level.PROJECT)
class BackgroundPool(private val project: Project) : Disposable {

    private val sessionPools = ConcurrentHashMap<String, SessionPool>()

    companion object {
        private val LOG = Logger.getInstance(BackgroundPool::class.java)
        internal fun log(): Logger = LOG
        fun getInstance(project: Project): BackgroundPool = project.service()
    }

    class MaxConcurrentReached(message: String) : RuntimeException(message)

    fun forSession(sessionId: String): SessionPool =
        sessionPools.computeIfAbsent(sessionId) { SessionPool(sessionId) }

    suspend fun register(sessionId: String, handle: BackgroundHandle) {
        val cap = AgentSettings.getInstance(project).state.concurrentBackgroundProcessesPerSession
        val pool = forSession(sessionId)
        pool.register(handle, cap)
        emitSnapshot(sessionId)
    }

    fun get(sessionId: String, bgId: String): BackgroundHandle? =
        sessionPools[sessionId]?.get(bgId)

    fun list(sessionId: String): List<BackgroundHandle> =
        sessionPools[sessionId]?.list() ?: emptyList()

    fun size(sessionId: String): Int = sessionPools[sessionId]?.size() ?: 0

    fun killAll(sessionId: String) {
        sessionPools.remove(sessionId)?.killAll()
        emitSnapshot(sessionId)
    }

    fun killAllForProject() {
        sessionPools.values.toList().forEach { it.killAll() }
        sessionPools.clear()
    }

    override fun dispose() {
        stopSupervisor()
        killAllForProject()
        scope.cancel()
    }

    private val completionListeners = java.util.concurrent.CopyOnWriteArrayList<(BackgroundCompletionEvent) -> Unit>()

    fun addCompletionListener(listener: (BackgroundCompletionEvent) -> Unit) {
        completionListeners.add(listener)
    }

    fun removeCompletionListener(listener: (BackgroundCompletionEvent) -> Unit) {
        completionListeners.remove(listener)
    }

    fun emitCompletion(sessionId: String, event: BackgroundCompletionEvent) {
        // Remove from session pool first so list() no longer surfaces it.
        sessionPools[sessionId]?.remove(event.bgId)
        completionListeners.forEach { listener ->
            runCatching { listener(event) }.onFailure {
                LOG.warn("[BackgroundPool] completion listener failed: ${it.message}", it)
            }
        }
        emitSnapshot(sessionId)
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var supervisorJob: Job? = null

    /**
     * Build a snapshot of all current handles for [sessionId] and emit a
     * [WorkflowEvent.BackgroundChanged] on the [EventBus].  Best-effort: if the
     * EventBus service is unavailable (unit tests without a project container) the
     * call is silently swallowed.
     */
    private fun emitSnapshot(sessionId: String) {
        val snapshot = list(sessionId).map { h ->
            BackgroundProcessSnapshotDto(
                bgId = h.bgId,
                kind = h.kind,
                label = h.label,
                state = h.state().name,
                startedAt = h.startedAt,
                exitCode = h.exitCode(),
                outputBytes = h.outputBytes(),
                runtimeMs = h.runtimeMs(),
            )
        }
        LOG.info("[BackgroundPool] emitSnapshot session=$sessionId count=${snapshot.size} " +
            "bgIds=${snapshot.map { it.bgId }}")
        scope.launch {
            runCatching {
                val bus = project.getService(EventBus::class.java)
                if (bus == null) {
                    LOG.warn("[BackgroundPool] EventBus service is null — event not emitted (session=$sessionId)")
                } else {
                    bus.emit(WorkflowEvent.BackgroundChanged(sessionId, snapshot))
                }
            }.onFailure {
                LOG.warn("[BackgroundPool] emitSnapshot failed for session $sessionId: ${it.message}", it)
            }
        }
    }

    fun startSupervisor(pollIntervalMs: Long = 500) {
        if (supervisorJob?.isActive == true) return
        supervisorJob = scope.launch {
            while (isActive) {
                runCatching { tick() }.onFailure {
                    LOG.warn("[BackgroundPool] supervisor tick failed: ${it.message}", it)
                }
                delay(pollIntervalMs)
            }
        }
    }

    fun stopSupervisor() { supervisorJob?.cancel(); supervisorJob = null }

    // Test-only tight polling — stops any running supervisor first so interval takes effect.
    fun startSupervisorForTest() { stopSupervisor(); startSupervisor(50) }
    fun stopSupervisorForTest() = stopSupervisor()

    private fun tick() {
        sessionPools.forEach { (sessionId, sp) ->
            sp.list().forEach { handle ->
                if (handle.state() != BackgroundState.RUNNING) {
                    val ev = BackgroundCompletionEvent(
                        bgId = handle.bgId,
                        kind = handle.kind,
                        label = handle.label,
                        sessionId = sessionId,
                        exitCode = handle.exitCode() ?: -1,
                        state = handle.state(),
                        runtimeMs = handle.runtimeMs(),
                        tailContent = handle.readOutput(tailLines = 20).content,
                        spillPath = null,
                        occurredAt = System.currentTimeMillis(),
                    )
                    emitCompletion(sessionId, ev)
                    if (handle is RunCommandBackgroundHandle) handle.fireCompletion()
                }
            }
        }
    }

    init { startSupervisor() }
}

class SessionPool(val sessionId: String) {
    private val handles = ConcurrentHashMap<String, BackgroundHandle>()
    val mutex = Mutex()

    suspend fun register(handle: BackgroundHandle, cap: Int) {
        mutex.withLock {
            if (handles.size >= cap) {
                throw BackgroundPool.MaxConcurrentReached(
                    "Session '$sessionId' already has $cap background processes (cap). " +
                        "Kill one via background_process(action=kill) before launching another."
                )
            }
            handles[handle.bgId] = handle
        }
    }

    fun get(bgId: String): BackgroundHandle? = handles[bgId]
    fun list(): List<BackgroundHandle> = handles.values.toList()
    fun size(): Int = handles.size
    fun remove(bgId: String): BackgroundHandle? = handles.remove(bgId)

    fun killAll() {
        handles.values.toList().forEach { handle ->
            runCatching { handle.kill() }.onFailure { e ->
                BackgroundPool.log().warn(
                    "[SessionPool:$sessionId] kill failed for bgId=${handle.bgId}: ${e.message}", e
                )
            }
        }
        handles.clear()
    }
}
