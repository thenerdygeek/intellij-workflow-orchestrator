package com.workflow.orchestrator.agent.tools.background

import java.util.concurrent.ConcurrentHashMap

/** Session-keyed index of live background-tool handles. Thread-safe; reads are snapshots. */
class BackgroundToolRegistry {
    private val bySession = ConcurrentHashMap<String, MutableSet<BackgroundToolHandle>>()

    fun add(handle: BackgroundToolHandle) {
        bySession.getOrPut(handle.sessionId) { ConcurrentHashMap.newKeySet() }.add(handle)
    }

    fun remove(handle: BackgroundToolHandle) {
        bySession[handle.sessionId]?.remove(handle)
    }

    /** Snapshot of live handles for [sessionId], oldest first. */
    fun list(sessionId: String): List<BackgroundToolHandle> =
        bySession[sessionId]?.sortedBy { it.startedAt } ?: emptyList()

    fun countForSession(sessionId: String): Int = bySession[sessionId]?.size ?: 0

    fun findByToolCallId(toolCallId: String): BackgroundToolHandle? =
        bySession.values.firstNotNullOfOrNull { set -> set.firstOrNull { it.toolCallId == toolCallId } }
}
