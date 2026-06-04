package com.workflow.orchestrator.agent.monitor

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
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
        fun getInstance(project: Project): MonitorPool = project.service()
    }

    class MaxConcurrentReached(message: String) : RuntimeException(message)

    suspend fun register(sessionId: String, handle: MonitorHandle) = mutex.withLock {
        val sp = pools.getOrPut(sessionId) { ConcurrentHashMap() }
        if (sp.size >= MAX_PER_SESSION) {
            throw MaxConcurrentReached("Session '$sessionId' already has $MAX_PER_SESSION monitors. Stop one via monitor(action=stop).")
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

    fun killAll(sessionId: String) {
        pools.remove(sessionId)?.values?.forEach { runCatching { it.kill() } }
    }

    override fun dispose() { pools.values.forEach { sp -> sp.values.forEach { runCatching { it.kill() } } }; pools.clear() }
}
