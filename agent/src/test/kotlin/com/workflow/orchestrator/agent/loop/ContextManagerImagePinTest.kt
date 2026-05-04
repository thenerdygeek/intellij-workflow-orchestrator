package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.agent.session.ApiMessage
import com.workflow.orchestrator.agent.session.ApiRole
import com.workflow.orchestrator.agent.session.ContentBlock
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
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
}
