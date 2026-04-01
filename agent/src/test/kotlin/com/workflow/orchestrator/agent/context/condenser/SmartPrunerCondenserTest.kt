package com.workflow.orchestrator.agent.context.condenser

import com.workflow.orchestrator.agent.context.events.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test

class SmartPrunerCondenserTest {

    private val condenser = SmartPrunerCondenser()

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private fun contextOf(view: View) = CondenserContext(
        view = view,
        tokenUtilization = 0.5,
        effectiveBudget = 100_000,
        currentTokens = 50_000
    )

    private fun fileRead(id: Int, toolCallId: String, path: String) = FileReadAction(
        toolCallId = toolCallId,
        responseGroupId = "rg-$id",
        path = path,
        id = id
    )

    private fun fileEdit(id: Int, toolCallId: String, path: String) = FileEditAction(
        toolCallId = toolCallId,
        responseGroupId = "rg-$id",
        path = path,
        oldStr = "old",
        newStr = "new",
        id = id
    )

    private fun toolResult(
        id: Int,
        toolCallId: String,
        content: String = "result content",
        isError: Boolean = false,
        toolName: String = "read_file"
    ) = ToolResultObservation(
        toolCallId = toolCallId,
        content = content,
        isError = isError,
        toolName = toolName,
        id = id
    )

    private fun genericAction(
        id: Int,
        toolCallId: String,
        toolName: String = "some_tool",
        arguments: String = "{}"
    ) = GenericToolAction(
        toolCallId = toolCallId,
        responseGroupId = "rg-$id",
        toolName = toolName,
        arguments = arguments,
        id = id
    )

    // -----------------------------------------------------------------------
    // Strategy 1: Deduplicate File Reads
    // -----------------------------------------------------------------------

    @Nested
    inner class DeduplicateFileReads {

        @Test
        fun `two reads of same file - older result replaced with CondensationObservation`() {
            val events = listOf(
                fileRead(1, "tc-1", "/src/Main.kt"),           // first read
                toolResult(2, "tc-1", "file content v1"),      // first result
                fileRead(3, "tc-3", "/src/Main.kt"),           // second read
                toolResult(4, "tc-3", "file content v2")       // second result
            )
            val result = condenser.condense(contextOf(View(events = events)))

            assertTrue(result is CondenserView)
            val resultEvents = (result as CondenserView).view.events

            assertEquals(4, resultEvents.size)
            // Older result (index 1) should be replaced
            val replaced = resultEvents[1]
            assertTrue(replaced is CondensationObservation)
            assertContains((replaced as CondensationObservation).content, "/src/Main.kt")
            assertContains(replaced.content, "re-read later")
            // Newer result (index 3) should be untouched
            assertTrue(resultEvents[3] is ToolResultObservation)
            assertEquals("file content v2", (resultEvents[3] as ToolResultObservation).content)
        }

        @Test
        fun `edit between reads resets tracking - no dedup`() {
            val events = listOf(
                fileRead(1, "tc-1", "/src/Main.kt"),
                toolResult(2, "tc-1", "file content v1"),
                fileEdit(3, "tc-3", "/src/Main.kt"),           // edit resets tracking
                toolResult(4, "tc-3", "edit applied"),
                fileRead(5, "tc-5", "/src/Main.kt"),
                toolResult(6, "tc-5", "file content v2")
            )
            val result = condenser.condense(contextOf(View(events = events)))

            val resultEvents = (result as CondenserView).view.events
            // No dedup — the edit reset tracking, so both read results should remain
            assertEquals(6, resultEvents.size)
            assertTrue(resultEvents[1] is ToolResultObservation)
            assertEquals("file content v1", (resultEvents[1] as ToolResultObservation).content)
            assertTrue(resultEvents[5] is ToolResultObservation)
            assertEquals("file content v2", (resultEvents[5] as ToolResultObservation).content)
        }

        @Test
        fun `three reads of same file - first two results replaced`() {
            val events = listOf(
                fileRead(1, "tc-1", "/src/Main.kt"),
                toolResult(2, "tc-1", "content v1"),
                fileRead(3, "tc-3", "/src/Main.kt"),
                toolResult(4, "tc-3", "content v2"),
                fileRead(5, "tc-5", "/src/Main.kt"),
                toolResult(6, "tc-5", "content v3")
            )
            val result = condenser.condense(contextOf(View(events = events)))

            val resultEvents = (result as CondenserView).view.events
            assertEquals(6, resultEvents.size)
            // First result replaced
            assertTrue(resultEvents[1] is CondensationObservation)
            // Second result replaced
            assertTrue(resultEvents[3] is CondensationObservation)
            // Third result kept
            assertTrue(resultEvents[5] is ToolResultObservation)
            assertEquals("content v3", (resultEvents[5] as ToolResultObservation).content)
        }

        @Test
        fun `different files - no dedup`() {
            val events = listOf(
                fileRead(1, "tc-1", "/src/Main.kt"),
                toolResult(2, "tc-1", "main content"),
                fileRead(3, "tc-3", "/src/Other.kt"),
                toolResult(4, "tc-3", "other content")
            )
            val result = condenser.condense(contextOf(View(events = events)))

            val resultEvents = (result as CondenserView).view.events
            // Both results should remain untouched
            assertTrue(resultEvents[1] is ToolResultObservation)
            assertEquals("main content", (resultEvents[1] as ToolResultObservation).content)
            assertTrue(resultEvents[3] is ToolResultObservation)
            assertEquals("other content", (resultEvents[3] as ToolResultObservation).content)
        }
    }

    // -----------------------------------------------------------------------
    // Strategy 2: Purge Failed Tool Inputs
    // -----------------------------------------------------------------------

    @Nested
    inner class PurgeFailedToolInputs {

        @Test
        fun `error result older than 4 events - tool action args truncated`() {
            val longArgs = "x".repeat(600)
            val events = listOf(
                genericAction(1, "tc-1", arguments = longArgs),     // failed tool action
                toolResult(2, "tc-1", "error!", isError = true),    // error result
                MessageAction(content = "msg1", id = 3),            // 1 turn after error
                MessageAction(content = "msg2", id = 4),            // 2
                MessageAction(content = "msg3", id = 5),            // 3
                MessageAction(content = "msg4", id = 6),            // 4 — now older than turnsAfterError
                MessageAction(content = "msg5", id = 7)             // 5
            )
            val result = condenser.condense(contextOf(View(events = events)))

            val resultEvents = (result as CondenserView).view.events
            assertEquals(7, resultEvents.size)
            // The tool action at index 0 should be replaced with truncated GenericToolAction
            val replaced = resultEvents[0]
            assertTrue(replaced is GenericToolAction)
            val truncated = replaced as GenericToolAction
            assertTrue(truncated.arguments.length < longArgs.length)
            assertTrue(truncated.arguments.endsWith("... [args truncated — tool call failed]"))
            assertEquals(200 + "... [args truncated — tool call failed]".length, truncated.arguments.length)
        }

        @Test
        fun `error result newer than 4 events - no change`() {
            val longArgs = "x".repeat(600)
            val events = listOf(
                genericAction(1, "tc-1", arguments = longArgs),
                toolResult(2, "tc-1", "error!", isError = true),
                MessageAction(content = "msg1", id = 3),  // only 2 events after error
                MessageAction(content = "msg2", id = 4)
            )
            val result = condenser.condense(contextOf(View(events = events)))

            val resultEvents = (result as CondenserView).view.events
            // No change — error is too recent
            val action = resultEvents[0] as GenericToolAction
            assertEquals(longArgs, action.arguments)
        }

        @Test
        fun `error result with short args - no change`() {
            val shortArgs = "x".repeat(400)  // under 500 char threshold
            val events = listOf(
                genericAction(1, "tc-1", arguments = shortArgs),
                toolResult(2, "tc-1", "error!", isError = true),
                MessageAction(content = "msg1", id = 3),
                MessageAction(content = "msg2", id = 4),
                MessageAction(content = "msg3", id = 5),
                MessageAction(content = "msg4", id = 6),
                MessageAction(content = "msg5", id = 7)
            )
            val result = condenser.condense(contextOf(View(events = events)))

            val resultEvents = (result as CondenserView).view.events
            // No truncation — args are short
            val action = resultEvents[0] as GenericToolAction
            assertEquals(shortArgs, action.arguments)
        }
    }

    // -----------------------------------------------------------------------
    // Strategy 3: Supersede Confirmed Writes
    // -----------------------------------------------------------------------

    @Nested
    inner class SupersedeConfirmedWrites {

        @Test
        fun `edit + success result + later read of same file - edit result replaced`() {
            val events = listOf(
                fileEdit(1, "tc-1", "/src/Main.kt"),
                toolResult(2, "tc-1", "edit applied successfully", toolName = "edit_file"),
                fileRead(3, "tc-3", "/src/Main.kt"),
                toolResult(4, "tc-3", "updated file content")
            )
            val result = condenser.condense(contextOf(View(events = events)))

            val resultEvents = (result as CondenserView).view.events
            assertEquals(4, resultEvents.size)
            // Edit result (index 1) should be replaced
            val replaced = resultEvents[1]
            assertTrue(replaced is CondensationObservation)
            assertContains((replaced as CondensationObservation).content, "/src/Main.kt")
            assertContains(replaced.content, "confirmed by subsequent read")
        }

        @Test
        fun `edit + error result - no superseding`() {
            val events = listOf(
                fileEdit(1, "tc-1", "/src/Main.kt"),
                toolResult(2, "tc-1", "edit failed", isError = true, toolName = "edit_file"),
                fileRead(3, "tc-3", "/src/Main.kt"),
                toolResult(4, "tc-3", "file content")
            )
            val result = condenser.condense(contextOf(View(events = events)))

            val resultEvents = (result as CondenserView).view.events
            // Error result should NOT be superseded
            assertTrue(resultEvents[1] is ToolResultObservation)
            assertEquals("edit failed", (resultEvents[1] as ToolResultObservation).content)
        }

        @Test
        fun `edit + success but no later read - no superseding`() {
            val events = listOf(
                fileEdit(1, "tc-1", "/src/Main.kt"),
                toolResult(2, "tc-1", "edit applied successfully", toolName = "edit_file"),
                MessageAction(content = "done", id = 3)
            )
            val result = condenser.condense(contextOf(View(events = events)))

            val resultEvents = (result as CondenserView).view.events
            // No read after edit, so result stays
            assertTrue(resultEvents[1] is ToolResultObservation)
            assertEquals("edit applied successfully", (resultEvents[1] as ToolResultObservation).content)
        }
    }

    // -----------------------------------------------------------------------
    // Integration: all three strategies
    // -----------------------------------------------------------------------

    @Test
    fun `all three strategies run together`() {
        val longArgs = "y".repeat(600)
        val events = listOf(
            // Strategy 1: dedup file reads - first read to be deduped
            fileRead(1, "tc-1", "/src/Config.kt"),
            toolResult(2, "tc-1", "config v1"),

            // Strategy 2: failed tool with long args
            genericAction(3, "tc-3", arguments = longArgs),
            toolResult(4, "tc-3", "error!", isError = true),

            // Strategy 3: edit + success + later read
            fileEdit(5, "tc-5", "/src/Main.kt"),
            toolResult(6, "tc-5", "edit ok", toolName = "edit_file"),

            // Strategy 1: second read of same file (dedup triggers)
            fileRead(7, "tc-7", "/src/Config.kt"),
            toolResult(8, "tc-7", "config v2"),

            // Strategy 3: read of edited file (supersede triggers)
            fileRead(9, "tc-9", "/src/Main.kt"),
            toolResult(10, "tc-9", "main content after edit"),

            // Padding events to make the error old enough
            MessageAction(content = "msg1", id = 11),
            MessageAction(content = "msg2", id = 12),
            MessageAction(content = "msg3", id = 13),
            MessageAction(content = "msg4", id = 14)
        )
        val result = condenser.condense(contextOf(View(events = events)))

        val resultEvents = (result as CondenserView).view.events
        assertEquals(14, resultEvents.size)

        // Strategy 1: older read result replaced
        assertTrue(resultEvents[1] is CondensationObservation, "Older read of Config.kt should be deduped")

        // Strategy 2: failed tool args truncated
        val truncatedAction = resultEvents[2] as GenericToolAction
        assertTrue(truncatedAction.arguments.contains("[args truncated"), "Failed tool args should be truncated")

        // Strategy 3: edit result superseded
        assertTrue(resultEvents[5] is CondensationObservation, "Edit result for Main.kt should be superseded")
    }

    // -----------------------------------------------------------------------
    // Result type and immutability
    // -----------------------------------------------------------------------

    @Test
    fun `always returns CondenserView - never Condensation`() {
        val events = listOf(MessageAction(content = "hello", id = 1))
        val result = condenser.condense(contextOf(View(events = events)))
        assertTrue(result is CondenserView)
        assertFalse(result is Condensation)
    }

    @Test
    fun `original view is not mutated`() {
        val originalEvents = listOf(
            fileRead(1, "tc-1", "/src/Main.kt"),
            toolResult(2, "tc-1", "file content v1"),
            fileRead(3, "tc-3", "/src/Main.kt"),
            toolResult(4, "tc-3", "file content v2")
        )
        val originalView = View(events = originalEvents)

        condenser.condense(contextOf(originalView))

        // Original view should still have the original events
        assertEquals(4, originalView.events.size)
        assertTrue(originalView.events[1] is ToolResultObservation)
        assertEquals("file content v1", (originalView.events[1] as ToolResultObservation).content)
    }

    // -----------------------------------------------------------------------
    // Edge cases
    // -----------------------------------------------------------------------

    @Test
    fun `empty events list - returns empty view`() {
        val result = condenser.condense(contextOf(View(events = emptyList())))
        val resultEvents = (result as CondenserView).view.events
        assertTrue(resultEvents.isEmpty())
    }

    @Test
    fun `preserves unhandledCondensationRequest and forgottenEventIds`() {
        val view = View(
            events = listOf(MessageAction(content = "hello", id = 1)),
            unhandledCondensationRequest = true,
            forgottenEventIds = setOf(99, 100)
        )
        val result = condenser.condense(contextOf(view))
        val resultView = (result as CondenserView).view
        assertTrue(resultView.unhandledCondensationRequest)
        assertEquals(setOf(99, 100), resultView.forgottenEventIds)
    }

    // -----------------------------------------------------------------------
    // Helper assertion
    // -----------------------------------------------------------------------

    private fun assertContains(text: String, substring: String) {
        assertTrue(text.contains(substring), "Expected '$text' to contain '$substring'")
    }
}
