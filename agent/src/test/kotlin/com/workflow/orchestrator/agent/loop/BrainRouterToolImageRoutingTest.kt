package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.ContentPart
import com.workflow.orchestrator.core.ai.dto.hasImageParts
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Phase 7 Task 7.1 — Pin BrainRouter routing detection for tool-origin images.
 *
 * `BrainRouter` decides which downstream brain handles each turn via
 * `messages.any { it.hasImageParts() }` (see `BrainRouter.kt`). That predicate
 * is position-independent — it fires whether the image-bearing
 * [com.workflow.orchestrator.core.ai.dto.ContentPart.Image] arrives on a
 * `role="user"` turn (user-paste path) or a `role="tool"` turn (Phase 1's
 * `ApiMessage.toChatMessage()` path for tool-produced images).
 *
 * This test pins that contract: a tool-result message carrying a
 * `ContentPart.Image` triggers the same router signal as a user-pasted image.
 * If a future refactor of routing tightens detection to "only user messages",
 * this test catches the regression.
 */
class BrainRouterToolImageRoutingTest {

    @Test
    fun `tool-result message with image part triggers hasImageParts true`() {
        // Shape produced by ApiMessage.toChatMessage() after Phase 1 wiring:
        // a tool turn where ContentBlock.ImageRef alongside the ContentBlock.ToolResult
        // is mapped into ChatMessage.parts.
        val toolMessage = ChatMessage(
            role = "tool",
            content = "downloaded screenshot.png",
            toolCallId = "call_xyz",
            parts = listOf(
                ContentPart.Text("downloaded screenshot.png"),
                ContentPart.Image(sha256 = "deadbeef", mime = "image/png"),
            ),
        )
        val userMessage = ChatMessage(
            role = "user",
            content = "look at the diff",
            parts = null,
        )

        val messages = listOf(userMessage, toolMessage)
        assertTrue(
            messages.any { it.hasImageParts() },
            "router signal must fire when image arrives via tool-result message",
        )
    }

    @Test
    fun `image-bearing tool result plus user follow-up still fires the router signal`() {
        val toolResult = ChatMessage(
            role = "tool",
            content = "ss",
            toolCallId = "c1",
            parts = listOf(
                ContentPart.Text("ss"),
                ContentPart.Image(sha256 = "sha-x", mime = "image/png"),
            ),
        )
        val followUp = ChatMessage(role = "user", content = "now read JIRA-1")
        val messages = listOf(toolResult, followUp)

        assertTrue(
            messages.any { it.hasImageParts() },
            "router signal must fire on tool-origin images even when a later user turn follows",
        )
    }

    @Test
    fun `text-only conversation does not fire the router signal`() {
        val u = ChatMessage(role = "user", content = "hi")
        val a = ChatMessage(role = "assistant", content = "hi back")
        val messages = listOf(u, a)
        assertFalse(messages.any { it.hasImageParts() })
    }

    @Test
    fun `tool result with only text parts does not fire the router signal`() {
        // Defense against false positives: a tool turn that only carries
        // ContentPart.Text (no Image) must NOT fire the router signal.
        val toolMessage = ChatMessage(
            role = "tool",
            content = "result text",
            toolCallId = "c2",
            parts = listOf(ContentPart.Text("result text")),
        )
        val messages = listOf(ChatMessage(role = "user", content = "go"), toolMessage)
        assertFalse(messages.any { it.hasImageParts() })
    }
}
