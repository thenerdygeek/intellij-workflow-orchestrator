package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.FunctionCall
import com.workflow.orchestrator.core.ai.dto.ToolCall
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class PruneTrailingEmptyAssistantsTest {

    private fun newCtx(): ContextManager = ContextManager(
        maxInputTokens = 100_000,
    )

    private fun emptyAssistant() = ChatMessage(role = "assistant", content = null, toolCalls = null)
    private fun blankAssistant() = ChatMessage(role = "assistant", content = "   ", toolCalls = null)
    private fun textAssistant(text: String) = ChatMessage(role = "assistant", content = text, toolCalls = null)
    private fun toolAssistant() = ChatMessage(
        role = "assistant",
        content = null,
        toolCalls = listOf(ToolCall(id = "c1", type = "function", function = FunctionCall(name = "read_file", arguments = "{}"))),
    )
    private fun userMsg(s: String) = ChatMessage(role = "user", content = s)

    @Test
    fun `returns 0 when no trailing empty assistant`() = runTest {
        val ctx = newCtx()
        ctx.restoreMessages(listOf(userMsg("task"), textAssistant("ok"), userMsg("done")))
        assertEquals(0, ctx.pruneTrailingEmptyAssistants())
        assertEquals(3, ctx.getMessages().size)
    }

    @Test
    fun `removes single trailing empty assistant`() = runTest {
        val ctx = newCtx()
        ctx.restoreMessages(listOf(userMsg("task"), textAssistant("step 1"), userMsg("next"), emptyAssistant()))
        assertEquals(1, ctx.pruneTrailingEmptyAssistants())
        val tail = ctx.getMessages().last()
        assertEquals("user", tail.role)
        assertEquals("next", tail.content)
    }

    @Test
    fun `removes multiple contiguous trailing empty assistants`() = runTest {
        val ctx = newCtx()
        ctx.restoreMessages(listOf(
            userMsg("task"),
            textAssistant("step 1"),
            userMsg("next"),
            emptyAssistant(),
            blankAssistant(),
            emptyAssistant(),
        ))
        assertEquals(3, ctx.pruneTrailingEmptyAssistants())
        assertEquals("next", ctx.getMessages().last().content)
    }

    @Test
    fun `stops at first non-empty tail element`() = runTest {
        val ctx = newCtx()
        ctx.restoreMessages(listOf(
            userMsg("task"),
            emptyAssistant(),
            userMsg("interjection"),
            emptyAssistant(),
        ))
        assertEquals(1, ctx.pruneTrailingEmptyAssistants())
        assertEquals("interjection", ctx.getMessages().last().content)
    }

    @Test
    fun `preserves assistant with tool calls even if content is blank`() = runTest {
        val ctx = newCtx()
        ctx.restoreMessages(listOf(userMsg("task"), toolAssistant()))
        assertEquals(0, ctx.pruneTrailingEmptyAssistants())
        assertEquals(2, ctx.getMessages().size)
    }

    @Test
    fun `preserves assistant with real text content`() = runTest {
        val ctx = newCtx()
        ctx.restoreMessages(listOf(userMsg("task"), textAssistant("explanation")))
        assertEquals(0, ctx.pruneTrailingEmptyAssistants())
        assertEquals(2, ctx.getMessages().size)
    }
}
