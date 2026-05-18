package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Pure-function tests for the two-tier compaction helpers:
 * findLastUserIndex, findTokenWeightedCutForLayer4, snapToToolBoundary.
 *
 * Spec: docs/superpowers/specs/2026-05-18-context-compaction-two-tier-design.md
 */
class ContextManagerTwoTierHelpersTest {

    private lateinit var cm: ContextManager

    @BeforeEach
    fun setUp() {
        cm = ContextManager(maxInputTokens = 10_000)
    }

    private fun seed(messages: List<ChatMessage>) {
        cm.restoreMessages(messages)
    }

    private fun u(content: String) = ChatMessage(role = "user", content = content)
    private fun a(content: String) = ChatMessage(role = "assistant", content = content)
    private fun t(content: String) = ChatMessage(role = "tool", content = content)

    @Test
    fun `findLastUserIndex returns -1 on empty history`() {
        seed(emptyList())
        assertEquals(-1, cm.findLastUserIndex())
    }

    @Test
    fun `findLastUserIndex returns -1 when no user message present`() {
        seed(listOf(a("only assistant"), t("only tool")))
        assertEquals(-1, cm.findLastUserIndex())
    }

    @Test
    fun `findLastUserIndex returns index of single user message`() {
        seed(listOf(a("ack"), u("hi"), a("bye")))
        assertEquals(1, cm.findLastUserIndex())
    }

    @Test
    fun `findLastUserIndex returns index of last when multiple users`() {
        seed(listOf(u("first"), a("ack"), u("second"), a("ack"), u("third"), t("result")))
        assertEquals(4, cm.findLastUserIndex())
    }

    @Test
    fun `findLastUserIndex returns last index when only message is user`() {
        seed(listOf(u("only one")))
        assertEquals(0, cm.findLastUserIndex())
    }
}
