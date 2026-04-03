package com.workflow.orchestrator.agent.runtime

import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Thread-safe channel for user steering messages sent while the agent is working.
 *
 * Messages are enqueued from the UI thread (EDT via JCEF bridge) and drained
 * at iteration boundaries in [SingleAgentSession.execute]. This implements
 * boundary-aware queuing: messages wait for the current tool execution to complete,
 * then inject before the next LLM call.
 *
 * Design matches Claude Code's h2A async queue pattern — dual path with
 * immediate drain when consumer is waiting, buffered accumulation otherwise.
 */
class SteeringChannel {

    /**
     * A steering message from the user, sent while the agent was working.
     */
    data class SteeringMessage(
        val content: String,
        val timestampMs: Long = System.currentTimeMillis()
    )

    private val queue = ConcurrentLinkedQueue<SteeringMessage>()

    /**
     * Enqueue a steering message. Called from UI thread — must be non-blocking.
     */
    fun enqueue(content: String) {
        queue.add(SteeringMessage(content = content))
    }

    /**
     * Drain all pending messages atomically. Returns empty list if none.
     * Called from the ReAct loop coroutine at iteration boundaries.
     */
    fun drain(): List<SteeringMessage> {
        val result = mutableListOf<SteeringMessage>()
        while (true) {
            val msg = queue.poll() ?: break
            result.add(msg)
        }
        return result
    }

    /**
     * Check if there are pending messages without consuming them.
     */
    fun hasPending(): Boolean = queue.isNotEmpty()

    /**
     * Clear all pending messages (used on session reset / new chat).
     */
    fun clear() {
        queue.clear()
    }
}
