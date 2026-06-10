package com.workflow.orchestrator.agent.observability

/**
 * Pure gate for the 30s "smart working phrase" timer (audit P1-10): each tick used to fire
 * a real Haiku LLM round-trip even while the loop sat suspended on an approval or question.
 * The timer now skips the call when the agent's observable activity hasn't changed.
 */
object PhraseActivityGate {

    private const val HASH_PRIME = 31

    /** Cheap fingerprint of "what the agent has done lately". */
    fun signature(recentTools: List<Any?>, streamSnippet: String): Int =
        recentTools.hashCode() * HASH_PRIME + streamSnippet.hashCode()

    /** Generate on the first tick, and whenever activity changed since the previous tick. */
    fun shouldGenerate(previousSignature: Int?, signature: Int): Boolean =
        previousSignature == null || previousSignature != signature
}
