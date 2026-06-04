package com.workflow.orchestrator.agent.monitor

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.tools.background.BackgroundState
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Session-scoped registry of [MonitorHandle]s, mirroring BackgroundPool but with its own
 * cap and no process semantics. A SEPARATE pool (not BackgroundPool) so monitors don't
 * consume the background-process cap or appear in background_process(list).
 */
@Service(Service.Level.PROJECT)
class MonitorPool(
    private val project: Project,
    // reserved for future async ops; required by the @Service constructor-injection contract (mirrors BackgroundPool)
    private val cs: CoroutineScope,
) : Disposable {
    private val pools = ConcurrentHashMap<String, ConcurrentHashMap<String, MonitorHandle>>()
    private val mutex = Mutex()

    /**
     * Invoked for each EXITED handle dropped by [pruneExited] so the owner (AgentService) can
     * forget that monitor's per-id MonitorManager state (pending/wakeBudget/dormant/autoStopped/
     * recentTimestamps). Wired in AgentService init to `forgetMonitor`. NOT invoked by
     * [markExited] itself — forgetting at exit time would clear the just-emitted 'process exited'
     * notification from the manager's pending queue before the 200 ms flush delivers it.
     */
    @Volatile var forgetCallback: ((sessionId: String, id: String) -> Unit)? = null

    companion object {
        const val MAX_PER_SESSION = 5
        /** Maximum number of EXITED handles retained per session for post-exit inspection. */
        const val MAX_EXITED_RETAINED = 10
        fun getInstance(project: Project): MonitorPool = project.service()
    }

    class MaxConcurrentReached(message: String) : RuntimeException(message)

    suspend fun register(sessionId: String, handle: MonitorHandle) = mutex.withLock {
        val sp = pools.getOrPut(sessionId) { ConcurrentHashMap() }
        val running = sp.values.count { it.state() == BackgroundState.RUNNING }
        if (running >= MAX_PER_SESSION) {
            throw MaxConcurrentReached("Session '$sessionId' already has $MAX_PER_SESSION running monitors. Stop one via monitor(action=stop).")
        }
        sp[handle.bgId] = handle
    }

    fun get(sessionId: String, id: String): MonitorHandle? = pools[sessionId]?.get(id)
    fun list(sessionId: String): List<MonitorHandle> = pools[sessionId]?.values?.toList() ?: emptyList()

    fun stop(sessionId: String, id: String): Boolean {
        val h = pools[sessionId]?.remove(id) ?: return false
        // Manager-forget on explicit stop is handled at the tool layer (MonitorTool.stop calls
        // AgentService.forgetMonitor); prune-forget is handled here via forgetCallback.
        h.kill(); return true
    }

    /**
     * Mark a monitor EXITED (kept in the pool so status/list can inspect it), then prune old exited.
     *
     * Intentionally lock-free (called from the onExit callback, which runs outside a coroutine).
     * Consequence: the RUNNING-count cap in [register] is a SOFT bound — a concurrent markExited
     * can cause at most a transient ±1 drift in the cap, which self-corrects on the next register.
     * No data-loss/crash risk: CHM ops are atomic and ids are unique UUIDs.
     */
    fun markExited(sessionId: String, id: String, code: Int?) {
        val sp = pools[sessionId] ?: return
        sp[id]?.markExited(code)
        pruneExited(sessionId, sp)
    }

    /**
     * Drop the oldest EXITED handles beyond [MAX_EXITED_RETAINED] (RUNNING handles are never
     * pruned), invoking [forgetCallback] for each dropped id so its MonitorManager per-id state
     * is forgotten. Deterministic ordering via (startedAt, bgId) so two handles sharing a
     * millisecond startedAt prune in a stable order. Lock-free — see [markExited].
     */
    private fun pruneExited(sessionId: String, sp: ConcurrentHashMap<String, MonitorHandle>) {
        val exitedHandles = sp.values.filter { it.state() == BackgroundState.EXITED }
            .sortedWith(compareBy({ it.startedAt }, { it.bgId }))
        val toRemove = exitedHandles.size - MAX_EXITED_RETAINED
        if (toRemove > 0) exitedHandles.take(toRemove).forEach {
            sp.remove(it.bgId)
            forgetCallback?.invoke(sessionId, it.bgId)
        }
    }

    fun killAll(sessionId: String) {
        pools.remove(sessionId)?.values?.forEach { runCatching { it.kill() } }
    }

    override fun dispose() { pools.values.forEach { sp -> sp.values.forEach { runCatching { it.kill() } } }; pools.clear() }
}
