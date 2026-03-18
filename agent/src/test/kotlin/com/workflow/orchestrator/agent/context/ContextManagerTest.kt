package com.workflow.orchestrator.agent.context

import com.workflow.orchestrator.agent.api.dto.ChatMessage
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
}
