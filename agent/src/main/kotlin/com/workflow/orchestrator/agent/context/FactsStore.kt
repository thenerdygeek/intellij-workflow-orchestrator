package com.workflow.orchestrator.agent.context

/**
 * Type of verified fact recorded during an agent session.
 * Facts survive context compression and provide a structured knowledge base
 * that the LLM can always reference, even after older messages are pruned.
 */
enum class FactType {
    FILE_READ, EDIT_MADE, CODE_PATTERN, ERROR_FOUND, COMMAND_RESULT, DISCOVERY
}

/**
 * A single verified fact — an append-only record of something the agent discovered or did.
 *
 * @param type Category of fact (file read, edit, pattern found, etc.)
 * @param path File path associated with the fact, if any
 * @param content Human-readable description of the fact (capped at 200 chars by callers)
 * @param iteration The ReAct loop iteration when this fact was recorded
 */
data class Fact(
    val type: FactType,
    val path: String?,
    val content: String,
    val iteration: Int
)

/**
 * Compression-proof structured knowledge store.
 *
 * Records verified facts from tool executions during an agent session.
 * Unlike conversation messages, facts are never dropped by context compression.
 * They are injected as an anchored system message via [EventSourcedContextBridge.updateFactsAnchor].
 *
 * Deduplication: Facts with the same (type, path) replace older entries,
 * keeping only the most recent version. Facts with null path are never deduped.
 *
 * Eviction: When [maxFacts] is exceeded, the oldest facts are removed (FIFO).
 *
 * THREAD SAFETY: Not thread-safe. Access from the ReAct loop's single coroutine context only.
 */
class FactsStore(private val maxFacts: Int = 50) {
    private val facts = mutableListOf<Fact>()

    /** Number of facts currently stored. */
    val size: Int get() = facts.size

    /**
     * Record a new fact. If a fact with the same (type, path) already exists
     * and path is non-null, the older entry is replaced.
     */
    fun record(fact: Fact) {
        // Dedup by (type, path) — same type + path replaces older
        if (fact.path != null) {
            facts.removeAll { it.type == fact.type && it.path == fact.path }
        }
        facts.add(fact)
        while (facts.size > maxFacts) facts.removeAt(0)
    }

    /**
     * Render all facts as a context string suitable for injection into the LLM prompt.
     * Returns empty string if no facts are recorded.
     */
    fun toContextString(): String {
        if (facts.isEmpty()) return ""
        return buildString {
            appendLine("<agent_facts>")
            appendLine("Verified facts from this session (compression-proof):")
            for (fact in facts) {
                val pathStr = if (fact.path != null) " ${fact.path}" else ""
                appendLine("- [${fact.type}]$pathStr: ${fact.content.take(200)}")
            }
            appendLine("</agent_facts>")
        }.trimEnd()
    }

    /**
     * Estimate token consumption of the current facts context string.
     * Returns 0 if no facts are recorded.
     */
    fun estimateTokens(): Int {
        val s = toContextString()
        return if (s.isEmpty()) 0 else TokenEstimator.estimate(s)
    }

    /** Clear all recorded facts. */
    fun clear() {
        facts.clear()
    }
}
