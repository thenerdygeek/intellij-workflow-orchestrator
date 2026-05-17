package com.workflow.orchestrator.agent.loop

import com.workflow.orchestrator.core.ai.dto.ChatMessage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Disabled
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

    @Disabled("truncateConversation() removed in Phase 1 redesign — will be deleted in Phase 3")
    @Test
    fun `plan path survives compaction via re-injection`() {
        // TODO Phase 3: delete — truncateConversation(TruncationStrategy.HALF) removed in Phase 1
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
