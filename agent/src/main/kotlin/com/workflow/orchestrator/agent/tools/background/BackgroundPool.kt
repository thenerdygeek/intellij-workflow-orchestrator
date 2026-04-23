package com.workflow.orchestrator.agent.tools.background

import com.intellij.openapi.Disposable
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.agent.settings.AgentSettings
import java.util.concurrent.ConcurrentHashMap
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
    }

    fun get(sessionId: String, bgId: String): BackgroundHandle? =
        sessionPools[sessionId]?.get(bgId)

    fun list(sessionId: String): List<BackgroundHandle> =
        sessionPools[sessionId]?.list() ?: emptyList()

    fun size(sessionId: String): Int = sessionPools[sessionId]?.size() ?: 0

    fun killAll(sessionId: String) {
        sessionPools.remove(sessionId)?.killAll()
    }

    fun killAllForProject() {
        sessionPools.values.toList().forEach { it.killAll() }
        sessionPools.clear()
    }

    override fun dispose() {
        killAllForProject()
    }
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
