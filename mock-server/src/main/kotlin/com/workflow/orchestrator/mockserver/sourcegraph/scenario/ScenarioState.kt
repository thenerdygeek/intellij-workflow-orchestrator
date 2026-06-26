package com.workflow.orchestrator.mockserver.sourcegraph.scenario

import java.util.concurrent.ConcurrentHashMap

/**
 * Per-conversation scenario selection + turn index, plus the admin-settable default.
 *
 * **Keying strategy (the simpler robust option).** A conversation is identified by a stable hash of
 * its first user message (the agent re-sends the full history every turn, so that message is stable
 * across the turns of one conversation and differs between conversations). The turn index is a
 * monotonic counter that **resets to 0 on selection** — i.e. whenever the latest user message carries
 * a `[scenario]` tag. This makes re-running a tagged prompt replay from the top, while untagged
 * follow-up turns (tool results) advance the same conversation. Short scenarios never hit compaction,
 * so the first-user-message key stays stable for the whole run.
 */
class ScenarioState(
    @Volatile var defaultScenario: String = ScenarioLibrary.DEFAULT_SCENARIO,
) {

    /** Immutable per-request view of a conversation's resolved scenario + the turn index to serve. */
    data class ConvoView(val scenarioName: String, val turnIndex: Int)

    private data class Convo(val scenarioName: String, val turnIndex: Int)

    private val convos = ConcurrentHashMap<String, Convo>()

    /**
     * Resolve the conversation [convoKey] and atomically advance its turn index.
     *
     *  - [tagged] non-null → (re)select that scenario and reset the turn index to 0.
     *  - else if unseen → start the admin [defaultScenario] at turn 0.
     *  - else → keep the existing selection and serve the next index.
     *
     * Returns the [ConvoView] to serve (the pre-increment index); the stored index is bumped.
     */
    fun advance(convoKey: String, tagged: String?): ConvoView {
        var served: ConvoView? = null
        convos.compute(convoKey) { _, existing ->
            val current = when {
                tagged != null -> Convo(tagged, 0)
                existing == null -> Convo(defaultScenario, 0)
                else -> existing
            }
            served = ConvoView(current.scenarioName, current.turnIndex)
            current.copy(turnIndex = current.turnIndex + 1)
        }
        return served!!
    }

    /** Clear all per-conversation turn indices (admin reset). Keeps [defaultScenario]. */
    fun reset() = convos.clear()

    /** Number of active (seen) conversations — surfaced in the admin state dump. */
    fun conversationCount(): Int = convos.size
}
