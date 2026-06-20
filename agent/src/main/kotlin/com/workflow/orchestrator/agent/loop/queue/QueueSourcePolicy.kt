package com.workflow.orchestrator.agent.loop.queue

/** Per-source behaviour for the unified message queue. One object per [QueueSourceKind]. */
interface QueueSourcePolicy {
    val kind: QueueSourceKind
    /** Tiebreaker weight only — drain orders by timestamp first (see [UnifiedMessageQueue]). */
    val priority: Int
    /** Reset `iterationsSinceLastUser` on drain? ONLY USER = true. */
    val resetsUserSilenceCounter: Boolean
    /** When the session is idle, may this source's enqueue trigger a (budget-guarded) auto-wake? */
    val autoWakesIdle: Boolean
    /** Persist pending items to disk? USER = false (in-memory, preserves revert invariant). */
    val durable: Boolean
    /** Arriving mid-`attempt_completion` stream, does this source block the loop exit? */
    val defersCompletion: Boolean

    /** Build the framed user-message section for a same-source [group] (in drain order). */
    fun frame(group: List<QueuedMessage>): String
}
