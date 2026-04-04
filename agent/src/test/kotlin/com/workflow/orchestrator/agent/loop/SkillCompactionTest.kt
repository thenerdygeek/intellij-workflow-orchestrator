package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SkillCompactionTest {

    private lateinit var contextManager: ContextManager

    @BeforeEach
    fun setUp() {
        contextManager = ContextManager(maxInputTokens = 100_000)
    }

    @Test
    fun `setActiveSkill stores skill content`() {
        contextManager.setActiveSkill("# TDD Skill\nWrite tests first.")

        assertEquals("# TDD Skill\nWrite tests first.", contextManager.getActiveSkill())
    }

    @Test
    fun `getActiveSkill returns null when no skill active`() {
        assertNull(contextManager.getActiveSkill())
    }

    @Test
    fun `clearActiveSkill removes stored skill`() {
        contextManager.setActiveSkill("Some skill content")
        contextManager.clearActiveSkill()

        assertNull(contextManager.getActiveSkill())
    }

    @Test
    fun `active skill survives compaction via re-injection`() {
        contextManager.setSystemPrompt("System prompt")

        // Set an active skill
        contextManager.setActiveSkill("# Brainstorm Skill\nExplore ideas first.")

        // Add enough messages to simulate a conversation that gets truncated
        for (i in 1..20) {
            contextManager.addUserMessage("User message $i with some content to take up space")
            contextManager.addAssistantMessage(
                ChatMessage(role = "assistant", content = "Response $i with details")
            )
        }

        // Simulate truncation (Stage 2 of compaction)
        contextManager.truncateConversation(TruncationStrategy.HALF)

        // Re-inject active skill (called at end of compact())
        contextManager.reInjectActiveSkill()

        // Verify the skill content was re-injected
        val messages = contextManager.getMessages()
        val hasSkillMessage = messages.any { msg ->
            msg.content?.contains("[Active Skill]") == true &&
            msg.content?.contains("Brainstorm Skill") == true
        }
        assertTrue(hasSkillMessage, "Active skill should be re-injected after compaction")
    }

    @Test
    fun `re-injection does not duplicate when skill already present`() {
        contextManager.setSystemPrompt("System prompt")
        contextManager.setActiveSkill("# My Skill")

        // Add a few messages
        contextManager.addUserMessage("Hello")
        contextManager.addAssistantMessage(ChatMessage(role = "assistant", content = "Hi"))

        // First re-injection
        contextManager.reInjectActiveSkill()

        val countAfterFirst = contextManager.getMessages().count {
            it.content?.contains("[Active Skill]") == true
        }

        // Second re-injection should not add another
        contextManager.reInjectActiveSkill()

        val countAfterSecond = contextManager.getMessages().count {
            it.content?.contains("[Active Skill]") == true
        }

        assertEquals(countAfterFirst, countAfterSecond,
            "Re-injection should not duplicate when skill message already in recent messages")
    }

    @Test
    fun `no re-injection when no active skill`() {
        contextManager.setSystemPrompt("System prompt")
        contextManager.addUserMessage("Hello")
        contextManager.addAssistantMessage(ChatMessage(role = "assistant", content = "Hi"))

        val sizeBefore = contextManager.getMessages().size
        contextManager.reInjectActiveSkill()
        val sizeAfter = contextManager.getMessages().size

        assertEquals(sizeBefore, sizeAfter, "No messages should be added when no active skill")
    }
}
