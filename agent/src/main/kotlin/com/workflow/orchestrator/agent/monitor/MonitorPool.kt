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
        // TODO(Task 7/8): also call MonitorManager.forget(id) so a re-registered monitor with the same id starts clean (else stale dormant/autoStopped/flood state leaks).
        h.kill(); return true
    }

    /** Remove a monitor from the session pool WITHOUT killing it (used when the source exits on its own). */
    fun deregister(sessionId: String, id: String): Boolean = pools[sessionId]?.remove(id) != null

    /**
     * Mark a monitor EXITED (kept in the pool so status/list can inspect it), then prune old exited.
     * Non-suspend: called from the onExit callback which runs outside a coroutine.
     */
    fun markExited(sessionId: String, id: String, code: Int?) {
        val sp = pools[sessionId] ?: return
        sp[id]?.markExited(code)
        pruneExited(sp)
    }

    private fun pruneExited(sp: ConcurrentHashMap<String, MonitorHandle>) {
        val exitedHandles = sp.values.filter { it.state() == BackgroundState.EXITED }.sortedBy { it.startedAt }
        val toRemove = exitedHandles.size - MAX_EXITED_RETAINED
        if (toRemove > 0) exitedHandles.take(toRemove).forEach { sp.remove(it.bgId) }
    }

    fun killAll(sessionId: String) {
        pools.remove(sessionId)?.values?.forEach { runCatching { it.kill() } }
    }

    override fun dispose() { pools.values.forEach { sp -> sp.values.forEach { runCatching { it.kill() } } }; pools.clear() }
}
