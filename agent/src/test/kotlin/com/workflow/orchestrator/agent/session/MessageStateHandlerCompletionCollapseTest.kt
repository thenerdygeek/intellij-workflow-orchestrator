package com.workflow.orchestrator.agent.session

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * Pins [MessageStateHandler.collapseLastCompletionToolPair].
 *
 * Mirrors the in-memory contract from [com.workflow.orchestrator.agent.loop.ContextManager].
 * The on-disk side runs through `saveApiHistoryInternal` (atomic write-then-rename) so
 * the tests reload from disk to also pin the persisted shape.
 *
 * See [ContextManagerCompletionCollapseTest][com.workflow.orchestrator.agent.loop.ContextManagerCompletionCollapseTest]
 * for the underlying motivation: the trailing `[assistant w/ completion tool_call,
 * USER w/ ToolResult]` pair, if left in place, gets merged into the next user turn
 * during sanitization and causes resume to auto-iterate.
 */
class MessageStateHandlerCompletionCollapseTest {

    @TempDir lateinit var tempDir: File

    private fun newHandler(id: String = "s1"): MessageStateHandler =
        MessageStateHandler(baseDir = tempDir, sessionId = id, taskText = "test task")

    private fun userMsg(text: String) = ApiMessage(
        role = ApiRole.USER,
        content = listOf(ContentBlock.Text(text = text)),
        ts = 1_000L,
    )

    private fun assistantWithCompletionToolUse(
        toolUseId: String,
        toolName: String = "attempt_completion",
        streamingText: String? = null,
    ): ApiMessage = ApiMessage(
        role = ApiRole.ASSISTANT,
        content = buildList {
            if (streamingText != null) add(ContentBlock.Text(streamingText))
            add(
                ContentBlock.ToolUse(
                    id = toolUseId,
                    name = toolName,
                    input = """{"kind":"done","result":"Done."}""",
                ),
            )
        },
        ts = 1_000L,
    )

    private fun userToolResult(toolUseId: String, content: String, isError: Boolean = false) = ApiMessage(
        role = ApiRole.USER,
        content = listOf(ContentBlock.ToolResult(toolUseId = toolUseId, content = content, isError = isError)),
        ts = 1_000L,
    )

    @Test
    fun `collapses a fresh attempt_completion pair into a single assistant text turn`() = runTest {
        val handler = newHandler()
        handler.addToApiConversationHistory(userMsg("refactor this"))
        handler.addToApiConversationHistory(assistantWithCompletionToolUse(toolUseId = "tu-1"))
        handler.addToApiConversationHistory(userToolResult(toolUseId = "tu-1", content = "Refactor complete."))

        val collapsed = handler.collapseLastCompletionToolPair()
        assertTrue(collapsed)

        // In-memory view
        val history = handler.getApiConversationHistory()
        assertEquals(2, history.size, "trailing pair must collapse into a single assistant turn")
        assertEquals(ApiRole.USER, history[0].role)
        assertEquals(ApiRole.ASSISTANT, history[1].role)
        val tailContent = history[1].content
        assertEquals(1, tailContent.size, "the rewritten assistant carries a single Text block")
        val text = tailContent.single() as ContentBlock.Text
        assertEquals("Refactor complete.", text.text)

        // On-disk view — same shape after reload
        val reloaded = MessageStateHandler.loadApiHistory(File(tempDir, "sessions/s1"))
        assertEquals(2, reloaded.size)
        assertEquals(ApiRole.ASSISTANT, reloaded[1].role)
        assertEquals(
            "Refactor complete.",
            (reloaded[1].content.single() as ContentBlock.Text).text,
        )
    }

    @Test
    fun `task_report is also collapsed (sub-agent terminator)`() = runTest {
        val handler = newHandler("s-tr")
        handler.addToApiConversationHistory(userMsg("explore"))
        handler.addToApiConversationHistory(
            assistantWithCompletionToolUse(toolUseId = "tu-tr", toolName = "task_report"),
        )
        handler.addToApiConversationHistory(
            userToolResult(toolUseId = "tu-tr", content = "Exploration findings: ..."),
        )

        assertTrue(handler.collapseLastCompletionToolPair())
        val history = handler.getApiConversationHistory()
        assertEquals(ApiRole.ASSISTANT, history.last().role)
        assertEquals(
            "Exploration findings: ...",
            (history.last().content.single() as ContentBlock.Text).text,
        )
    }

    @Test
    fun `streaming text is preserved and combined with the result text`() = runTest {
        val handler = newHandler("s-2")
        handler.addToApiConversationHistory(userMsg("refactor this"))
        handler.addToApiConversationHistory(
            assistantWithCompletionToolUse(
                toolUseId = "tu-2",
                streamingText = "I extracted TokenService and added 12 tests.",
            ),
        )
        handler.addToApiConversationHistory(
            userToolResult(toolUseId = "tu-2", content = "Refactor complete."),
        )

        assertTrue(handler.collapseLastCompletionToolPair())
        val history = handler.getApiConversationHistory()
        val text = (history.last().content.single() as ContentBlock.Text).text
        assertEquals(
            "I extracted TokenService and added 12 tests.\n\nRefactor complete.",
            text,
        )
    }

    @Test
    fun `non-completion tool pairs are left alone`() = runTest {
        val handler = newHandler("s-3")
        handler.addToApiConversationHistory(userMsg("read foo.kt"))
        handler.addToApiConversationHistory(
            assistantWithCompletionToolUse(toolUseId = "tu-rf", toolName = "read_file"),
        )
        handler.addToApiConversationHistory(
            userToolResult(toolUseId = "tu-rf", content = "// foo.kt contents"),
        )

        assertFalse(handler.collapseLastCompletionToolPair())
        // Trailing pair must remain — three messages total, ending with the tool result.
        val history = handler.getApiConversationHistory()
        assertEquals(3, history.size)
        assertEquals(ApiRole.USER, history.last().role)
        assertTrue(history.last().content.first() is ContentBlock.ToolResult)
    }

    @Test
    fun `mismatched tool_use_id leaves the pair untouched`() = runTest {
        val handler = newHandler("s-4")
        handler.addToApiConversationHistory(userMsg("a"))
        handler.addToApiConversationHistory(assistantWithCompletionToolUse(toolUseId = "tu-A"))
        handler.addToApiConversationHistory(userToolResult(toolUseId = "tu-OTHER", content = "stray"))

        assertFalse(handler.collapseLastCompletionToolPair())
        assertEquals(3, handler.getApiConversationHistory().size)
    }

    @Test
    fun `no-op on empty history`() = runTest {
        val handler = newHandler("s-empty")
        assertFalse(handler.collapseLastCompletionToolPair())
        assertTrue(handler.getApiConversationHistory().isEmpty())
    }

    @Test
    fun `idempotent for the success case`() = runTest {
        val handler = newHandler("s-idem")
        handler.addToApiConversationHistory(userMsg("x"))
        handler.addToApiConversationHistory(assistantWithCompletionToolUse(toolUseId = "tu-i"))
        handler.addToApiConversationHistory(userToolResult(toolUseId = "tu-i", content = "Done."))

        assertTrue(handler.collapseLastCompletionToolPair())
        val sizeAfterFirst = handler.getApiConversationHistory().size
        assertFalse(handler.collapseLastCompletionToolPair())
        assertEquals(sizeAfterFirst, handler.getApiConversationHistory().size)
    }

    @Test
    fun `next user turn after collapse persists alongside (no merge yet — that runs at the wire layer)`() = runTest {
        // This pins what consumers can rely on: after the collapse, the next user
        // message lands as its own ApiMessage. The actual sanitization-layer merge
        // happens later in SourcegraphChatClient — but with the trailing tool result
        // gone, the merge is between assistant and user, which is exactly what we
        // want. Anything that DOES need to merge can do so safely; nothing gets
        // welded onto a `"TOOL RESULT:..."` prefix anymore.
        val handler = newHandler("s-followup")
        handler.addToApiConversationHistory(userMsg("refactor this"))
        handler.addToApiConversationHistory(assistantWithCompletionToolUse(toolUseId = "tu-fu"))
        handler.addToApiConversationHistory(userToolResult(toolUseId = "tu-fu", content = "Refactor complete."))
        assertTrue(handler.collapseLastCompletionToolPair())

        // Now the user accepts the next-step hint or types a follow-up.
        handler.addToApiConversationHistory(userMsg("run the failing tests"))

        val history = handler.getApiConversationHistory()
        assertEquals(3, history.size)
        assertEquals(ApiRole.USER, history[0].role)         // original task
        assertEquals(ApiRole.ASSISTANT, history[1].role)    // collapsed completion (plain text)
        assertEquals(ApiRole.USER, history[2].role)         // follow-up user message — clean, no merge
        // Verify the follow-up user message contains ONLY the user's prompt — the
        // prior completion's result is on the *previous* (assistant) turn, not this one.
        val followUpText = (history[2].content.single() as ContentBlock.Text).text
        assertEquals("run the failing tests", followUpText)
    }
}
