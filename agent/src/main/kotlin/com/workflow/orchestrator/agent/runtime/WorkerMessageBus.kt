package com.workflow.orchestrator.agent.runtime

import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.ConcurrentHashMap

enum class MessageType {
    /** Parent → child: "focus on X", "skip Y", "use approach Z" */
    INSTRUCTION,
    /** Child → parent: "discovered breaking change in X" */
    FINDING,
    /** Child → parent: "completed 3/5 files", "blocked on tests" */
    STATUS_UPDATE,
    /** System-generated: "edit denied, file locked by agent-xyz" */
    FILE_CONFLICT
}

data class WorkerMessage(
    val from: String,
    val to: String,
    val type: MessageType,
    val content: String,
    val metadata: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Per-session message bus enabling bidirectional communication between the parent
 * orchestrator and worker sessions via bounded Kotlin [Channel]s.
 *
 * Each worker gets an inbox on spawn. The orchestrator has a permanent inbox keyed
 * by [ORCHESTRATOR_ID]. Messages are consumed at ReAct loop iteration boundaries
 * (not instant delivery) — this is the natural attention point between LLM calls.
 *
 * Channel capacity is bounded at [INBOX_CAPACITY] with [BufferOverflow.DROP_OLDEST]
 * to prevent unbounded memory growth if a worker ignores messages.
 */
class WorkerMessageBus {
    companion object {
        const val ORCHESTRATOR_ID = "orchestrator"
        private const val INBOX_CAPACITY = 20
        private val LOG = Logger.getInstance(WorkerMessageBus::class.java)
    }

    private val inboxes = ConcurrentHashMap<String, Channel<WorkerMessage>>()

    /** Create an inbox for a worker or the orchestrator. */
    fun createInbox(agentId: String): Channel<WorkerMessage> {
        val channel = Channel<WorkerMessage>(
            capacity = INBOX_CAPACITY,
            onBufferOverflow = BufferOverflow.DROP_OLDEST
        )
        inboxes[agentId] = channel
        LOG.info("WorkerMessageBus: created inbox for $agentId")
        return channel
    }

    /** Close and remove an inbox. Logs any unread messages. */
    fun closeInbox(agentId: String) {
        val channel = inboxes.remove(agentId)
        if (channel != null) {
            val remaining = mutableListOf<WorkerMessage>()
            while (true) {
                val msg = channel.tryReceive().getOrNull() ?: break
                remaining.add(msg)
            }
            channel.close()
            if (remaining.isNotEmpty()) {
                LOG.info("WorkerMessageBus: closed inbox for $agentId with ${remaining.size} unread messages")
            }
        }
    }

    /** Send a message to a worker's inbox. Returns false if the inbox doesn't exist or is closed. */
    fun send(message: WorkerMessage): Boolean {
        val channel = inboxes[message.to] ?: return false
        return try {
            channel.trySend(message).isSuccess
        } catch (_: Exception) {
            false
        }
    }

    /** Non-blocking drain of all pending messages for an agent. Returns empty if no inbox or no messages. */
    fun drain(agentId: String): List<WorkerMessage> {
        val channel = inboxes[agentId] ?: return emptyList()
        val messages = mutableListOf<WorkerMessage>()
        while (true) {
            val msg = channel.tryReceive().getOrNull() ?: break
            messages.add(msg)
        }
        return messages
    }

    /** Peek whether an inbox has pending messages without consuming them. */
    fun hasPending(agentId: String): Boolean {
        val channel = inboxes[agentId] ?: return false
        return !channel.isEmpty
    }

    /** Close all inboxes. Called on session end. */
    fun close() {
        val ids = inboxes.keys.toList()
        ids.forEach { closeInbox(it) }
    }
}
