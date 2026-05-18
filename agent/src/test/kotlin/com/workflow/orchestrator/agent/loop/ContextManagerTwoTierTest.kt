package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.core.ai.LlmBrain
import com.workflow.orchestrator.core.ai.dto.ChatCompletionResponse
import com.workflow.orchestrator.core.ai.dto.ChatMessage
import com.workflow.orchestrator.core.ai.dto.Choice
import com.workflow.orchestrator.core.ai.dto.ContentPart
import com.workflow.orchestrator.core.ai.dto.UsageInfo
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
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
    private lateinit var brain: LlmBrain

    @BeforeEach
    fun setUp() {
        cm = ContextManager(maxInputTokens = 10_000)
        brain = mockk(relaxed = true)
    }

    private fun mockBrainResponse(content: String) {
        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Success(
            ChatCompletionResponse(
                id = "test",
                choices = listOf(
                    Choice(
                        index = 0,
                        message = ChatMessage(role = "assistant", content = content),
                        finishReason = "stop",
                    ),
                ),
                usage = UsageInfo(promptTokens = 0, completionTokens = 0, totalTokens = 0),
            ),
        )
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

    @Test
    fun `case A first compaction builds L1 L2 L3 L4 in correct order`() = runTest {
        // ~300 tokens each (1050/3.5 + 4 overhead); 14 post-user msgs = ~4256 tokens
        // > 20% of 10K (2000 token L4 target), so L3 summarization runs
        val big = "x".repeat(1050)
        // 20 messages: 5 pre-user (idx 0-4), user at idx 5, 14 post-user (idx 6-19)
        cm.addUserMessage("first")
        cm.addAssistantMessage(ChatMessage(role = "assistant", content = big))
        cm.addToolResult(toolCallId = "1", content = big, isError = false, toolName = "read_file")
        cm.addAssistantMessage(ChatMessage(role = "assistant", content = big))
        cm.addToolResult(toolCallId = "2", content = big, isError = false, toolName = "read_file")
        cm.addUserMessage("anchor")  // lastUserIdx = 5
        repeat(7) {
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = big))
            cm.addToolResult(toolCallId = "t$it", content = big, isError = false, toolName = "read_file")
        }

        mockBrainResponse("MOCK-SUMMARY")
        val result = cm.compact(brain, force = true, iterationsSinceLastUser = 7)

        assertTrue(result is ContextManager.CompactResult.Compacted, "expected Compacted, got $result")
        val msgs = cm.exportMessages()
        // Expected shape: [L1 assistant][L2 user "anchor"][L3 assistant][L4 ...]
        assertEquals("assistant", msgs[0].role, "L1 must be assistant")
        assertTrue(msgs[0].content!!.startsWith("[Context Handoff"), "L1 must use handoff header")
        assertEquals("user", msgs[1].role, "L2 must be user")
        assertEquals("anchor", msgs[1].content)
        assertEquals("assistant", msgs[2].role, "L3 must be assistant")
        assertTrue(msgs[2].content!!.startsWith("[Working Memory"), "L3 must use working memory header")
        assertTrue(msgs.size > 3, "L4 must contain at least one message")
        // Brain called twice — once for L1, once for L3
        coVerify(exactly = 2) { brain.chat(any(), any(), any(), any()) }
    }

    @Test
    fun `case B with non-empty L1 reuses prior summary verbatim`() = runTest {
        // ~300 tokens each; 20 post-user msgs = ~6000 tokens > 2000 (20% L4 target), so L3 runs
        val big = "x".repeat(1050)
        // Pre-populate pre-user content so L1 is non-empty
        repeat(3) {
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = big))
            cm.addToolResult(toolCallId = "pre$it", content = big, isError = false, toolName = "read_file")
        }
        cm.addUserMessage("anchor")
        repeat(10) {
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = big))
            cm.addToolResult(toolCallId = "t$it", content = big, isError = false, toolName = "read_file")
        }
        mockBrainResponse("MOCK-L1-SUMMARY")
        cm.compact(brain, force = true, iterationsSinceLastUser = 10)
        // First call: L1 + L3 = 2 brain calls
        coVerify(exactly = 2) { brain.chat(any(), any(), any(), any()) }

        // Append more, don't add new user — Case B
        repeat(5) {
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = big))
            cm.addToolResult(toolCallId = "u$it", content = big, isError = false, toolName = "read_file")
        }
        mockBrainResponse("MOCK-L3-NEW")
        val result = cm.compact(brain, force = true, iterationsSinceLastUser = 15)

        assertTrue(result is ContextManager.CompactResult.Compacted)
        // Case B: L1 reused verbatim, L3 re-summarized → 1 new brain call → total 2 + 1 = 3
        coVerify(exactly = 3) { brain.chat(any(), any(), any(), any()) }
        // L1 in messages should contain the FIRST summary text, not the new one
        val msgs = cm.exportMessages()
        assertTrue(msgs[0].content!!.contains("MOCK-L1-SUMMARY"), "L1 should be the original Case-A summary")
    }

    @Test
    fun `case A with no pre-user messages skips L1 entirely`() = runTest {
        // ~300 tokens each; 20 post-user msgs = ~6000 tokens > 2000 (20% L4 target), so L3 runs
        val big = "x".repeat(1050)
        cm.addUserMessage("anchor")  // lastUserIdx = 0
        repeat(10) {
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = big))
            cm.addToolResult(toolCallId = "t$it", content = big, isError = false, toolName = "read_file")
        }
        mockBrainResponse("MOCK-L3-SUMMARY")
        val result = cm.compact(brain, force = true, iterationsSinceLastUser = 10)

        assertTrue(result is ContextManager.CompactResult.Compacted)
        val msgs = cm.exportMessages()
        // Shape: [L2 user][L3 assistant][L4 ...]
        assertEquals("user", msgs[0].role, "no L1 means message 0 is L2 user")
        assertEquals("anchor", msgs[0].content)
        assertEquals("assistant", msgs[1].role, "L3 follows L2 directly")
        assertTrue(msgs[1].content!!.startsWith("[Working Memory"))
        // Brain called exactly once (L3 only — L1 skipped because prefix empty)
        coVerify(exactly = 1) { brain.chat(any(), any(), any(), any()) }
    }

    @Test
    fun `iterations le 5 skips L3 when utilization stays healthy after L1`() = runTest {
        val small = "x".repeat(35)  // ~10 tokens each
        repeat(20) {
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = small))
            cm.addToolResult(toolCallId = "p$it", content = small, isError = false, toolName = "read_file")
        }
        cm.addUserMessage("anchor")
        repeat(3) {
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = small))
            cm.addToolResult(toolCallId = "t$it", content = small, isError = false, toolName = "read_file")
        }
        mockBrainResponse("MOCK-L1")
        val result = cm.compact(brain, force = true, iterationsSinceLastUser = 3)

        assertTrue(result is ContextManager.CompactResult.Compacted)
        val msgs = cm.exportMessages()
        // Shape: [L1][L2][3 asst/tool pairs verbatim] — no L3
        assertEquals("assistant", msgs[0].role)
        assertTrue(msgs[0].content!!.startsWith("[Context Handoff"))
        assertEquals("user", msgs[1].role)
        assertEquals("assistant", msgs[2].role)
        assertFalse(msgs[2].content!!.startsWith("[Working Memory"), "L3 must be absent")
        coVerify(exactly = 1) { brain.chat(any(), any(), any(), any()) }
    }

    @Test
    fun `iterations le 5 forces L3 when utilization is over budget`() = runTest {
        val smallBudgetCm = ContextManager(maxInputTokens = 1000)  // small budget so it's easy to exceed
        val big = "x".repeat(700)  // ~200 tokens each
        repeat(2) {
            smallBudgetCm.addAssistantMessage(ChatMessage(role = "assistant", content = big))
            smallBudgetCm.addToolResult(toolCallId = "p$it", content = big, isError = false, toolName = "read_file")
        }
        smallBudgetCm.addUserMessage("anchor")
        repeat(3) {
            smallBudgetCm.addAssistantMessage(ChatMessage(role = "assistant", content = big))
            smallBudgetCm.addToolResult(toolCallId = "t$it", content = big, isError = false, toolName = "read_file")
        }
        mockBrainResponse("MOCK-SUMMARY")
        val result = smallBudgetCm.compact(brain, force = true, iterationsSinceLastUser = 3)

        assertTrue(result is ContextManager.CompactResult.Compacted)
        val msgs = smallBudgetCm.exportMessages()
        // L3 should be present even though iters=3 because post-L1 utilization estimate >= 88%
        val hasL3 = msgs.any { it.content?.startsWith("[Working Memory") == true }
        assertTrue(hasL3, "L3 should be forced when post-L1 estimated utilization >= 88%")
        coVerify(exactly = 2) { brain.chat(any(), any(), any(), any()) }
    }

    @Test
    fun `no user messages in history triggers degenerate single-summary path`() = runTest {
        val big = "x".repeat(350)
        repeat(5) { cm.addAssistantMessage(ChatMessage(role = "assistant", content = big)) }
        mockBrainResponse("DEGENERATE-SUMMARY")
        val result = cm.compact(brain, force = true, iterationsSinceLastUser = 5)

        assertTrue(result is ContextManager.CompactResult.Compacted)
        val msgs = cm.exportMessages()
        assertEquals(1, msgs.size, "degenerate path collapses to a single message")
        assertEquals("assistant", msgs[0].role)
        assertTrue(msgs[0].content!!.startsWith("[Context Handoff"))
        coVerify(exactly = 1) { brain.chat(any(), any(), any(), any()) }
    }

    @Test
    fun `both summarization calls failing returns Failed`() = runTest {
        val big = "x".repeat(350)
        repeat(3) {
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = big))
            cm.addToolResult(toolCallId = "p$it", content = big, isError = false, toolName = "read_file")
        }
        cm.addUserMessage("anchor")
        repeat(10) {
            cm.addAssistantMessage(ChatMessage(role = "assistant", content = big))
            cm.addToolResult(toolCallId = "t$it", content = big, isError = false, toolName = "read_file")
        }
        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Error(ErrorType.SERVER_ERROR, "simulated failure")
        val result = cm.compact(brain, force = true, iterationsSinceLastUser = 10)

        assertTrue(result is ContextManager.CompactResult.Failed, "expected Failed, got $result")
        assertTrue((result as ContextManager.CompactResult.Failed).reason.contains("failed", ignoreCase = true),
            "Failed reason should mention summarization failure")
    }
}
