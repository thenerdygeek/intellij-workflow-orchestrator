package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.session.ApiMessage
import com.workflow.orchestrator.agent.session.ApiRole
import com.workflow.orchestrator.agent.session.ContentBlock
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.ContentPart
import com.workflow.orchestrator.core.ai.dto.FunctionCall
import com.workflow.orchestrator.core.ai.dto.ToolCall
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * Phase 5 of the tool-produced-images plan: Cline-style pinning. Any USER
 * `ApiMessage` whose `content` list contains a [ContentBlock.ImageRef] is
 * exempt from Stage 1 (duplicate file-read dedup) and Stage 2 (middle-block
 * truncation) of [ContextManager]'s compaction pipeline.
 *
 * Vision tokens are expensive — re-sending the same screenshot every turn is
 * worse than keeping it pinned, and dropping it mid-conversation strands tool
 * outputs the LLM may need to refer back to.
 */
class ContextManagerImagePinTest {

    @Test
    fun `isImageBearingMessage returns true when content contains ImageRef`() {
        val pinned = ApiMessage(
            role = ApiRole.USER,
            content = listOf(
                ContentBlock.ToolResult("call_a", "screenshot.png", false),
                ContentBlock.ImageRef("sha-1", "image/png", 100, "a.png"),
            )
        )
        assertTrue(ContextManager.isImageBearingMessage(pinned))
    }

    @Test
    fun `isImageBearingMessage returns false when content has no ImageRef`() {
        val plain = ApiMessage(
            role = ApiRole.USER,
            content = listOf(ContentBlock.ToolResult("call_b", "log dump", false))
        )
        assertFalse(ContextManager.isImageBearingMessage(plain))
    }

    @Test
    fun `isImageBearingMessage returns true even when ImageRef coexists with text`() {
        val mixed = ApiMessage(
            role = ApiRole.USER,
            content = listOf(
                ContentBlock.Text("here is the screenshot"),
                ContentBlock.ImageRef("sha-x", "image/png", 1, null)
            )
        )
        assertTrue(ContextManager.isImageBearingMessage(mixed))
    }

    // ---- Stage 1 pin: dedup never replaces an image-bearing tool result ----

    @Test
    fun `Stage 1 dedup never replaces a tool_result that has an ImageRef sibling`() {
        val cm = ContextManager(maxInputTokens = 1000)
        cm.setSystemPrompt("sys")

        // First read of foo.kt — but this slot ALSO carries an image part
        // (e.g. a screenshot the LLM took alongside the file content).
        cm.addAssistantMessage(
            ChatMessage(
                role = "assistant", content = null,
                toolCalls = listOf(
                    ToolCall(id = "c1", type = "function", function = FunctionCall("read_file", """{"path":"foo.kt"}"""))
                )
            )
        )
        // Manually inject the image-bearing tool result (the public addToolResult API
        // doesn't accept parts; cf. ContextManager addToolResult signature).
        cm.addAssistantMessage(
            ChatMessage(
                role = "tool",
                content = "[read_file for 'foo.kt'] Result:\nversion 1 (with screenshot)",
                toolCallId = "c1",
                parts = listOf(
                    ContentPart.Text("[read_file for 'foo.kt'] Result:\nversion 1 (with screenshot)"),
                    ContentPart.Image(sha256 = "sha-pinned", mime = "image/png")
                )
            )
        )

        // Second read of the same file — text-only.
        cm.addAssistantMessage(
            ChatMessage(
                role = "assistant", content = null,
                toolCalls = listOf(
                    ToolCall(id = "c2", type = "function", function = FunctionCall("read_file", """{"path":"foo.kt"}"""))
                )
            )
        )
        cm.addToolResult(
            toolCallId = "c2",
            content = "[read_file for 'foo.kt'] Result:\nversion 2",
            isError = false,
            toolName = "read_file"
        )

        // Re-bootstrap fileReadIndices: the manual addAssistantMessage path bypasses
        // the dedup tracking that addToolResult does, so we trigger a rebuild via
        // restoreMessages with the same content.
        cm.restoreMessages(cm.exportMessages())

        cm.deduplicateFileReads()

        // The image-bearing tool result must NOT have been replaced with the
        // dedup notice — its image part is load-bearing context.
        val toolResults = cm.getMessages().filter { it.role == "tool" }
        assertEquals(2, toolResults.size, "Expected both tool results to be present")

        val pinned = toolResults.firstOrNull { msg ->
            msg.parts?.any { it is ContentPart.Image } == true
        }
        assertNotNull(pinned, "Image-bearing tool result should still be present")
        assertTrue(
            pinned!!.content!!.contains("version 1 (with screenshot)"),
            "Pinned image-bearing tool result content must NOT have been replaced with a dedup notice; got: ${pinned.content}"
        )
        assertFalse(
            pinned.content!!.contains("previously read"),
            "Pinned image-bearing tool result must not contain dedup notice phrasing"
        )
    }

    // ---- Stage 2 pin: truncation must not drop an image-bearing message ----
    // TODO Phase 3: delete this test — truncateConversation() removed in Phase 1

    @Disabled("truncateConversation() removed in Phase 1 redesign — will be deleted in Phase 3")
    @Test
    fun `Stage 2 truncation preserves image-bearing tool_result in the middle`() {
        // TODO Phase 3: delete — truncateConversation(TruncationStrategy.HALF) removed in Phase 1
    }
}
