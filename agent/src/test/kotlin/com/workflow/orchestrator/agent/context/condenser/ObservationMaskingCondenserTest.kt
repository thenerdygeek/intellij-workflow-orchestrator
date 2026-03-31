package com.workflow.orchestrator.agent.context.condenser

import com.workflow.orchestrator.agent.context.events.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class ObservationMaskingCondenserTest {

    private val condenser = ObservationMaskingCondenser(attentionWindow = 5)

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun contextOf(view: View) = CondenserContext(
        view = view,
        tokenUtilization = 0.5,
        effectiveBudget = 100_000,
        currentTokens = 50_000
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
        assertTrue(text.contains(substring), "Expected '$text' to contain '$substring'")
    }

    // -----------------------------------------------------------------------
    // Core masking behavior
    // -----------------------------------------------------------------------

    @Nested
    inner class CoreMasking {

        @Test
        fun `observations outside attention window are replaced with CondensationObservation`() {
            // attentionWindow=5, so with 8 events, indices 0-2 are outside window
            val events = listOf(
                toolResult(1, toolName = "read_file"),       // index 0 — outside
                toolResult(2, toolName = "search_code"),     // index 1 — outside
                toolResult(3, toolName = "run_command"),      // index 2 — outside
                message(4),                                   // index 3 — inside (8 - 5 = 3)
                message(5),                                   // index 4 — inside
                toolResult(6, toolName = "read_file"),       // index 5 — inside
                message(7),                                   // index 6 — inside
                message(8)                                    // index 7 — inside
            )

            val result = condenser.condense(contextOf(View(events = events)))
            val resultEvents = (result as CondenserView).view.events

            assertEquals(8, resultEvents.size)
            // Outside window: masked
            assertTrue(resultEvents[0] is CondensationObservation)
            assertTrue(resultEvents[1] is CondensationObservation)
            assertTrue(resultEvents[2] is CondensationObservation)
            // Inside window: kept
            assertTrue(resultEvents[3] is MessageAction)
            assertTrue(resultEvents[5] is ToolResultObservation)
        }

        @Test
        fun `observations inside attention window are kept unchanged`() {
            // attentionWindow=5, 5 events total => threshold = 0, nothing outside
            val events = listOf(
                toolResult(1),
                message(2),
                toolResult(3),
                message(4),
                toolResult(5)
            )

            val result = condenser.condense(contextOf(View(events = events)))
            val resultEvents = (result as CondenserView).view.events

            assertEquals(5, resultEvents.size)
            assertTrue(resultEvents[0] is ToolResultObservation)
            assertTrue(resultEvents[2] is ToolResultObservation)
            assertTrue(resultEvents[4] is ToolResultObservation)
        }

        @Test
        fun `non-observation events (Actions) are never masked regardless of position`() {
            // 10 events, attentionWindow=5, threshold=5, so indices 0-4 are outside
            val events = listOf(
                message(1),                                   // index 0 — Action, outside, NOT masked
                genericAction(2),                             // index 1 — Action, outside, NOT masked
                toolResult(3, toolName = "read_file"),       // index 2 — Observation, outside, MASKED
                message(4),                                   // index 3 — Action, outside, NOT masked
                genericAction(5),                             // index 4 — Action, outside, NOT masked
                toolResult(6),                                // index 5 — inside
                message(7),
                message(8),
                message(9),
                message(10)
            )

            val result = condenser.condense(contextOf(View(events = events)))
            val resultEvents = (result as CondenserView).view.events

            assertEquals(10, resultEvents.size)
            // Actions outside window: NOT masked
            assertTrue(resultEvents[0] is MessageAction)
            assertTrue(resultEvents[1] is GenericToolAction)
            assertTrue(resultEvents[3] is MessageAction)
            assertTrue(resultEvents[4] is GenericToolAction)
            // Observation outside window: masked
            assertTrue(resultEvents[2] is CondensationObservation)
        }
    }

    // -----------------------------------------------------------------------
    // Rich placeholder format
    // -----------------------------------------------------------------------

    @Nested
    inner class RichPlaceholderFormat {

        @Test
        fun `ToolResultObservation placeholder includes tool name and recovery hint`() {
            val events = listOf(
                toolResult(1, toolName = "read_file", content = "file content here"),
                message(2),
                message(3),
                message(4),
                message(5),
                message(6),
                message(7)  // attentionWindow=5, threshold=2, index 0 is outside
            )

            val result = condenser.condense(contextOf(View(events = events)))
            val masked = (result as CondenserView).view.events[0] as CondensationObservation

            assertContains(masked.content, "[Tool result masked to save context]")
            assertContains(masked.content, "Tool: read_file")
            assertContains(masked.content, "Preview: file content here")
            assertContains(masked.content, "Recovery: re-run read_file to get current content")
        }

        @Test
        fun `recovery hint for search_code`() {
            val events = listOf(
                toolResult(1, toolName = "search_code", content = "search results"),
                message(2), message(3), message(4), message(5), message(6), message(7)
            )

            val result = condenser.condense(contextOf(View(events = events)))
            val masked = (result as CondenserView).view.events[0] as CondensationObservation
            assertContains(masked.content, "Recovery: re-run search_code with the same query")
        }

        @Test
        fun `recovery hint for glob_files`() {
            val events = listOf(
                toolResult(1, toolName = "glob_files", content = "glob results"),
                message(2), message(3), message(4), message(5), message(6), message(7)
            )

            val result = condenser.condense(contextOf(View(events = events)))
            val masked = (result as CondenserView).view.events[0] as CondensationObservation
            assertContains(masked.content, "Recovery: re-run glob_files with the same pattern")
        }

        @Test
        fun `recovery hint for run_command`() {
            val events = listOf(
                toolResult(1, toolName = "run_command", content = "command output"),
                message(2), message(3), message(4), message(5), message(6), message(7)
            )

            val result = condenser.condense(contextOf(View(events = events)))
            val masked = (result as CondenserView).view.events[0] as CondensationObservation
            assertContains(masked.content, "Recovery: re-run the command if output is needed")
        }

        @Test
        fun `recovery hint for diagnostics`() {
            val events = listOf(
                toolResult(1, toolName = "diagnostics", content = "diagnostic results"),
                message(2), message(3), message(4), message(5), message(6), message(7)
            )

            val result = condenser.condense(contextOf(View(events = events)))
            val masked = (result as CondenserView).view.events[0] as CondensationObservation
            assertContains(masked.content, "Recovery: re-run diagnostics for current results")
        }

        @Test
        fun `recovery hint for unknown tool uses generic message`() {
            val events = listOf(
                toolResult(1, toolName = "custom_tool", content = "custom output"),
                message(2), message(3), message(4), message(5), message(6), message(7)
            )

            val result = condenser.condense(contextOf(View(events = events)))
            val masked = (result as CondenserView).view.events[0] as CondensationObservation
            assertContains(masked.content, "Recovery: re-run the tool if the result is needed")
        }

        @Test
        fun `preview truncates at 100 characters`() {
            val longContent = "a".repeat(200)
            val events = listOf(
                toolResult(1, toolName = "read_file", content = longContent),
                message(2), message(3), message(4), message(5), message(6), message(7)
            )

            val result = condenser.condense(contextOf(View(events = events)))
            val masked = (result as CondenserView).view.events[0] as CondensationObservation
            assertContains(masked.content, "Preview: ${"a".repeat(100)}...")
            assertFalse(masked.content.contains("a".repeat(101)))
        }

        @Test
        fun `ErrorObservation masked with generic format`() {
            val events = listOf(
                errorObs(1, content = "some error happened"),
                message(2), message(3), message(4), message(5), message(6), message(7)
            )

            val result = condenser.condense(contextOf(View(events = events)))
            val masked = (result as CondenserView).view.events[0] as CondensationObservation
            assertContains(masked.content, "[Observation masked to save context]")
            assertContains(masked.content, "Preview: some error happened...")
            // Should NOT contain tool-specific fields
            assertFalse(masked.content.contains("Tool:"))
            assertFalse(masked.content.contains("Recovery:"))
        }

        @Test
        fun `SuccessObservation masked with generic format`() {
            val events = listOf(
                successObs(1, content = "task completed successfully"),
                message(2), message(3), message(4), message(5), message(6), message(7)
            )

            val result = condenser.condense(contextOf(View(events = events)))
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
        fun `CondensationObservation instances are NOT masked even outside attention window`() {
            // attentionWindow=5, 8 events, threshold=3
            val events = listOf(
                condensationObs(1, "critical summary from phase 1"),  // index 0 — outside, but NOT masked
                toolResult(2, toolName = "read_file"),                // index 1 — outside, masked
                condensationObs(3, "another summary"),                // index 2 — outside, but NOT masked
                message(4),
                message(5),
                message(6),
                message(7),
                message(8)
            )

            val result = condenser.condense(contextOf(View(events = events)))
            val resultEvents = (result as CondenserView).view.events

            assertEquals(8, resultEvents.size)

            // CondensationObservation at index 0: kept as-is
            val kept0 = resultEvents[0]
            assertTrue(kept0 is CondensationObservation)
            assertEquals("critical summary from phase 1", (kept0 as CondensationObservation).content)

            // ToolResultObservation at index 1: masked
            val masked1 = resultEvents[1]
            assertTrue(masked1 is CondensationObservation)
            assertContains((masked1 as CondensationObservation).content, "[Tool result masked to save context]")

            // CondensationObservation at index 2: kept as-is
            val kept2 = resultEvents[2]
            assertTrue(kept2 is CondensationObservation)
            assertEquals("another summary", (kept2 as CondensationObservation).content)
        }
    }

    // -----------------------------------------------------------------------
    // Configurable attention window
    // -----------------------------------------------------------------------

    @Nested
    inner class ConfigurableAttentionWindow {

        @Test
        fun `window of 5 masks observations beyond last 5 events`() {
            val condenser5 = ObservationMaskingCondenser(attentionWindow = 5)
            val events = listOf(
                toolResult(1), // index 0 — outside (7-5=2, so 0 and 1 outside)
                toolResult(2), // index 1 — outside
                toolResult(3), // index 2 — inside
                message(4),
                message(5),
                message(6),
                message(7)
            )

            val result = condenser5.condense(contextOf(View(events = events)))
            val resultEvents = (result as CondenserView).view.events

            assertTrue(resultEvents[0] is CondensationObservation, "Index 0 should be masked")
            assertTrue(resultEvents[1] is CondensationObservation, "Index 1 should be masked")
            assertTrue(resultEvents[2] is ToolResultObservation, "Index 2 should NOT be masked")
        }

        @Test
        fun `window of 50 with only 10 events masks nothing`() {
            val condenser50 = ObservationMaskingCondenser(attentionWindow = 50)
            val events = (1..10).map { toolResult(it) }

            val result = condenser50.condense(contextOf(View(events = events)))
            val resultEvents = (result as CondenserView).view.events

            // threshold = 10 - 50 = -40, so nothing outside window
            resultEvents.forEach { event ->
                assertTrue(event is ToolResultObservation, "No events should be masked when window > events count")
            }
        }
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Nested
    inner class EdgeCases {

        @Test
        fun `empty view returns empty view`() {
            val result = condenser.condense(contextOf(View(events = emptyList())))
            val resultEvents = (result as CondenserView).view.events
            assertTrue(resultEvents.isEmpty())
        }

        @Test
        fun `view with fewer events than attentionWindow - nothing masked`() {
            val condenser30 = ObservationMaskingCondenser(attentionWindow = 30)
            val events = listOf(
                toolResult(1),
                errorObs(2),
                successObs(3),
                message(4)
            )

            val result = condenser30.condense(contextOf(View(events = events)))
            val resultEvents = (result as CondenserView).view.events

            assertEquals(4, resultEvents.size)
            assertTrue(resultEvents[0] is ToolResultObservation)
            assertTrue(resultEvents[1] is ErrorObservation)
            assertTrue(resultEvents[2] is SuccessObservation)
            assertTrue(resultEvents[3] is MessageAction)
        }

        @Test
        fun `original view is not mutated`() {
            val originalEvents = listOf(
                toolResult(1, toolName = "read_file", content = "original content"),
                message(2), message(3), message(4), message(5), message(6), message(7)
            )
            val originalView = View(events = originalEvents)

            condenser.condense(contextOf(originalView))

            // Original view should still have the original ToolResultObservation
            assertEquals(7, originalView.events.size)
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

            val result = condenser.condense(contextOf(view))
            val resultView = (result as CondenserView).view

            assertTrue(resultView.unhandledCondensationRequest)
            assertEquals(setOf(42, 43, 99), resultView.forgottenEventIds)
        }

        @Test
        fun `always returns CondenserView never Condensation`() {
            val events = listOf(
                toolResult(1),
                message(2), message(3), message(4), message(5), message(6), message(7)
            )

            val result = condenser.condense(contextOf(View(events = events)))
            assertTrue(result is CondenserView)
            assertFalse(result is Condensation)
        }

        @Test
        fun `masked observation preserves original id and timestamp`() {
            val original = toolResult(42, toolName = "read_file", content = "some content")
            val events = listOf(
                original,
                message(2), message(3), message(4), message(5), message(6), message(7)
            )

            val result = condenser.condense(contextOf(View(events = events)))
            val masked = (result as CondenserView).view.events[0] as CondensationObservation

            assertEquals(42, masked.id)
            assertEquals(original.timestamp, masked.timestamp)
        }
    }
}
