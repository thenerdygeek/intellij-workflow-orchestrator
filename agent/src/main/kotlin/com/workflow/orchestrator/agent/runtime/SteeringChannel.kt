package com.workflow.orchestrator.agent.runtime

import java.util.LinkedList

/**
 * Thread-safe channel for user steering messages sent while the agent is working.
 *
 * Messages are enqueued from the UI thread (EDT via JCEF bridge) and drained
 * at iteration boundaries in [SingleAgentSession.execute]. This implements
 * boundary-aware queuing: messages wait for the current tool execution to complete,
 * then inject before the next LLM call.
 *
 * All mutation methods are [Synchronized] to prevent race conditions between
 * the UI thread (enqueue/remove) and the ReAct loop coroutine (drain).
 */
class SteeringChannel {

    /**
     * A steering message from the user, sent while the agent was working.
     */
    data class SteeringMessage(
        val id: String = java.util.UUID.randomUUID().toString().take(12),
        val content: String,
        val timestampMs: Long = System.currentTimeMillis()
    )

    private val queue = LinkedList<SteeringMessage>()

    /**
     * Enqueue a steering message. Called from UI thread — must be non-blocking.
     * Returns the message ID for UI tracking (queued → delivered promotion).
     */
    @Synchronized
    fun enqueue(content: String): String {
        val msg = SteeringMessage(content = content)
        queue.add(msg)
        return msg.id
    }

    /**
     * Remove a specific queued message by ID (e.g. user cancelled a queued steering message).
     * Returns the removed message, or null if not found (already drained or invalid ID).
     */
    @Synchronized
    fun remove(id: String): SteeringMessage? {
        val iter = queue.iterator()
        while (iter.hasNext()) {
            val msg = iter.next()
            if (msg.id == id) {
                iter.remove()
                return msg
            }
        }
        return null
    }

    /**
     * Drain all currently pending messages. Returns empty list if none.
     * Called from the ReAct loop coroutine at iteration boundaries.
     */
    @Synchronized
    fun drain(): List<SteeringMessage> {
        val result = queue.toList()
        queue.clear()
        return result
    }

    /**
     * Check if there are pending messages without consuming them.
     */
    @Synchronized
    fun hasPending(): Boolean = queue.isNotEmpty()

    /**
     * Clear all pending messages (used on session reset / new chat).
     */
    @Synchronized
    fun clear() {
        queue.clear()
    }
}
