package com.workflow.orchestrator.agent.context

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.api.dto.FunctionCall
import com.workflow.orchestrator.agent.api.dto.ToolCall
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class SmartPrunerTest {

    // --- Helper builders ---

    private fun assistantWithToolCall(toolCallId: String, toolName: String, args: String): ChatMessage {
        return ChatMessage(
            role = "assistant",
            content = null,
            toolCalls = listOf(
                ToolCall(
                    id = toolCallId,
                    type = "function",
                    function = FunctionCall(name = toolName, arguments = args)
                )
            )
        )
    }

    private fun toolResult(toolCallId: String, content: String): ChatMessage {
        return ChatMessage(role = "tool", content = content, toolCallId = toolCallId)
    }

    private fun userMessage(content: String): ChatMessage {
        return ChatMessage(role = "user", content = content)
    }

    private fun assistantMessage(content: String): ChatMessage {
        return ChatMessage(role = "assistant", content = content)
    }

    // ========== Deduplication Tests ==========

    @Test
    fun `dedup keeps latest read_file for same path`() {
        val messages = mutableListOf(
            // First read of foo.kt
            assistantWithToolCall("call-1", "read_file", """{"path": "/src/foo.kt"}"""),
            toolResult("call-1", "<external_data>\n" + "fun old() { /* original implementation with lots of code */ }\n".repeat(10) + "</external_data>"),
            // Some conversation
            userMessage("Now read it again"),
            // Second read of foo.kt
            assistantWithToolCall("call-2", "read_file", """{"path": "/src/foo.kt"}"""),
            toolResult("call-2", "<external_data>\n" + "fun new() { /* updated implementation with lots of code */ }\n".repeat(10) + "</external_data>"),
        )

        val saved = SmartPruner.deduplicateFileReads(messages)

        // The older read (index 1) should be replaced with dedup marker
        assertTrue(messages[1].content!!.contains("Deduplicated"))
        assertTrue(messages[1].content!!.contains("/src/foo.kt"))
        // The newer read (index 4) should be untouched
        assertTrue(messages[4].content!!.contains("fun new()"))
        assertTrue(saved > 0)
    }

    @Test
    fun `dedup does NOT deduplicate across edit boundaries`() {
        val messages = mutableListOf(
            // First read of foo.kt
            assistantWithToolCall("call-1", "read_file", """{"path": "/src/foo.kt"}"""),
            toolResult("call-1", "<external_data>\nfun old() {}\n</external_data>"),
            // Edit foo.kt
            assistantWithToolCall("call-2", "edit_file", """{"path": "/src/foo.kt", "old_string": "old", "new_string": "new"}"""),
            toolResult("call-2", "Edit applied successfully"),
            // Second read of foo.kt (after edit — NOT a duplicate)
            assistantWithToolCall("call-3", "read_file", """{"path": "/src/foo.kt"}"""),
            toolResult("call-3", "<external_data>\nfun new() {}\n</external_data>"),
        )

        val saved = SmartPruner.deduplicateFileReads(messages)

        // Neither read should be deduplicated because an edit happened between them
        assertFalse(messages[1].content!!.contains("Deduplicated"))
        assertFalse(messages[5].content!!.contains("Deduplicated"))
        assertEquals(0, saved)
    }

    @Test
    fun `dedup handles multiple different files independently`() {
        val messages = mutableListOf(
            assistantWithToolCall("call-1", "read_file", """{"path": "/src/foo.kt"}"""),
            toolResult("call-1", "content of foo"),
            assistantWithToolCall("call-2", "read_file", """{"path": "/src/bar.kt"}"""),
            toolResult("call-2", "content of bar"),
            // Re-read foo only
            assistantWithToolCall("call-3", "read_file", """{"path": "/src/foo.kt"}"""),
            toolResult("call-3", "content of foo again"),
        )

        SmartPruner.deduplicateFileReads(messages)

        // foo's first read should be deduped
        assertTrue(messages[1].content!!.contains("Deduplicated"))
        // bar should be untouched (only read once)
        assertEquals("content of bar", messages[3].content)
    }

    // ========== Error Purge Tests ==========

    @Test
    fun `error purge truncates large args from failed tool calls after N turns`() {
        val largeArgs = """{"path": "/src/foo.kt", "old_string": "${"x".repeat(600)}", "new_string": "replacement"}"""
        val messages = mutableListOf(
            assistantWithToolCall("call-1", "edit_file", largeArgs),
            toolResult("call-1", "ERROR: old_string not found in file"),
            // 4 more messages (turnsAfterError = 4)
            userMessage("Try again"),
            assistantMessage("Let me fix that"),
            userMessage("Ok"),
            assistantMessage("Done"),
        )

        val saved = SmartPruner.purgeFailedToolInputs(messages, turnsAfterError = 4)

        // The tool call args should be truncated
        val toolCall = messages[0].toolCalls!![0]
        assertTrue(toolCall.function.arguments.contains("... [args truncated — tool call failed]"))
        assertTrue(toolCall.function.arguments.length < largeArgs.length)
        assertTrue(saved > 0)
    }

    @Test
    fun `error purge does NOT truncate small args (less than 500 chars)`() {
        val smallArgs = """{"path": "/src/foo.kt", "old_string": "hello"}"""
        val messages = mutableListOf(
            assistantWithToolCall("call-1", "edit_file", smallArgs),
            toolResult("call-1", "ERROR: old_string not found in file"),
            userMessage("Try again"),
            assistantMessage("Let me fix that"),
            userMessage("Ok"),
            assistantMessage("Done"),
        )

        val saved = SmartPruner.purgeFailedToolInputs(messages, turnsAfterError = 4)

        // Args should be unchanged (< 500 chars)
        val toolCall = messages[0].toolCalls!![0]
        assertEquals(smallArgs, toolCall.function.arguments)
        assertEquals(0, saved)
    }

    @Test
    fun `error purge does NOT truncate if not enough turns have passed`() {
        val largeArgs = """{"path": "/src/foo.kt", "old_string": "${"x".repeat(600)}"}"""
        val messages = mutableListOf(
            assistantWithToolCall("call-1", "edit_file", largeArgs),
            toolResult("call-1", "Failed to apply edit"),
            // Only 2 turns after error (need 4)
            userMessage("Try again"),
            assistantMessage("Ok"),
        )

        val saved = SmartPruner.purgeFailedToolInputs(messages, turnsAfterError = 4)

        // Args should be unchanged (not enough turns)
        val toolCall = messages[0].toolCalls!![0]
        assertEquals(largeArgs, toolCall.function.arguments)
        assertEquals(0, saved)
    }

    @Test
    fun `error purge detects various error patterns`() {
        val largeArgs = """{"command": "${"x".repeat(600)}"}"""
        val errorContents = listOf(
            "ERROR: something went wrong",
            "error: compilation failed",
            "Failed to execute command",
            "<external_data>\nCommand failed with exit code 1\n</external_data>",
            "Exception: NullPointerException at line 42",
        )

        for (errorContent in errorContents) {
            val messages = mutableListOf(
                assistantWithToolCall("call-1", "run_command", largeArgs),
                toolResult("call-1", errorContent),
                userMessage("1"), assistantMessage("2"),
                userMessage("3"), assistantMessage("4"),
            )

            val saved = SmartPruner.purgeFailedToolInputs(messages, turnsAfterError = 4)
            assertTrue(saved > 0, "Should detect error in: $errorContent")
        }
    }

    // ========== Write Supersede Tests ==========

    @Test
    fun `write supersede compacts write result after confirmed read`() {
        val messages = mutableListOf(
            // Write to foo.kt
            assistantWithToolCall("call-1", "edit_file", """{"path": "/src/foo.kt", "old_string": "old", "new_string": "new"}"""),
            toolResult("call-1", "<external_data>\nEdit applied successfully. Changes:\n- Line 5: old -> new\n- 1 replacement made\n</external_data>"),
            // Read foo.kt (confirms the write)
            assistantWithToolCall("call-2", "read_file", """{"path": "/src/foo.kt"}"""),
            toolResult("call-2", "<external_data>\nfun new() {}\n</external_data>"),
        )

        val saved = SmartPruner.supersedeConfirmedWrites(messages)

        // The write result should be compacted
        assertTrue(messages[1].content!!.contains("Write confirmed by subsequent read"))
        assertTrue(messages[1].content!!.contains("/src/foo.kt"))
        // The read result should be untouched
        assertTrue(messages[3].content!!.contains("fun new()"))
        assertTrue(saved > 0)
    }

    @Test
    fun `write supersede does NOT compact failed writes`() {
        val messages = mutableListOf(
            assistantWithToolCall("call-1", "edit_file", """{"path": "/src/foo.kt", "old_string": "old", "new_string": "new"}"""),
            toolResult("call-1", "ERROR: old_string not found"),
            // Read foo.kt
            assistantWithToolCall("call-2", "read_file", """{"path": "/src/foo.kt"}"""),
            toolResult("call-2", "fun old() {}"),
        )

        val saved = SmartPruner.supersedeConfirmedWrites(messages)

        // Write result should NOT be compacted (it failed)
        assertEquals("ERROR: old_string not found", messages[1].content)
        assertEquals(0, saved)
    }

    @Test
    fun `write supersede only compacts when same path is read`() {
        val messages = mutableListOf(
            assistantWithToolCall("call-1", "edit_file", """{"path": "/src/foo.kt", "old_string": "old", "new_string": "new"}"""),
            toolResult("call-1", "Edit applied successfully"),
            // Read different file
            assistantWithToolCall("call-2", "read_file", """{"path": "/src/bar.kt"}"""),
            toolResult("call-2", "bar content"),
        )

        val saved = SmartPruner.supersedeConfirmedWrites(messages)

        // Write result should NOT be compacted (different file was read)
        assertEquals("Edit applied successfully", messages[1].content)
        assertEquals(0, saved)
    }

    // ========== pruneAll Tests ==========

    @Test
    fun `pruneAll runs all strategies`() {
        val largeArgs = """{"path": "/src/err.kt", "old_string": "${"x".repeat(600)}"}"""
        val messages = mutableListOf(
            // Duplicate read
            assistantWithToolCall("call-1", "read_file", """{"path": "/src/foo.kt"}"""),
            toolResult("call-1", "old content of foo " + "x".repeat(200)),
            // Error with large args
            assistantWithToolCall("call-2", "edit_file", largeArgs),
            toolResult("call-2", "ERROR: old_string not found"),
            // Write + confirming read
            assistantWithToolCall("call-3", "edit_file", """{"path": "/src/bar.kt", "old_string": "a", "new_string": "b"}"""),
            toolResult("call-3", "Edit applied successfully. Detailed changes: " + "y".repeat(200)),
            // Padding to satisfy turnsAfterError
            userMessage("1"), assistantMessage("2"),
            userMessage("3"), assistantMessage("4"),
            // Re-read foo (dedup trigger)
            assistantWithToolCall("call-4", "read_file", """{"path": "/src/foo.kt"}"""),
            toolResult("call-4", "new content of foo"),
            // Read bar (supersede trigger)
            assistantWithToolCall("call-5", "read_file", """{"path": "/src/bar.kt"}"""),
            toolResult("call-5", "content of bar after edit"),
        )

        val saved = SmartPruner.pruneAll(messages, turnsAfterError = 4)

        assertTrue(saved > 0, "Should save tokens from at least one strategy")
        // Check dedup happened
        assertTrue(messages[1].content!!.contains("Deduplicated"))
        // Check error purge happened
        assertTrue(messages[2].toolCalls!![0].function.arguments.contains("[args truncated"))
        // Check write supersede happened
        assertTrue(messages[5].content!!.contains("Write confirmed"))
    }

    // ========== findToolCall Tests ==========

    @Test
    fun `findToolCall walks backward to find matching tool call`() {
        val messages = listOf(
            assistantWithToolCall("call-1", "read_file", """{"path": "/src/foo.kt"}"""),
            toolResult("call-1", "content"),
        )

        val result = SmartPruner.findToolCall(messages, 1, "call-1")
        assertNotNull(result)
        assertEquals("read_file", result!!.name)
    }

    @Test
    fun `findToolCall returns null when no match found`() {
        val messages = listOf(
            assistantWithToolCall("call-1", "read_file", """{"path": "/src/foo.kt"}"""),
            toolResult("call-99", "content"),
        )

        val result = SmartPruner.findToolCall(messages, 1, "call-99")
        assertNull(result)
    }
}
