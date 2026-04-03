package com.workflow.orchestrator.agent.context.condenser

import com.workflow.orchestrator.agent.context.events.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ObservationMaskingCondenserTest {

    // Default condenser: threshold=0.60, innerWindow=40K, outerWindow=60K
    private val condenser = ObservationMaskingCondenser(
        threshold = 0.60,
        innerWindowTokens = 1_000,  // small windows for testing
        outerWindowTokens = 2_000
    )

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun contextOf(
        view: View,
        tokenUtilization: Double = 0.70,
        effectiveBudget: Int = 100_000,
        currentTokens: Int = 70_000
    ) = CondenserContext(
        view = view,
        tokenUtilization = tokenUtilization,
        effectiveBudget = effectiveBudget,
        currentTokens = currentTokens
    )

    private fun toolResult(
        id: Int,
        toolCallId: String = "tc-$id",
        content: String = "result content for tool call $id",
        isError: Boolean = false,
        toolName: String = "read_file"
    ) = ToolResultObservation(
        toolCallId = toolCallId,
        content = content,
        isError = isError,
        toolName = toolName,
        id = id
    )

    /** Create a tool result with content of approximately [tokenCount] tokens (chars * 4). */
    private fun toolResultWithTokens(
        id: Int,
        tokenCount: Int,
        toolName: String = "read_file"
    ) = toolResult(
        id = id,
        content = "x".repeat(tokenCount * 4),
        toolName = toolName
    )

    /** Create a large tool result with many lines for testing COMPRESSED tier. */
    private fun toolResultWithLines(
        id: Int,
        lineCount: Int,
        toolName: String = "read_file"
    ) = toolResult(
        id = id,
        content = (1..lineCount).joinToString("\n") { "line $it: content here" },
        toolName = toolName
    )

    private fun errorObs(id: Int, content: String = "something went wrong") = ErrorObservation(
        content = content,
        id = id
    )

    private fun successObs(id: Int, content: String = "operation succeeded") = SuccessObservation(
        content = content,
        id = id
    )

    private fun condensationObs(id: Int, content: String = "summary of previous events") =
        CondensationObservation(content = content, id = id)

    private fun message(id: Int, content: String = "message $id") = MessageAction(
        content = content,
        id = id
    )

    private fun genericAction(id: Int, toolName: String = "some_tool") = GenericToolAction(
        toolCallId = "tc-$id",
        responseGroupId = "rg-$id",
        toolName = toolName,
        arguments = "{}",
        id = id
    )

    private fun assertContains(text: String, substring: String) {
        assertTrue(text.contains(substring), "Expected text to contain '$substring' but was:\n$text")
    }

    // -----------------------------------------------------------------------
    // Token-utilization gating
    // -----------------------------------------------------------------------

    @Nested
    inner class TokenUtilizationGating {

        @Test
        fun `below threshold - no masking occurs`() {
            val events = listOf(
                toolResultWithTokens(1, 500, "read_file"),
                toolResultWithTokens(2, 500, "search_code"),
                message(3),
                message(4)
            )

            val result = condenser.condense(contextOf(View(events = events), tokenUtilization = 0.55))
            val resultEvents = (result as CondenserView).view.events

            // All observations kept as-is
            assertTrue(resultEvents[0] is ToolResultObservation)
            assertTrue(resultEvents[1] is ToolResultObservation)
            assertEquals(
                (events[0] as ToolResultObservation).content,
                (resultEvents[0] as ToolResultObservation).content
            )
        }

        @Test
        fun `at exact threshold - masking activates`() {
            // Event[0] is the observation we want masked.
            // Token distance of event[0] = sum of tokens of all events AFTER it.
            // Need events after it totaling > outerWindowTokens (2000) to reach METADATA tier.
            val events = listOf(
                toolResult(1, toolName = "read_file", content = "target observation"),
                // Padding after: 2500 tokens * 4 = 10000 chars → distance > outerWindow (2000)
                message(2, content = "x".repeat(10_000)),
                message(3, content = "recent")
            )

            val result = condenser.condense(contextOf(View(events = events), tokenUtilization = 0.60))
            val resultEvents = (result as CondenserView).view.events

            // Observation should be masked (METADATA tier — beyond outerWindow)
            assertTrue(resultEvents[0] is CondensationObservation,
                "At threshold, observation far from tail should be masked")
        }

        @Test
        fun `above threshold - masking activates`() {
            val events = listOf(
                toolResult(1, toolName = "read_file", content = "target observation"),
                message(2, content = "x".repeat(10_000)),
                message(3, content = "recent")
            )

            val result = condenser.condense(contextOf(View(events = events), tokenUtilization = 0.85))
            val resultEvents = (result as CondenserView).view.events

            assertTrue(resultEvents[0] is CondensationObservation,
                "Above threshold, observation far from tail should be masked")
        }

        @Test
        fun `just below threshold - no masking`() {
            val events = listOf(
                toolResultWithTokens(1, 1500, "read_file"),
                message(2, content = "short")
            )

            val result = condenser.condense(contextOf(View(events = events), tokenUtilization = 0.59))
            val resultEvents = (result as CondenserView).view.events

            assertTrue(resultEvents[0] is ToolResultObservation)
        }
    }

    // -----------------------------------------------------------------------
    // Three-tier masking
    // -----------------------------------------------------------------------

    @Nested
    inner class ThreeTierMasking {

        @Test
        fun `FULL tier - observations within innerWindow kept unchanged`() {
            // innerWindowTokens=1000, so ~4000 chars fit in FULL tier
            val events = listOf(
                toolResult(1, content = "recent result"),  // close to tail → FULL
                message(2, content = "msg")
            )

            val result = condenser.condense(contextOf(View(events = events), tokenUtilization = 0.70))
            val resultEvents = (result as CondenserView).view.events

            assertTrue(resultEvents[0] is ToolResultObservation)
            assertEquals("recent result", (resultEvents[0] as ToolResultObservation).content)
        }

        @Test
        fun `COMPRESSED tier - observations between inner and outer window keep first 20 and last 5 lines`() {
            // To land in COMPRESSED tier, token distance must be > innerWindow but <= outerWindow.
            // innerWindowTokens=1000 (~4K chars), outerWindowTokens=2000 (~8K chars)
            // Put a 50-line observation, then padding of ~5K chars after it (distance ~1250 tokens)
            val bigResult = toolResultWithLines(1, 50, "read_file")
            val events = listOf(
                bigResult,
                // ~1250 tokens of padding → distance > inner (1000) but < outer (2000)
                message(2, content = "x".repeat(5_000)),
                message(3, content = "recent")
            )

            val result = condenser.condense(contextOf(View(events = events), tokenUtilization = 0.70))
            val resultEvents = (result as CondenserView).view.events

            // The big result should be in COMPRESSED tier
            val compressed = resultEvents[0]
            assertTrue(compressed is ToolResultObservation,
                "COMPRESSED tier should return ToolResultObservation with truncated content")
            val compressedResult = compressed as ToolResultObservation
            val lines = compressedResult.content.lines()
            assertTrue(lines.size < 50, "Compressed should have fewer lines than original 50, got ${lines.size}")
            assertTrue(compressedResult.content.contains("[..."), "Should contain compression marker")
            assertTrue(compressedResult.content.contains("lines compressed"), "Should mention compressed lines")
            assertTrue(compressedResult.content.contains("line 1:"), "First line should be preserved")
            assertTrue(compressedResult.content.contains("line 50:"), "Last line should be preserved")
        }

        @Test
        fun `METADATA tier - observations beyond outer window become placeholders`() {
            // innerWindow=1000 (~4K chars), outerWindow=2000 (~8K chars)
            // Distance of event[0] = sum of tokens of events AFTER it.
            // Need > 2000 tokens after → > 8000 chars of padding
            val events = listOf(
                toolResult(1, toolName = "read_file", content = "target content here"),
                // ~2500 tokens of padding → distance > outerWindow (2000) → METADATA tier
                message(2, content = "x".repeat(10_000)),
                message(3, content = "recent")
            )

            val result = condenser.condense(contextOf(View(events = events), tokenUtilization = 0.70))
            val resultEvents = (result as CondenserView).view.events

            // First result should be METADATA (its distance from tail > outerWindow)
            val masked = resultEvents[0]
            assertTrue(masked is CondensationObservation, "Should be masked to METADATA")
            assertContains((masked as CondensationObservation).content, "[Tool result masked to save context]")
            assertContains(masked.content, "Tool: read_file")
        }

        @Test
        fun `small observations in COMPRESSED tier are kept unchanged`() {
            // An observation with < 30 lines in COMPRESSED tier stays as-is
            val smallCondenser = ObservationMaskingCondenser(
                threshold = 0.60,
                innerWindowTokens = 50,
                outerWindowTokens = 5_000
            )

            val smallResult = toolResult(1, content = "line 1\nline 2\nline 3")
            val events = listOf(
                smallResult,
                message(2, content = "x".repeat(400))  // pushes result out of inner window
            )

            val result = smallCondenser.condense(contextOf(View(events = events), tokenUtilization = 0.70))
            val resultEvents = (result as CondenserView).view.events

            // Small content should pass through even in COMPRESSED tier
            if (resultEvents[0] is ToolResultObservation) {
                assertEquals(
                    "line 1\nline 2\nline 3",
                    (resultEvents[0] as ToolResultObservation).content
                )
            }
        }
    }

    // -----------------------------------------------------------------------
    // Rich placeholder format (METADATA tier)
    // -----------------------------------------------------------------------

    @Nested
    inner class RichPlaceholderFormat {

        /** Helper to create a context where the first observation falls into METADATA tier. */
        private fun metadataContext(events: List<Event>) = contextOf(
            View(events = events),
            tokenUtilization = 0.70
        )

        /** Create events where event[0] is far enough from tail to be METADATA. */
        private fun eventsWithDistantObservation(obs: Observation): List<Event> = listOf(
            obs,
            // Padding to push obs beyond outerWindow (2000 tokens = 8000 chars)
            message(100, content = "x".repeat(10_000)),
            message(101, content = "recent")
        )

        @Test
        fun `ToolResultObservation placeholder includes tool name and recovery hint`() {
            val obs = toolResult(1, toolName = "read_file", content = "file content here")
            val result = condenser.condense(metadataContext(eventsWithDistantObservation(obs)))
            val masked = (result as CondenserView).view.events[0] as CondensationObservation

            assertContains(masked.content, "[Tool result masked to save context]")
            assertContains(masked.content, "Tool: read_file")
            assertContains(masked.content, "Preview: file content here")
            assertContains(masked.content, "Recovery: content was compressed")
        }

        @Test
        fun `recovery hint for search_code`() {
            val obs = toolResult(1, toolName = "search_code", content = "search results")
            val result = condenser.condense(metadataContext(eventsWithDistantObservation(obs)))
            val masked = (result as CondenserView).view.events[0] as CondensationObservation
            assertContains(masked.content, "Recovery: re-run search_code with the same query if needed")
        }

        @Test
        fun `recovery hint for glob_files`() {
            val obs = toolResult(1, toolName = "glob_files", content = "glob results")
            val result = condenser.condense(metadataContext(eventsWithDistantObservation(obs)))
            val masked = (result as CondenserView).view.events[0] as CondensationObservation
            assertContains(masked.content, "Recovery: re-run glob_files with the same pattern if needed")
        }

        @Test
        fun `recovery hint for run_command`() {
            val obs = toolResult(1, toolName = "run_command", content = "command output")
            val result = condenser.condense(metadataContext(eventsWithDistantObservation(obs)))
            val masked = (result as CondenserView).view.events[0] as CondensationObservation
            assertContains(masked.content, "Recovery: re-run the command if output is needed")
        }

        @Test
        fun `recovery hint for diagnostics`() {
            val obs = toolResult(1, toolName = "diagnostics", content = "diagnostic results")
            val result = condenser.condense(metadataContext(eventsWithDistantObservation(obs)))
            val masked = (result as CondenserView).view.events[0] as CondensationObservation
            assertContains(masked.content, "Recovery: re-run diagnostics for current results")
        }

        @Test
        fun `recovery hint for unknown tool uses generic message`() {
            val obs = toolResult(1, toolName = "custom_tool", content = "custom output")
            val result = condenser.condense(metadataContext(eventsWithDistantObservation(obs)))
            val masked = (result as CondenserView).view.events[0] as CondensationObservation
            assertContains(masked.content, "Recovery: result was compressed to save context")
        }

        @Test
        fun `preview truncates at 100 characters`() {
            val longContent = "a".repeat(200)
            val obs = toolResult(1, toolName = "read_file", content = longContent)
            val result = condenser.condense(metadataContext(eventsWithDistantObservation(obs)))
            val masked = (result as CondenserView).view.events[0] as CondensationObservation
            assertContains(masked.content, "Preview: ${"a".repeat(100)}...")
            assertFalse(masked.content.contains("a".repeat(101)))
        }

        @Test
        fun `ErrorObservation masked with generic format`() {
            val obs = errorObs(1, content = "some error happened")
            val result = condenser.condense(metadataContext(eventsWithDistantObservation(obs)))
            val masked = (result as CondenserView).view.events[0] as CondensationObservation
            assertContains(masked.content, "[Observation masked to save context]")
            assertContains(masked.content, "Preview: some error happened...")
            assertFalse(masked.content.contains("Tool:"))
            assertFalse(masked.content.contains("Recovery:"))
        }

        @Test
        fun `SuccessObservation masked with generic format`() {
            val obs = successObs(1, content = "task completed successfully")
            val result = condenser.condense(metadataContext(eventsWithDistantObservation(obs)))
            val masked = (result as CondenserView).view.events[0] as CondensationObservation
            assertContains(masked.content, "[Observation masked to save context]")
            assertContains(masked.content, "Preview: task completed successfully...")
        }
    }

    // -----------------------------------------------------------------------
    // CondensationObservation preservation
    // -----------------------------------------------------------------------

    @Nested
    inner class CondensationObservationPreservation {

        @Test
        fun `CondensationObservation instances are NOT masked regardless of tier`() {
            val events = listOf(
                condensationObs(1, "critical summary from phase 1"),
                toolResult(2, toolName = "read_file", content = "x".repeat(1000)),
                condensationObs(3, "another summary"),
                // Padding to push events beyond outer window
                message(4, content = "x".repeat(10_000)),
                message(5, content = "recent")
            )

            val result = condenser.condense(contextOf(View(events = events), tokenUtilization = 0.70))
            val resultEvents = (result as CondenserView).view.events

            assertEquals(5, resultEvents.size)

            // CondensationObservation at index 0: kept as-is
            val kept0 = resultEvents[0]
            assertTrue(kept0 is CondensationObservation)
            assertEquals("critical summary from phase 1", (kept0 as CondensationObservation).content)

            // ToolResultObservation at index 1: should be masked (METADATA or COMPRESSED)
            val masked1 = resultEvents[1]
            assertFalse(masked1 is ToolResultObservation && (masked1 as ToolResultObservation).content == "x".repeat(1000),
                "Tool result should be compressed or masked, not kept as FULL")

            // CondensationObservation at index 2: kept as-is
            val kept2 = resultEvents[2]
            assertTrue(kept2 is CondensationObservation)
            assertEquals("another summary", (kept2 as CondensationObservation).content)
        }
    }

    // -----------------------------------------------------------------------
    // Non-observation event preservation
    // -----------------------------------------------------------------------

    @Nested
    inner class NonObservationPreservation {

        @Test
        fun `actions are never masked regardless of position or tier`() {
            val events = listOf(
                message(1, content = "old message"),
                genericAction(2, toolName = "some_tool"),
                toolResult(3, toolName = "read_file", content = "x".repeat(1000)),
                // Padding
                message(4, content = "x".repeat(10_000)),
                message(5, content = "recent")
            )

            val result = condenser.condense(contextOf(View(events = events), tokenUtilization = 0.70))
            val resultEvents = (result as CondenserView).view.events

            // Actions preserved
            assertTrue(resultEvents[0] is MessageAction)
            assertTrue(resultEvents[1] is GenericToolAction)
            // Observation potentially compressed/masked
            // Actions near tail preserved
            assertTrue(resultEvents[3] is MessageAction)
            assertTrue(resultEvents[4] is MessageAction)
        }
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Nested
    inner class EdgeCases {

        @Test
        fun `empty view returns empty view`() {
            val result = condenser.condense(contextOf(View(events = emptyList()), tokenUtilization = 0.70))
            val resultEvents = (result as CondenserView).view.events
            assertTrue(resultEvents.isEmpty())
        }

        @Test
        fun `single event view is unchanged when above threshold`() {
            val events = listOf(toolResult(1, content = "single result"))
            val result = condenser.condense(contextOf(View(events = events), tokenUtilization = 0.70))
            val resultEvents = (result as CondenserView).view.events

            // Single event is at distance 0 from tail → FULL tier
            assertEquals(1, resultEvents.size)
            assertTrue(resultEvents[0] is ToolResultObservation)
        }

        @Test
        fun `original view is not mutated`() {
            val originalEvents = listOf(
                toolResult(1, toolName = "read_file", content = "original content"),
                message(2, content = "x".repeat(10_000)),
                message(3, content = "recent")
            )
            val originalView = View(events = originalEvents)

            condenser.condense(contextOf(originalView, tokenUtilization = 0.70))

            // Original view should still have the original ToolResultObservation
            assertEquals(3, originalView.events.size)
            assertTrue(originalView.events[0] is ToolResultObservation)
            assertEquals(
                "original content",
                (originalView.events[0] as ToolResultObservation).content
            )
        }

        @Test
        fun `preserves unhandledCondensationRequest and forgottenEventIds from original view`() {
            val view = View(
                events = listOf(message(1)),
                unhandledCondensationRequest = true,
                forgottenEventIds = setOf(42, 43, 99)
            )

            val result = condenser.condense(contextOf(view, tokenUtilization = 0.70))
            val resultView = (result as CondenserView).view

            assertTrue(resultView.unhandledCondensationRequest)
            assertEquals(setOf(42, 43, 99), resultView.forgottenEventIds)
        }

        @Test
        fun `always returns CondenserView never Condensation`() {
            val events = listOf(
                toolResult(1),
                message(2, content = "x".repeat(10_000)),
                message(3, content = "recent")
            )

            val result = condenser.condense(contextOf(View(events = events), tokenUtilization = 0.90))
            assertTrue(result is CondenserView)
            assertFalse(result is Condensation)
        }

        @Test
        fun `masked observation preserves original id and timestamp`() {
            val original = toolResult(42, toolName = "read_file", content = "some content")
            val events = listOf(
                original,
                message(2, content = "x".repeat(10_000)),
                message(3, content = "recent")
            )

            val result = condenser.condense(contextOf(View(events = events), tokenUtilization = 0.70))
            val masked = (result as CondenserView).view.events[0]

            if (masked is CondensationObservation) {
                assertEquals(42, masked.id)
                assertEquals(original.timestamp, masked.timestamp)
            }
        }

        @Test
        fun `zero utilization passes through unchanged`() {
            val events = listOf(
                toolResult(1, content = "result"),
                toolResult(2, content = "result2"),
                message(3)
            )

            val result = condenser.condense(contextOf(View(events = events), tokenUtilization = 0.0))
            val resultEvents = (result as CondenserView).view.events

            assertTrue(resultEvents[0] is ToolResultObservation)
            assertTrue(resultEvents[1] is ToolResultObservation)
        }
    }
}
