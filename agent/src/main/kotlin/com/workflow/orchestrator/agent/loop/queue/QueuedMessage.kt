package com.workflow.orchestrator.agent.loop.queue

import kotlinx.serialization.Serializable

/**
 * One async message awaiting injection into the agent loop. [body] is the RAW, UNFRAMED payload;
 * [QueueSourcePolicy.frame] wraps a same-source group for the LLM. [coalesceKey] non-null = an
 * earlier pending item with the same (kind, key) is replaced on enqueue (latest-wins).
 */
@Serializable
data class QueuedMessage(
    val id: String,
    val kind: QueueSourceKind,
    val body: String,
    val timestamp: Long,
    val priority: Int,
    val coalesceKey: String? = null,
    val meta: Map<String, String> = emptyMap(),
)

/** The result of draining one source's items: a single framed user-message section. */
data class DrainGroup(
    val kind: QueueSourceKind,
    val framedText: String,
    val ids: List<String>,
    val resetsUserSilenceCounter: Boolean,
    val defersCompletion: Boolean,
    val items: List<QueuedMessage> = emptyList(),
)
