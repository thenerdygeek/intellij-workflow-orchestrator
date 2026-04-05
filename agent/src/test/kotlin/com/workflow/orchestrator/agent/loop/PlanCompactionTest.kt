package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class PlanCompactionTest {

    private lateinit var contextManager: ContextManager

    @BeforeEach
    fun setUp() {
        contextManager = ContextManager(maxInputTokens = 100_000)
    }

    @Test
    fun `setActivePlanPath stores path`() {
        contextManager.setActivePlanPath("/tmp/session/plan.md")
        assertEquals("/tmp/session/plan.md", contextManager.getActivePlanPath())
    }

    @Test
    fun `getActivePlanPath returns null when no plan`() {
        assertNull(contextManager.getActivePlanPath())
    }

    @Test
    fun `clearActivePlanPath removes stored path`() {
        contextManager.setActivePlanPath("/tmp/plan.md")
        contextManager.clearActivePlanPath()
        assertNull(contextManager.getActivePlanPath())
    }

    @Test
    fun `plan path survives compaction via re-injection`() {
        contextManager.setSystemPrompt("System prompt")
        contextManager.setActivePlanPath("/tmp/session/plan.md")

        for (i in 1..20) {
            contextManager.addUserMessage("User message $i")
            contextManager.addAssistantMessage(
                ChatMessage(role = "assistant", content = "Response $i")
            )
        }

        contextManager.truncateConversation(TruncationStrategy.HALF)
        contextManager.reInjectActivePlan()

        val messages = contextManager.getMessages()
        val hasPlanMessage = messages.any { msg ->
            msg.content?.contains("[Active Plan]") == true &&
            msg.content?.contains("/tmp/session/plan.md") == true
        }
        assertTrue(hasPlanMessage, "Plan path should be re-injected after compaction")
    }

    @Test
    fun `re-injection does not duplicate when plan already present`() {
        contextManager.setSystemPrompt("System prompt")
        contextManager.setActivePlanPath("/tmp/plan.md")
        contextManager.addUserMessage("Hello")

        contextManager.reInjectActivePlan()
        val countAfterFirst = contextManager.getMessages().count {
            it.content?.contains("[Active Plan]") == true
        }

        contextManager.reInjectActivePlan()
        val countAfterSecond = contextManager.getMessages().count {
            it.content?.contains("[Active Plan]") == true
        }

        assertEquals(countAfterFirst, countAfterSecond)
    }

    @Test
    fun `no re-injection when no active plan`() {
        contextManager.setSystemPrompt("System prompt")
        contextManager.addUserMessage("Hello")

        val sizeBefore = contextManager.getMessages().size
        contextManager.reInjectActivePlan()
        assertEquals(sizeBefore, contextManager.getMessages().size)
    }
}
