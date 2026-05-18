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

    // ---- findTokenWeightedCutForLayer4 ----

    @Test
    fun `token-weighted cut returns sliceStart when single small message in slice`() {
        // estimateMessageTokens for "x" is ~ ceil(1/3.5) = 1, well under any realistic target.
        seed(listOf(u("anchor"), a("x")))
        val cut = cm.findTokenWeightedCutForLayer4(
            sliceStart = 1,
            sliceEnd = 2,
            targetTokensFromEnd = 100,
        )
        assertEquals(1, cut, "cut should clamp to sliceStart when slice's total tokens are below target")
    }

    @Test
    fun `token-weighted cut stops at the message that brings sum past target`() {
        // estimateMessageTokens uses char/3.5. Each "x"*350 = 100 tokens.
        val big = "x".repeat(350)
        seed(listOf(
            u("anchor"),
            a(big),  // index 1, ~100 tokens
            a(big),  // index 2, ~100 tokens
            a(big),  // index 3, ~100 tokens
            a(big),  // index 4, ~100 tokens
        ))
        // Walk backward from end (index 5) summing tokens. After including index 4 -> 100,
        // index 3 -> 200, index 2 -> 300 (>= 250). Stop at index 2.
        val cut = cm.findTokenWeightedCutForLayer4(
            sliceStart = 1,
            sliceEnd = 5,
            targetTokensFromEnd = 250,
        )
        assertEquals(2, cut)
    }

    @Test
    fun `token-weighted cut clamps to sliceStart never below`() {
        val big = "x".repeat(350)
        seed(listOf(u("anchor"), a(big), a(big)))
        // Target much larger than slice can supply — cut clamps to sliceStart.
        val cut = cm.findTokenWeightedCutForLayer4(
            sliceStart = 1,
            sliceEnd = 3,
            targetTokensFromEnd = 10_000,
        )
        assertEquals(1, cut, "cut must not go below sliceStart")
    }

    @Test
    fun `token-weighted cut returns sliceEnd when slice is empty`() {
        seed(listOf(u("anchor")))
        val cut = cm.findTokenWeightedCutForLayer4(
            sliceStart = 1,
            sliceEnd = 1,
            targetTokensFromEnd = 100,
        )
        assertEquals(1, cut)
    }

    // ---- snapToToolBoundary ----

    @Test
    fun `snap returns candidate when candidate is already a tool message`() {
        seed(listOf(u("anchor"), a("call"), t("result"), a("call2"), t("result2")))
        val snapped = cm.snapToToolBoundary(candidateIdx = 2, sliceStart = 1)
        assertEquals(2, snapped)
    }

    @Test
    fun `snap walks backward from assistant to nearest preceding tool`() {
        seed(listOf(u("anchor"), a("call"), t("result"), a("call2"), t("result2"), a("call3")))
        // candidateIdx 5 is assistant → walk back. idx 4 is tool → return 4.
        val snapped = cm.snapToToolBoundary(candidateIdx = 5, sliceStart = 1)
        assertEquals(4, snapped)
    }

    @Test
    fun `snap returns sliceEnd when slice is all-assistant from sliceStart down`() {
        seed(listOf(u("anchor"), a("call1"), a("call2"), a("call3")))
        // candidateIdx 3 is assistant. Walk back to 2 (assistant), 1 (assistant) — all the
        // way to sliceStart without finding tool. Return messages.size = 4.
        val snapped = cm.snapToToolBoundary(candidateIdx = 3, sliceStart = 1)
        assertEquals(4, snapped, "all-assistant slice signals 'skip L3' by returning sliceEnd")
    }

    @Test
    fun `snap returns candidate when role is user`() {
        // Defensive: should not be invoked with a user in the slice, but if it is, return as-is.
        seed(listOf(u("anchor"), u("steer"), a("ack")))
        val snapped = cm.snapToToolBoundary(candidateIdx = 1, sliceStart = 1)
        assertEquals(1, snapped)
    }
}
