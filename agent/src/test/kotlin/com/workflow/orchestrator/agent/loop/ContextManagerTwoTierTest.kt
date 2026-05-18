package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.ContentPart
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Two-tier compaction behavior — Layer 1 (pre-user handoff) + Layer 2 (anchor user) +
 * Layer 3 (post-user working memory) + Layer 4 (verbatim tail).
 *
 * Spec: docs/superpowers/specs/2026-05-18-context-compaction-two-tier-design.md
 */
class ContextManagerTwoTierTest {

    private lateinit var cm: ContextManager

    @BeforeEach
    fun setUp() {
        cm = ContextManager(maxInputTokens = 10_000)
    }

    @Test
    fun `addUserMessage increments totalUserMessageCount`() {
        assertEquals(0, cm.getTotalUserMessageCountForTest())
        cm.addUserMessage("hello")
        assertEquals(1, cm.getTotalUserMessageCountForTest())
        cm.addUserMessage("world")
        assertEquals(2, cm.getTotalUserMessageCountForTest())
    }

    @Test
    fun `addUserMessageWithParts increments totalUserMessageCount`() {
        assertEquals(0, cm.getTotalUserMessageCountForTest())
        cm.addUserMessageWithParts(listOf(ContentPart.Text("hi")))
        assertEquals(1, cm.getTotalUserMessageCountForTest())
    }

    @Test
    fun `restoreMessages recomputes totalUserMessageCount from saved history`() {
        val saved = listOf(
            ChatMessage(role = "user", content = "first"),
            ChatMessage(role = "assistant", content = "ack"),
            ChatMessage(role = "user", content = "second"),
            ChatMessage(role = "tool", content = "result"),
        )
        cm.restoreMessages(saved)
        assertEquals(2, cm.getTotalUserMessageCountForTest())
    }

    @Test
    fun `restoreMessages resets compaction state fields`() {
        cm.addUserMessage("seeded")
        cm.restoreMessages(listOf(ChatMessage(role = "user", content = "restored")))
        assertNull(cm.getPreviousPreUserSummaryForTest())
        assertNull(cm.getPreviousPostUserSummaryForTest())
        assertNull(cm.getLastCompactionUserMessageCountForTest())
    }
}
