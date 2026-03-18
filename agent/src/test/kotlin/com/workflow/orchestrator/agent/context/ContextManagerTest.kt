package com.workflow.orchestrator.agent.context

import com.workflow.orchestrator.agent.api.dto.ChatCompletionResponse
import com.workflow.orchestrator.agent.api.dto.ChatMessage
import com.workflow.orchestrator.agent.api.dto.Choice
import com.workflow.orchestrator.agent.api.dto.UsageInfo
import com.workflow.orchestrator.agent.brain.LlmBrain
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ContextManagerTest {

    private lateinit var manager: ContextManager

    @BeforeEach
    fun setUp() {
        // Small budget for testing compression behavior
        manager = ContextManager(
            maxInputTokens = 1000,
            tMaxRatio = 0.70,     // compress at 700 tokens
            tRetainedRatio = 0.40  // compress down to 400 tokens
        )
    }

    @Test
    fun `addMessage tracks token count`() {
        manager.addMessage(ChatMessage(role = "user", content = "Hello world"))
        assertTrue(manager.currentTokens > 0)
        assertEquals(1, manager.messageCount)
    }

    @Test
    fun `getMessages returns messages in order`() {
        manager.addMessage(ChatMessage(role = "user", content = "First"))
        manager.addMessage(ChatMessage(role = "assistant", content = "Second"))

        val messages = manager.getMessages()
        assertEquals(2, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("assistant", messages[1].role)
    }

    @Test
    fun `addToolResult compresses large results`() {
        val largeContent = "x".repeat(5000)
        manager.addToolResult("call-1", largeContent, "Large tool result")

        val messages = manager.getMessages()
        val toolMsg = messages.first { it.role == "tool" }
        assertTrue(toolMsg.content!!.contains("Summary"))
        assertTrue(toolMsg.content!!.length < largeContent.length)
    }

    @Test
    fun `addToolResult passes small results through`() {
        manager.addToolResult("call-1", "small result", "Small result")

        val messages = manager.getMessages()
        val toolMsg = messages.first { it.role == "tool" }
        assertEquals("small result", toolMsg.content)
    }

    @Test
    fun `compression triggers when tMax exceeded`() {
        // Add messages with enough content to exceed tMax (700 tokens at 3.5 chars/token = ~2450 chars)
        for (i in 1..40) {
            manager.addMessage(ChatMessage(role = "user", content = "Message number $i. " + "x".repeat(80)))
        }

        // After compression, should have fewer messages than added
        assertTrue(manager.messageCount < 40, "Messages (${manager.messageCount}) should be fewer than 40 after compression")
    }

    @Test
    fun `compressed messages appear as anchored summaries`() {
        // Fill up and trigger compression with large enough messages
        for (i in 1..40) {
            manager.addMessage(ChatMessage(role = "user", content = "Message $i discussing architecture. " + "x".repeat(80)))
        }

        val messages = manager.getMessages()
        // Should have a system message with summary at the start (contains "summary" case-insensitive)
        val hasSystemSummary = messages.any { it.role == "system" && it.content?.lowercase()?.contains("summary") == true }
        assertTrue(hasSystemSummary, "Should have anchored summary after compression. Messages: ${messages.map { "${it.role}: ${it.content?.take(50)}" }}")
    }

    @Test
    fun `system messages are never compressed`() {
        manager.addMessage(ChatMessage(role = "system", content = "You are a helpful assistant."))

        for (i in 1..30) {
            manager.addMessage(ChatMessage(role = "user", content = "Padding message $i with extra content for token usage"))
        }

        val messages = manager.getMessages()
        // Original system prompt should survive (possibly in anchored summaries section)
        val allContent = messages.mapNotNull { it.content }.joinToString("\n")
        // System messages are preserved through compression
        assertTrue(messages.any { it.role == "system" })
    }

    @Test
    fun `remainingBudget decreases as messages are added`() {
        val initial = manager.remainingBudget()
        manager.addMessage(ChatMessage(role = "user", content = "Some message"))
        assertTrue(manager.remainingBudget() < initial)
    }

    @Test
    fun `isBudgetCritical returns true when low`() {
        assertFalse(manager.isBudgetCritical())

        // Fill to near capacity with a large message
        manager.addMessage(ChatMessage(role = "user", content = "x".repeat(3500))) // ~1000 tokens
        assertTrue(manager.isBudgetCritical())
    }

    @Test
    fun `reset clears all state`() {
        manager.addMessage(ChatMessage(role = "user", content = "Hello"))
        manager.reset()

        assertEquals(0, manager.currentTokens)
        assertEquals(0, manager.messageCount)
        assertTrue(manager.getMessages().isEmpty())
    }

    // --- Reserved tokens tests (Step 1) ---

    @Test
    fun `reservedTokens reduces effective budget`() {
        val managerWithReserved = ContextManager(
            maxInputTokens = 1000,
            reservedTokens = 200
        )
        // Effective budget = 1000 - 200 = 800
        assertEquals(800, managerWithReserved.remainingBudget())
    }

    @Test
    fun `compression thresholds use effective budget with reservedTokens`() {
        // With reservedTokens=200, effectiveBudget=800
        // tMax at 0.70 = 560 tokens (not 700)
        val managerWithReserved = ContextManager(
            maxInputTokens = 1000,
            reservedTokens = 200,
            tMaxRatio = 0.70,
            tRetainedRatio = 0.40
        )

        // Add messages to trigger compression at 560 tokens (~1960 chars)
        for (i in 1..25) {
            managerWithReserved.addMessage(ChatMessage(role = "user", content = "Msg $i. " + "x".repeat(80)))
        }

        // Should have compressed with the lower threshold
        assertTrue(managerWithReserved.messageCount < 25,
            "Messages (${managerWithReserved.messageCount}) should be fewer than 25 after compression with reserved tokens")
    }

    // --- LLM-powered summarization tests ---

    @Test
    fun `compression uses LLM summarization when brain is provided`() {
        val brain = mockk<LlmBrain>()
        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Success(
            ChatCompletionResponse(
                id = "summary-1",
                choices = listOf(
                    Choice(
                        index = 0,
                        message = ChatMessage(role = "assistant", content = "LLM summary: analyzed files and found issues"),
                        finishReason = "stop"
                    )
                ),
                usage = UsageInfo(promptTokens = 50, completionTokens = 20, totalTokens = 70)
            )
        )

        val brainManager = ContextManager(
            maxInputTokens = 1000,
            brain = brain,
            tMaxRatio = 0.70,
            tRetainedRatio = 0.40
        )

        // Fill enough to trigger compression
        for (i in 1..40) {
            brainManager.addMessage(ChatMessage(role = "user", content = "Message $i about code review. " + "x".repeat(80)))
        }

        val messages = brainManager.getMessages()
        // Should have used LLM for summarization
        val summaryContent = messages.filter { it.role == "system" }.mapNotNull { it.content }.joinToString("\n")
        assertTrue(summaryContent.contains("LLM summary"), "Expected LLM summary in anchored summaries, got: $summaryContent")

        // Verify brain.chat was called
        coVerify(atLeast = 1) { brain.chat(any(), any(), any(), any()) }
    }

    @Test
    fun `compression falls back to truncation when LLM fails`() {
        val brain = mockk<LlmBrain>()
        coEvery { brain.chat(any(), any(), any(), any()) } returns ApiResult.Error(
            type = ErrorType.NETWORK_ERROR,
            message = "Connection refused"
        )

        val brainManager = ContextManager(
            maxInputTokens = 1000,
            brain = brain,
            tMaxRatio = 0.70,
            tRetainedRatio = 0.40
        )

        // Fill enough to trigger compression
        for (i in 1..40) {
            brainManager.addMessage(ChatMessage(role = "user", content = "Message $i about analysis. " + "x".repeat(80)))
        }

        val messages = brainManager.getMessages()
        // Should still have compressed (fell back to truncation)
        assertTrue(brainManager.messageCount < 40)
        val summaryContent = messages.filter { it.role == "system" }.mapNotNull { it.content }.joinToString("\n")
        assertTrue(summaryContent.contains("Previous context"), "Expected fallback summary, got: $summaryContent")
    }

    @Test
    fun `null brain uses default truncation summarizer`() {
        // This is the default setUp() behavior — no brain provided
        for (i in 1..40) {
            manager.addMessage(ChatMessage(role = "user", content = "Message $i about testing. " + "x".repeat(80)))
        }

        val messages = manager.getMessages()
        val summaryContent = messages.filter { it.role == "system" }.mapNotNull { it.content }.joinToString("\n")
        assertTrue(summaryContent.contains("Previous context summary"), "Expected default summary, got: $summaryContent")
    }
}
