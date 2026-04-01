package com.workflow.orchestrator.agent.runtime

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * Coroutine context element carrying worker identity and shared coordination
 * infrastructure to all tools executing within a worker's scope.
 *
 * Set via `withContext(WorkerContext(...))` in [WorkerSession.execute].
 * Read by tools via `coroutineContext[WorkerContext]`.
 *
 * @param agentId The worker's agent ID, or null for the orchestrator.
 * @param workerType The worker's type (determines tool access).
 * @param messageBus The session's message bus for inter-agent communication.
 * @param fileOwnership The session's file ownership registry for conflict prevention.
 */
data class WorkerContext(
    val agentId: String?,
    val workerType: WorkerType,
    val messageBus: WorkerMessageBus?,
    val fileOwnership: FileOwnershipRegistry?
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<WorkerContext>

    /** True if this context belongs to the orchestrator (main agent), not a spawned worker. */
    val isOrchestrator: Boolean get() = agentId == null
}
