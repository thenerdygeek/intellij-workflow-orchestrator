package com.workflow.orchestrator.agent.tools.builtin

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [SpawnAgentTool.composePromptPairs] — the pure prompt-composition
 * stage of parallel sub-agent dispatch.
 *
 * Context: each sub-agent starts with a FRESH context and cannot see the parent's other
 * prompts. Before the `shared_prompt` field existed, fanning N sub-agents over the same
 * large payload forced the model to duplicate that payload into every prompt_N. The model
 * "optimized" by writing a placeholder like "[Same prompt as above …]" into prompt_2/4,
 * which the dispatcher passed through literally — so those workers got the placeholder, not
 * the work. `shared_prompt` lets the payload be stated once and prepended to every branch.
 */
class SpawnAgentToolPromptCompositionTest {

    private fun params(vararg pairs: Pair<String, String>): JsonObject =
        JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })

    private fun resolved(p: SpawnAgentTool.PromptComposition): List<Pair<String, String>> {
        assertTrue(p is SpawnAgentTool.PromptComposition.Resolved, "Expected Resolved, got $p")
        return (p as SpawnAgentTool.PromptComposition.Resolved).pairs
    }

    @Test
    fun `shared_prompt is prepended to every parallel branch`() {
        val result = SpawnAgentTool.composePromptPairs(
            params(
                "description" to "Multi-angle review",
                "shared_prompt" to "PAYLOAD: the 4000-word spec text.",
                "prompt" to "Analyze the auth layer.",
                "prompt_2" to "Analyze the data layer.",
                "prompt_3" to "Analyze the UI layer."
            ),
            isReadOnly = true
        )

        val pairs = resolved(result)
        assertEquals(3, pairs.size)
        assertEquals("PAYLOAD: the 4000-word spec text.\n\nAnalyze the auth layer.", pairs[0].first)
        assertEquals("PAYLOAD: the 4000-word spec text.\n\nAnalyze the data layer.", pairs[1].first)
        assertEquals("PAYLOAD: the 4000-word spec text.\n\nAnalyze the UI layer.", pairs[2].first)
    }

    @Test
    fun `absent shared_prompt leaves prompts byte-for-byte unchanged`() {
        val result = SpawnAgentTool.composePromptPairs(
            params(
                "description" to "Multi",
                "prompt" to "Question 1",
                "prompt_2" to "Question 2"
            ),
            isReadOnly = true
        )

        val pairs = resolved(result)
        assertEquals(listOf("Question 1", "Question 2"), pairs.map { it.first })
    }

    @Test
    fun `blank shared_prompt is treated as absent`() {
        val result = SpawnAgentTool.composePromptPairs(
            params(
                "description" to "Multi",
                "shared_prompt" to "   ",
                "prompt" to "Question 1",
                "prompt_2" to "Question 2"
            ),
            isReadOnly = true
        )

        assertEquals(listOf("Question 1", "Question 2"), resolved(result).map { it.first })
    }

    @Test
    fun `single dispatch composes shared_prompt with the primary prompt`() {
        val result = SpawnAgentTool.composePromptPairs(
            params(
                "description" to "One agent",
                "shared_prompt" to "CONTEXT",
                "prompt" to "Do the thing."
            ),
            isReadOnly = false
        )

        val pairs = resolved(result)
        assertEquals(1, pairs.size)
        assertEquals("CONTEXT\n\nDo the thing.", pairs[0].first)
    }

    @Test
    fun `write-capable agent ignores prompt_2 through prompt_5`() {
        val result = SpawnAgentTool.composePromptPairs(
            params(
                "description" to "Impl",
                "prompt" to "Implement the feature.",
                "prompt_2" to "Should be ignored.",
                "prompt_5" to "Also ignored."
            ),
            isReadOnly = false
        )

        val pairs = resolved(result)
        assertEquals(1, pairs.size)
        assertEquals("Implement the feature.", pairs[0].first)
    }

    @Test
    fun `per-branch descriptions still align with their prompt`() {
        val result = SpawnAgentTool.composePromptPairs(
            params(
                "description" to "Primary label",
                "prompt" to "P1",
                "prompt_2" to "P2",
                "description_2" to "Second label"
            ),
            isReadOnly = true
        )

        val pairs = resolved(result)
        assertEquals("Primary label", pairs[0].second)
        assertEquals("Second label", pairs[1].second)
    }

    @Test
    fun `missing description_N falls back to the primary description`() {
        val result = SpawnAgentTool.composePromptPairs(
            params(
                "description" to "Primary label",
                "prompt" to "P1",
                "prompt_2" to "P2"
            ),
            isReadOnly = true
        )

        assertEquals("Primary label", resolved(result)[1].second)
    }

    @Test
    fun `placeholder stub in a parallel branch is rejected with a shared_prompt hint`() {
        val result = SpawnAgentTool.composePromptPairs(
            params(
                "description" to "Multi",
                "prompt" to "Research question 1.",
                "prompt_2" to "[Same prompt as above — the full system prompt content]"
            ),
            isReadOnly = true
        )

        assertTrue(result is SpawnAgentTool.PromptComposition.Invalid, "Expected Invalid, got $result")
        val msg = (result as SpawnAgentTool.PromptComposition.Invalid).message
        assertTrue(msg.contains("shared_prompt"), "Error should point the model at shared_prompt: $msg")
        assertTrue(msg.contains("prompt_2"), "Error should name the offending field: $msg")
    }

    @Test
    fun `placeholder phrasing in a single dispatch is NOT flagged`() {
        // Only one prompt → no sibling to reference → the stub guard must not trip.
        val result = SpawnAgentTool.composePromptPairs(
            params(
                "description" to "One",
                "prompt" to "See above for the full task."
            ),
            isReadOnly = true
        )

        assertTrue(result is SpawnAgentTool.PromptComposition.Resolved, "Single dispatch must not be guarded: $result")
    }

    @Test
    fun `long genuine prompt that incidentally says 'as above' is not flagged`() {
        val longPrompt = "Investigate the caching layer described earlier in the file. " +
            "Trace how cache keys are derived, where TTLs are set, and whether the eviction policy " +
            "matches the documented behaviour. Note that the configuration block referenced as above " +
            "in the design doc may be stale; verify against the actual code before drawing conclusions."
        assertTrue(longPrompt.length > 200, "guard length cap is 200; this fixture must exceed it")

        val result = SpawnAgentTool.composePromptPairs(
            params(
                "description" to "Multi",
                "prompt" to "Investigate the auth layer.",
                "prompt_2" to longPrompt
            ),
            isReadOnly = true
        )

        assertTrue(result is SpawnAgentTool.PromptComposition.Resolved, "Long genuine prompt must not be flagged: $result")
    }

    @Test
    fun `no valid prompts yields Invalid`() {
        val result = SpawnAgentTool.composePromptPairs(
            params(
                "description" to "Empty",
                "prompt" to "   "
            ),
            isReadOnly = true
        )

        assertTrue(result is SpawnAgentTool.PromptComposition.Invalid, "Expected Invalid, got $result")
    }
}
