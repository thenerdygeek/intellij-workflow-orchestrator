package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.FunctionCall
import com.workflow.orchestrator.core.ai.dto.ToolCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Standalone coverage for [ContextManager.collapseLastCompletionToolPair].
 *
 * Tests the collapse contract directly without going through [compact].
 */
class ContextManagerCollapseTest {

    private lateinit var cm: ContextManager

    @BeforeEach
    fun setUp() {
        cm = ContextManager(maxInputTokens = 100_000)
    }

    private fun assistantWithCompletion(
        toolCallId: String,
        toolName: String = "attempt_completion",
        streamingText: String? = null,
        argsJson: String = """{"kind":"done","result":"Done."}""",
    ): ChatMessage = ChatMessage(
        role = "assistant",
        content = streamingText,
        toolCalls = listOf(
            ToolCall(id = toolCallId, function = FunctionCall(name = toolName, arguments = argsJson)),
        ),
    )

    @Test
    fun `collapses attempt_completion pair into single assistant turn`() {
        cm.setSystemPrompt("sys")
        cm.addUserMessage("do the thing")
        cm.addAssistantMessage(assistantWithCompletion(toolCallId = "tc-1"))
        cm.addToolResult(toolCallId = "tc-1", content = "Refactor complete.", isError = false, toolName = "attempt_completion")

        val collapsed = cm.collapseLastCompletionToolPair()

        assertTrue(collapsed)
        val msgs = cm.getMessages()
        assertEquals(3, msgs.size)
        assertEquals("assistant", msgs[2].role)
        assertEquals("Refactor complete.", msgs[2].content)
        assertNull(msgs[2].toolCalls)
    }

    @Test
    fun `collapses task_report pair (sub-agent terminator)`() {
        cm.setSystemPrompt("sys")
        cm.addUserMessage("explore")
        cm.addAssistantMessage(
            assistantWithCompletion(
                toolCallId = "tc-tr",
                toolName = "task_report",
                argsJson = """{"summary":"done","findings":[]}""",
            ),
        )
        cm.addToolResult(toolCallId = "tc-tr", content = "Sub-agent done.", isError = false, toolName = "task_report")

        assertTrue(cm.collapseLastCompletionToolPair())
        val msgs = cm.getMessages()
        assertEquals("assistant", msgs.last().role)
        assertEquals("Sub-agent done.", msgs.last().content)
        assertNull(msgs.last().toolCalls)
    }

    @Test
    fun `streaming text is combined with result text`() {
        cm.setSystemPrompt("sys")
        cm.addUserMessage("refactor")
        cm.addAssistantMessage(assistantWithCompletion(toolCallId = "tc-2", streamingText = "Extracted TokenService."))
        cm.addToolResult(toolCallId = "tc-2", content = "Refactor complete.", isError = false, toolName = "attempt_completion")

        assertTrue(cm.collapseLastCompletionToolPair())
        assertEquals(
            "Extracted TokenService.\n\nRefactor complete.",
            cm.getMessages().last().content,
        )
        assertNull(cm.getMessages().last().toolCalls)
    }

    @Test
    fun `does not collapse a non-completion tool pair`() {
        cm.setSystemPrompt("sys")
        cm.addUserMessage("read foo.kt")
        cm.addAssistantMessage(
            assistantWithCompletion(toolCallId = "tc-rf", toolName = "read_file", argsJson = """{"file_path":"foo.kt"}"""),
        )
        cm.addToolResult(toolCallId = "tc-rf", content = "// file contents", isError = false, toolName = "read_file")

        assertFalse(cm.collapseLastCompletionToolPair())
        assertEquals("tool", cm.getMessages().last().role)
    }

    @Test
    fun `does not collapse when toolCallId does not match`() {
        cm.setSystemPrompt("sys")
        cm.addUserMessage("a")
        cm.addAssistantMessage(assistantWithCompletion(toolCallId = "tc-A"))
        cm.addToolResult(toolCallId = "tc-OTHER", content = "foo", isError = false, toolName = "attempt_completion")

        assertFalse(cm.collapseLastCompletionToolPair())
        assertEquals("tool", cm.getMessages().last().role)
    }

    @Test
    fun `is a no-op on an empty conversation`() {
        assertFalse(cm.collapseLastCompletionToolPair())
        assertTrue(cm.getMessages().isEmpty())
    }

    @Test
    fun `is idempotent — second call is a no-op`() {
        cm.setSystemPrompt("sys")
        cm.addUserMessage("x")
        cm.addAssistantMessage(assistantWithCompletion(toolCallId = "tc-i"))
        cm.addToolResult(toolCallId = "tc-i", content = "Done.", isError = false, toolName = "attempt_completion")

        assertTrue(cm.collapseLastCompletionToolPair(), "first call collapses")
        val sizeAfterFirst = cm.getMessages().size
        assertFalse(cm.collapseLastCompletionToolPair(), "second call is a no-op")
        assertEquals(sizeAfterFirst, cm.getMessages().size)
    }

    @Test
    fun `error-flagged result has ERROR prefix stripped`() {
        cm.setSystemPrompt("sys")
        cm.addUserMessage("x")
        cm.addAssistantMessage(assistantWithCompletion(toolCallId = "tc-err"))
        cm.addToolResult(toolCallId = "tc-err", content = "needs discovery", isError = true, toolName = "attempt_completion")

        assertTrue(cm.collapseLastCompletionToolPair())
        assertEquals("needs discovery", cm.getMessages().last().content)
    }
}
