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
 * Pins [ContextManager.collapseLastCompletionToolPair].
 *
 * The collapse exists to fix two failure modes that otherwise affect every follow-up
 * turn after `attempt_completion` (or `task_report`) and every resume of a session
 * whose tail is the same pair:
 *
 *   1. Sourcegraph's `sanitizeMessages` converts `role: tool` into `role: user` with
 *      a `"TOOL RESULT:\n..."` prefix and merges consecutive same-role turns. Without
 *      the collapse, the next user prompt is welded onto the prior completion's tool
 *      result and the LLM re-issues `attempt_completion` with similar arguments.
 *
 *   2. Resume of a session whose `RESUME_COMPLETED_TASK` short-circuit doesn't fire
 *      (e.g. a follow-up was queued but the LLM didn't get to answer before the IDE
 *      closed) hits the loop with a trailing tool result and auto-iterates.
 *
 * These tests pin the collapse contract — *not* the failure modes themselves; those
 * are integration concerns. Each test sets up a representative trailing shape and
 * asserts the collapse output.
 */
class ContextManagerCompletionCollapseTest {

    private lateinit var cm: ContextManager

    @BeforeEach
    fun setUp() {
        cm = ContextManager(maxInputTokens = 100_000)
    }

    private fun assistantWithCompletionToolCall(
        toolCallId: String,
        toolName: String = "attempt_completion",
        streamingText: String? = null,
        argsJson: String = """{"kind":"done","result":"Refactor complete."}""",
    ): ChatMessage = ChatMessage(
        role = "assistant",
        content = streamingText,
        toolCalls = listOf(
            ToolCall(id = toolCallId, function = FunctionCall(name = toolName, arguments = argsJson)),
        ),
    )

    @Test
    fun `collapses a freshly-completed attempt_completion pair into a single assistant turn`() {
        cm.setSystemPrompt("sys")
        cm.addUserMessage("refactor this")
        // Simulate the post-LLM persistence: assistant w/ tool_call, then tool result.
        cm.addAssistantMessage(assistantWithCompletionToolCall(toolCallId = "tc-1"))
        cm.addToolResult(
            toolCallId = "tc-1",
            content = "Refactor complete.",
            isError = false,
            toolName = "attempt_completion",
        )

        val collapsed = cm.collapseLastCompletionToolPair()
        assertTrue(collapsed, "completion pair must collapse")

        val msgs = cm.getMessages()
        // Expect: system, user, assistant (collapsed). No tool message at the tail.
        assertEquals(3, msgs.size, "trailing pair must collapse into a single assistant turn")
        assertEquals("system", msgs[0].role)
        assertEquals("user", msgs[1].role)
        assertEquals("assistant", msgs[2].role)
        assertEquals("Refactor complete.", msgs[2].content)
        assertNull(msgs[2].toolCalls, "the rewritten assistant must carry no tool_calls")
    }

    @Test
    fun `task_report pair is also collapsed (sub-agent terminator)`() {
        cm.setSystemPrompt("sys")
        cm.addUserMessage("explore the agent module")
        cm.addAssistantMessage(
            assistantWithCompletionToolCall(
                toolCallId = "tc-tr",
                toolName = "task_report",
                argsJson = """{"summary":"explored","findings":["a","b"]}""",
            ),
        )
        cm.addToolResult(
            toolCallId = "tc-tr",
            content = "Sub-agent reported: explored ok.",
            isError = false,
            toolName = "task_report",
        )

        assertTrue(cm.collapseLastCompletionToolPair())
        val msgs = cm.getMessages()
        assertEquals("assistant", msgs.last().role)
        assertEquals("Sub-agent reported: explored ok.", msgs.last().content)
        assertNull(msgs.last().toolCalls)
    }

    @Test
    fun `streaming text is preserved and combined with the result`() {
        cm.setSystemPrompt("sys")
        cm.addUserMessage("refactor this")
        cm.addAssistantMessage(
            assistantWithCompletionToolCall(
                toolCallId = "tc-2",
                streamingText = "I extracted TokenService and added 12 unit tests.",
            ),
        )
        cm.addToolResult(
            toolCallId = "tc-2",
            content = "Refactor complete.",
            isError = false,
            toolName = "attempt_completion",
        )

        assertTrue(cm.collapseLastCompletionToolPair())
        val msgs = cm.getMessages()
        // The combined text keeps the LLM's narrative and appends the structured
        // result — joined by a blank line so the LLM reads them as distinct paragraphs.
        assertEquals(
            "I extracted TokenService and added 12 unit tests.\n\nRefactor complete.",
            msgs.last().content,
        )
        assertNull(msgs.last().toolCalls)
    }

    @Test
    fun `does not collapse a non-completion tool pair`() {
        cm.setSystemPrompt("sys")
        cm.addUserMessage("read foo.kt")
        cm.addAssistantMessage(
            assistantWithCompletionToolCall(
                toolCallId = "tc-rf",
                toolName = "read_file",
                argsJson = """{"file_path":"foo.kt"}""",
            ),
        )
        cm.addToolResult(
            toolCallId = "tc-rf",
            content = "// file contents",
            isError = false,
            toolName = "read_file",
        )

        assertFalse(cm.collapseLastCompletionToolPair())
        // Tail must remain the tool result; non-completion tools are not collapsed.
        val msgs = cm.getMessages()
        assertEquals("tool", msgs.last().role)
    }

    @Test
    fun `does not collapse when the trailing tool result toolCallId does not match the assistant tool_call`() {
        cm.setSystemPrompt("sys")
        cm.addUserMessage("a")
        cm.addAssistantMessage(assistantWithCompletionToolCall(toolCallId = "tc-A"))
        // Mismatched tool_call_id — defends against rare interleaving where a stray
        // tool result lands behind a completion's assistant message.
        cm.addToolResult(
            toolCallId = "tc-OTHER",
            content = "foo",
            isError = false,
            toolName = "attempt_completion",
        )

        assertFalse(cm.collapseLastCompletionToolPair())
        // Tail unchanged — no rewrite.
        assertEquals("tool", cm.getMessages().last().role)
    }

    @Test
    fun `is a no-op on an empty conversation`() {
        assertFalse(cm.collapseLastCompletionToolPair())
        assertTrue(cm.getMessages().isEmpty())
    }

    @Test
    fun `is a no-op when called twice (idempotent for the success case)`() {
        cm.setSystemPrompt("sys")
        cm.addUserMessage("x")
        cm.addAssistantMessage(assistantWithCompletionToolCall(toolCallId = "tc-i"))
        cm.addToolResult(
            toolCallId = "tc-i",
            content = "Done.",
            isError = false,
            toolName = "attempt_completion",
        )

        assertTrue(cm.collapseLastCompletionToolPair(), "first call collapses")
        val sizeAfterFirst = cm.getMessages().size
        assertFalse(
            cm.collapseLastCompletionToolPair(),
            "second call is a no-op — tail is now a plain assistant turn",
        )
        assertEquals(sizeAfterFirst, cm.getMessages().size)
    }

    @Test
    fun `error-flagged completion result strips the ERROR prefix that addToolResult prepends`() {
        cm.setSystemPrompt("sys")
        cm.addUserMessage("x")
        cm.addAssistantMessage(assistantWithCompletionToolCall(toolCallId = "tc-err"))
        // ContextManager.addToolResult prepends "[ERROR] " when isError=true. The
        // collapse strips that prefix so the rewritten assistant text reads naturally
        // even on the rare error path.
        cm.addToolResult(
            toolCallId = "tc-err",
            content = "kind=heads_up requires discovery",
            isError = true,
            toolName = "attempt_completion",
        )

        assertTrue(cm.collapseLastCompletionToolPair())
        assertEquals("kind=heads_up requires discovery", cm.getMessages().last().content)
    }
}
