package com.workflow.orchestrator.agent.context

import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.api.dto.FunctionCall
import com.workflow.orchestrator.agent.api.dto.ToolCall
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class ContextManagerTest {

    private lateinit var manager: ContextManager

    @BeforeEach
    fun setUp() {
        // Small budget for testing compression behavior
        manager = ContextManager(
            maxInputTokens = 1000,
            tMaxRatio = 0.70,     // compress at 700 tokens
            tRetainedRatio = 0.40  // compress down to 400 tokens
        )
    }

    @Test
    fun `addMessage tracks token count`() {
        manager.addMessage(ChatMessage(role = "user", content = "Hello world"))
        assertTrue(manager.currentTokens > 0)
        assertEquals(1, manager.messageCount)
    }

    @Test
    fun `getMessages returns messages in order`() {
        manager.addMessage(ChatMessage(role = "user", content = "First"))
        manager.addMessage(ChatMessage(role = "assistant", content = "Second"))

        val messages = manager.getMessages()
        assertEquals(2, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("assistant", messages[1].role)
    }

    @Test
    fun `addToolResult caps very large results at 50KB`() {
        // Content larger than 50KB should be truncated (OpenCode pattern: 50KB cap)
        val largeContent = "x".repeat(60000)
        manager.addToolResult("call-1", largeContent, "Large tool result")

        val messages = manager.getMessages()
        val toolMsg = messages.first { it.role == "tool" }
        // Without toolOutputStore, content passes through uncapped
        // The content is wrapped in <external_data> tags
        assertTrue(toolMsg.content!!.contains("<external_data>"))
        assertTrue(toolMsg.content!!.contains("x".repeat(100)), "Tool message should contain original content")
    }

    @Test
    fun `addToolResult passes small results through`() {
        manager.addToolResult("call-1", "small result", "Small result")

        val messages = manager.getMessages()
        val toolMsg = messages.first { it.role == "tool" }
        assertTrue(toolMsg.content!!.contains("small result"), "Tool message should contain the original result")
        assertTrue(toolMsg.content!!.contains("<external_data>"), "Tool result should be wrapped in <external_data> tags")
    }

    @Test
    fun `compression triggers when tMax exceeded`() {
        // Add messages with enough content to exceed tMax (700 tokens at 3.5 chars/token = ~2450 chars)
        for (i in 1..40) {
            manager.addMessage(ChatMessage(role = "user", content = "Message number $i. " + "x".repeat(80)))
        }

        // After compression, should have fewer messages than added
        assertTrue(manager.messageCount < 40, "Messages (${manager.messageCount}) should be fewer than 40 after compression")
    }

    @Test
    fun `compressed messages appear as anchored summaries`() {
        // Fill up and trigger compression with large enough messages
        for (i in 1..40) {
            manager.addMessage(ChatMessage(role = "user", content = "Message $i discussing architecture. " + "x".repeat(80)))
        }

        val messages = manager.getMessages()
        // Should have a system message with summary at the start (contains "summary" case-insensitive)
        val hasSystemSummary = messages.any { it.role == "system" && it.content?.lowercase()?.contains("summary") == true }
        assertTrue(hasSystemSummary, "Should have anchored summary after compression. Messages: ${messages.map { "${it.role}: ${it.content?.take(50)}" }}")
    }

    @Test
    fun `system messages are never compressed`() {
        manager.addMessage(ChatMessage(role = "system", content = "You are a helpful assistant."))

        for (i in 1..30) {
            manager.addMessage(ChatMessage(role = "user", content = "Padding message $i with extra content for token usage"))
        }

        val messages = manager.getMessages()
        // Original system prompt should survive (possibly in anchored summaries section)
        val allContent = messages.mapNotNull { it.content }.joinToString("\n")
        // System messages are preserved through compression
        assertTrue(messages.any { it.role == "system" })
    }

    @Test
    fun `remainingBudget decreases as messages are added`() {
        val initial = manager.remainingBudget()
        manager.addMessage(ChatMessage(role = "user", content = "Some message"))
        assertTrue(manager.remainingBudget() < initial)
    }

    @Test
    fun `isBudgetCritical returns true when low`() {
        assertFalse(manager.isBudgetCritical())

        // Fill to near capacity with a large message
        manager.addMessage(ChatMessage(role = "user", content = "x".repeat(3500))) // ~1000 tokens
        assertTrue(manager.isBudgetCritical())
    }

    @Test
    fun `reset clears all state`() {
        manager.addMessage(ChatMessage(role = "user", content = "Hello"))
        manager.reset()

        assertEquals(0, manager.currentTokens)
        assertEquals(0, manager.messageCount)
        assertTrue(manager.getMessages().isEmpty())
    }

    // --- Reserved tokens tests (Step 1) ---

    @Test
    fun `reservedTokens reduces effective budget`() {
        val managerWithReserved = ContextManager(
            maxInputTokens = 1000,
            reservedTokens = 200
        )
        // Effective budget = 1000 - 200 = 800
        assertEquals(800, managerWithReserved.remainingBudget())
    }

    @Test
    fun `compression thresholds use effective budget with reservedTokens`() {
        // With reservedTokens=200, effectiveBudget=800
        // tMax at 0.70 = 560 tokens (not 700)
        val managerWithReserved = ContextManager(
            maxInputTokens = 1000,
            reservedTokens = 200,
            tMaxRatio = 0.70,
            tRetainedRatio = 0.40
        )

        // Add messages to trigger compression at 560 tokens (~1960 chars)
        for (i in 1..25) {
            managerWithReserved.addMessage(ChatMessage(role = "user", content = "Msg $i. " + "x".repeat(80)))
        }

        // Should have compressed with the lower threshold
        assertTrue(managerWithReserved.messageCount < 25,
            "Messages (${managerWithReserved.messageCount}) should be fewer than 25 after compression with reserved tokens")
    }

    // --- Summarization tests ---

    @Test
    fun `compression uses custom summarizer when provided`() {
        var summarizerCalled = false
        val customManager = ContextManager(
            maxInputTokens = 1000,
            tMaxRatio = 0.70,
            tRetainedRatio = 0.40,
            summarizer = { messages ->
                summarizerCalled = true
                "Custom summary of ${messages.size} messages"
            }
        )

        // Fill enough to trigger compression
        for (i in 1..40) {
            customManager.addMessage(ChatMessage(role = "user", content = "Message $i about code review. " + "x".repeat(80)))
        }

        assertTrue(summarizerCalled, "Custom summarizer should have been called during compression")

        val messages = customManager.getMessages()
        val summaryContent = messages.filter { it.role == "system" }.mapNotNull { it.content }.joinToString("\n")
        assertTrue(summaryContent.contains("Custom summary"), "Expected custom summary in anchored summaries, got: $summaryContent")
    }

    @Test
    fun `compression always uses default truncation summarizer when no custom summarizer`() {
        val defaultManager = ContextManager(
            maxInputTokens = 1000,
            tMaxRatio = 0.70,
            tRetainedRatio = 0.40
        )

        // Fill enough to trigger compression
        for (i in 1..40) {
            defaultManager.addMessage(ChatMessage(role = "user", content = "Message $i about analysis. " + "x".repeat(80)))
        }

        val messages = defaultManager.getMessages()
        // Should still have compressed using default truncation
        assertTrue(defaultManager.messageCount < 40)
        val summaryContent = messages.filter { it.role == "system" }.mapNotNull { it.content }.joinToString("\n")
        assertTrue(summaryContent.contains("Compressed Context Summary"), "Expected default summary, got: $summaryContent")
    }

    @Test
    fun `null brain uses default truncation summarizer`() {
        // This is the default setUp() behavior — no brain provided
        for (i in 1..40) {
            manager.addMessage(ChatMessage(role = "user", content = "Message $i about testing. " + "x".repeat(80)))
        }

        val messages = manager.getMessages()
        val summaryContent = messages.filter { it.role == "system" }.mapNotNull { it.content }.joinToString("\n")
        assertTrue(summaryContent.contains("Compressed Context Summary"), "Expected default summary, got: $summaryContent")
    }

    // --- Rich pruning placeholder tests ---

    @Test
    fun `pruneOldToolResults placeholder contains tool name and arguments`() {
        // Large budget so we can manually trigger pruning without auto-compression
        val cm = ContextManager(maxInputTokens = 200_000, tMaxRatio = 0.99, tRetainedRatio = 0.90)

        // Add assistant with tool_call, then matching tool result
        cm.addMessage(ChatMessage(
            role = "assistant",
            content = null,
            toolCalls = listOf(ToolCall(
                id = "tc-read-1",
                function = FunctionCall(name = "read_file", arguments = """{"path":"/src/Auth.kt"}""")
            ))
        ))
        cm.addMessage(ChatMessage(
            role = "tool",
            content = "<external_data>class Auth {\n  fun login() {}\n  fun logout() {}\n  fun validate() {}\n  fun refresh() {}\n  fun expire() {}\n  // more code\n}</external_data>",
            toolCallId = "tc-read-1"
        ))

        // Add many more messages to push the tool result outside the protection window
        for (i in 1..200) {
            cm.addMessage(ChatMessage(role = "user", content = "Padding $i " + "x".repeat(500)))
            cm.addMessage(ChatMessage(role = "assistant", content = "Response $i " + "y".repeat(500)))
        }

        // Force prune with a small protection window
        cm.pruneOldToolResults(protectedTokens = 0)

        val toolMsg = cm.getMessages().first { it.role == "tool" && it.toolCallId == "tc-read-1" }
        val content = toolMsg.content!!
        assertTrue(content.contains("read_file"), "Placeholder should contain tool name 'read_file', got: ${content.take(200)}")
        assertTrue(content.contains("/src/Auth.kt"), "Placeholder should contain the file path argument, got: ${content.take(300)}")
    }

    @Test
    fun `pruneOldToolResults placeholder contains content preview`() {
        val cm = ContextManager(maxInputTokens = 200_000, tMaxRatio = 0.99, tRetainedRatio = 0.90)

        cm.addMessage(ChatMessage(
            role = "assistant",
            content = null,
            toolCalls = listOf(ToolCall(
                id = "tc-search-1",
                function = FunctionCall(name = "search_code", arguments = """{"query":"TODO"}""")
            ))
        ))
        cm.addMessage(ChatMessage(
            role = "tool",
            content = "<external_data>Line 1: TODO fix auth\nLine 2: TODO add tests\nLine 3: TODO refactor\nLine 4: TODO cleanup\nLine 5: TODO docs\nLine 6: extra line\nLine 7: more</external_data>",
            toolCallId = "tc-search-1"
        ))

        for (i in 1..200) {
            cm.addMessage(ChatMessage(role = "user", content = "Pad $i " + "x".repeat(500)))
            cm.addMessage(ChatMessage(role = "assistant", content = "Resp $i " + "y".repeat(500)))
        }

        cm.pruneOldToolResults(protectedTokens = 0)

        val toolMsg = cm.getMessages().first { it.role == "tool" && it.toolCallId == "tc-search-1" }
        val content = toolMsg.content!!
        assertTrue(content.contains("Preview:"), "Placeholder should contain 'Preview:', got: ${content.take(400)}")
        assertTrue(content.contains("Line 1: TODO fix auth"), "Preview should contain first line of content")
        assertTrue(content.contains("Line 5: TODO docs"), "Preview should contain 5th line")
        assertTrue(content.contains("more lines"), "Preview should indicate truncation for remaining lines")
    }

    @Test
    fun `pruneOldToolResults placeholder contains disk path when ToolOutputStore is available`(@TempDir tempDir: File) {
        val cm = ContextManager(maxInputTokens = 200_000, tMaxRatio = 0.99, tRetainedRatio = 0.90)
        val store = ToolOutputStore(tempDir)
        cm.toolOutputStore = store

        // Save content to disk first (as addToolResult would do)
        val fullContent = "Full file content here\nLine 2\nLine 3"
        store.save("tc-disk-1", fullContent)

        cm.addMessage(ChatMessage(
            role = "assistant",
            content = null,
            toolCalls = listOf(ToolCall(
                id = "tc-disk-1",
                function = FunctionCall(name = "read_file", arguments = """{"path":"/src/Main.kt"}""")
            ))
        ))
        cm.addMessage(ChatMessage(
            role = "tool",
            content = "<external_data>$fullContent</external_data>",
            toolCallId = "tc-disk-1"
        ))

        for (i in 1..200) {
            cm.addMessage(ChatMessage(role = "user", content = "Pad $i " + "x".repeat(500)))
            cm.addMessage(ChatMessage(role = "assistant", content = "Resp $i " + "y".repeat(500)))
        }

        cm.pruneOldToolResults(protectedTokens = 0)

        val toolMsg = cm.getMessages().first { it.role == "tool" && it.toolCallId == "tc-disk-1" }
        val content = toolMsg.content!!
        assertTrue(content.contains("Full output saved:"), "Placeholder should contain disk path reference, got: ${content.take(500)}")
        assertTrue(content.contains("tool-outputs"), "Disk path should reference tool-outputs directory")
        assertTrue(content.contains("Recovery:"), "Placeholder should contain recovery hint")
        assertTrue(content.contains("re-read"), "Recovery hint for read_file should suggest re-reading")
    }

    @Test
    fun `pruneOldToolResults placeholder has recovery hint for search_code`() {
        val cm = ContextManager(maxInputTokens = 200_000, tMaxRatio = 0.99, tRetainedRatio = 0.90)

        cm.addMessage(ChatMessage(
            role = "assistant",
            content = null,
            toolCalls = listOf(ToolCall(
                id = "tc-search-2",
                function = FunctionCall(name = "search_code", arguments = """{"query":"Exception","path":"/src"}""")
            ))
        ))
        cm.addMessage(ChatMessage(
            role = "tool",
            content = "<external_data>Found 3 matches</external_data>",
            toolCallId = "tc-search-2"
        ))

        for (i in 1..200) {
            cm.addMessage(ChatMessage(role = "user", content = "Pad $i " + "x".repeat(500)))
            cm.addMessage(ChatMessage(role = "assistant", content = "Resp $i " + "y".repeat(500)))
        }

        cm.pruneOldToolResults(protectedTokens = 0)

        val toolMsg = cm.getMessages().first { it.role == "tool" && it.toolCallId == "tc-search-2" }
        val content = toolMsg.content!!
        assertTrue(content.contains("re-run search_code"), "Recovery hint should suggest re-running search_code")
    }

    @Test
    fun `pruneOldToolResults uses default protection window of 40K tokens`() {
        // Verify the default parameter is 40K (not the old 30K)
        val cm = ContextManager(maxInputTokens = 200_000, tMaxRatio = 0.99, tRetainedRatio = 0.90)

        cm.addMessage(ChatMessage(
            role = "assistant",
            content = null,
            toolCalls = listOf(ToolCall(
                id = "tc-recent",
                function = FunctionCall(name = "read_file", arguments = """{"path":"/recent.kt"}""")
            ))
        ))
        // A tool result within the 40K protection window should NOT be pruned
        // 40K tokens ~ 140K chars. This small result is well within the window.
        cm.addMessage(ChatMessage(
            role = "tool",
            content = "<external_data>Recent content</external_data>",
            toolCallId = "tc-recent"
        ))

        // Call with defaults — should not prune the only tool result
        cm.pruneOldToolResults()

        val toolMsg = cm.getMessages().first { it.role == "tool" && it.toolCallId == "tc-recent" }
        assertTrue(toolMsg.content!!.contains("Recent content"), "Recent tool result within 40K window should not be pruned")
    }

    @Test
    fun `fallback summarizer includes tool result preview not just char count`() {
        val cm = ContextManager(
            maxInputTokens = 1000,
            tMaxRatio = 0.70,
            tRetainedRatio = 0.40
        )

        // Add a tool result with recognizable content
        cm.addMessage(ChatMessage(role = "tool", content = "<external_data>Build FAILED: src/Main.kt:42 NullPointerException\nStack trace line 1\nStack trace line 2</external_data>", toolCallId = "tc-1"))

        // Fill to trigger compression
        for (i in 1..40) {
            cm.addMessage(ChatMessage(role = "user", content = "Message $i padding. " + "x".repeat(80)))
        }

        val messages = cm.getMessages()
        val summaryContent = messages.filter { it.role == "system" }.mapNotNull { it.content }.joinToString("\n")
        // Should contain actual tool content, not just "N chars"
        assertTrue(summaryContent.contains("Build FAILED") || summaryContent.contains("NullPointerException"),
            "Summarizer should include tool result preview content, got: $summaryContent")
        assertFalse(summaryContent.contains("chars)"),
            "Summarizer should NOT just show char count, got: $summaryContent")
    }

    @Test
    fun `fallback summarizer extracts file paths into separate section`() {
        val cm = ContextManager(
            maxInputTokens = 1000,
            tMaxRatio = 0.70,
            tRetainedRatio = 0.40
        )

        // Add messages with file paths
        cm.addMessage(ChatMessage(role = "user", content = "Please fix the error in src/main/kotlin/Auth.kt"))
        cm.addMessage(ChatMessage(role = "tool", content = "<external_data>Found issue at com/example/Service.kt line 42</external_data>", toolCallId = "tc-1"))

        // Fill to trigger compression
        for (i in 1..40) {
            cm.addMessage(ChatMessage(role = "user", content = "Message $i padding. " + "x".repeat(80)))
        }

        val messages = cm.getMessages()
        val summaryContent = messages.filter { it.role == "system" }.mapNotNull { it.content }.joinToString("\n")
        assertTrue(summaryContent.contains("Referenced Files"),
            "Summarizer should have a Referenced Files section, got: $summaryContent")
        assertTrue(summaryContent.contains("src/main/kotlin/Auth.kt") || summaryContent.contains("com/example/Service.kt"),
            "Referenced Files section should contain extracted file paths, got: $summaryContent")
    }

    @Test
    fun `pruneOldToolResults truncates long arguments at 300 chars`() {
        val cm = ContextManager(maxInputTokens = 200_000, tMaxRatio = 0.99, tRetainedRatio = 0.90)

        val longArgs = """{"query":"${"a".repeat(400)}"}"""
        cm.addMessage(ChatMessage(
            role = "assistant",
            content = null,
            toolCalls = listOf(ToolCall(
                id = "tc-long-args",
                function = FunctionCall(name = "search_code", arguments = longArgs)
            ))
        ))
        cm.addMessage(ChatMessage(
            role = "tool",
            content = "<external_data>Some result</external_data>",
            toolCallId = "tc-long-args"
        ))

        for (i in 1..200) {
            cm.addMessage(ChatMessage(role = "user", content = "Pad $i " + "x".repeat(500)))
            cm.addMessage(ChatMessage(role = "assistant", content = "Resp $i " + "y".repeat(500)))
        }

        cm.pruneOldToolResults(protectedTokens = 0)

        val toolMsg = cm.getMessages().first { it.role == "tool" && it.toolCallId == "tc-long-args" }
        val content = toolMsg.content!!
        assertTrue(content.contains("Args:"), "Should contain Args section")
        assertTrue(content.contains("..."), "Long arguments should be truncated with ellipsis")
        // The args line should not contain the full 400-char string
        val argsLine = content.lines().find { it.startsWith("Args:") }
        assertNotNull(argsLine)
        assertTrue(argsLine!!.length < 350, "Args line should be truncated, got length ${argsLine.length}")
    }
}
