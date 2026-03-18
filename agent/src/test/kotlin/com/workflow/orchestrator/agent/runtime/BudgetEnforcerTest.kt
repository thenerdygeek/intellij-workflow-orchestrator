package com.workflow.orchestrator.agent.runtime

import com.workflow.orchestrator.agent.context.ContextManager
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BudgetEnforcerTest {

    @Test
    fun `returns OK when token usage is under compression threshold`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 50_000 // 33% of 150K

        val enforcer = BudgetEnforcer(contextManager, maxInputTokens = 150_000)
        assertEquals(BudgetEnforcer.BudgetStatus.OK, enforcer.check())
    }

    @Test
    fun `returns OK when token usage is just under 40 percent`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 59_999 // just under 40%

        val enforcer = BudgetEnforcer(contextManager, maxInputTokens = 150_000)
        assertEquals(BudgetEnforcer.BudgetStatus.OK, enforcer.check())
    }

    @Test
    fun `returns COMPRESS when token usage is at compression threshold`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 60_000 // exactly 40%

        val enforcer = BudgetEnforcer(contextManager, maxInputTokens = 150_000)
        assertEquals(BudgetEnforcer.BudgetStatus.COMPRESS, enforcer.check())
    }

    @Test
    fun `returns COMPRESS when between compression and critical thresholds`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 75_000 // 50% — between 40% and 60%

        val enforcer = BudgetEnforcer(contextManager, maxInputTokens = 150_000)
        assertEquals(BudgetEnforcer.BudgetStatus.COMPRESS, enforcer.check())
    }

    @Test
    fun `returns ESCALATE when token usage exceeds critical threshold`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 90_000 // 60% — at critical

        val enforcer = BudgetEnforcer(contextManager, maxInputTokens = 150_000)
        assertEquals(BudgetEnforcer.BudgetStatus.ESCALATE, enforcer.check())
    }

    @Test
    fun `returns ESCALATE when token usage is well over critical threshold`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 140_000 // 93%

        val enforcer = BudgetEnforcer(contextManager, maxInputTokens = 150_000)
        assertEquals(BudgetEnforcer.BudgetStatus.ESCALATE, enforcer.check())
    }

    @Test
    fun `returns OK when zero tokens used`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 0

        val enforcer = BudgetEnforcer(contextManager, maxInputTokens = 150_000)
        assertEquals(BudgetEnforcer.BudgetStatus.OK, enforcer.check())
    }

    @Test
    fun `utilizationPercent returns correct percentage`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 75_000

        val enforcer = BudgetEnforcer(contextManager, maxInputTokens = 150_000)
        assertEquals(50, enforcer.utilizationPercent())
    }

    @Test
    fun `works with custom maxInputTokens`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 5_000

        // With a small budget, 5000 tokens is over 60%
        val enforcer = BudgetEnforcer(contextManager, maxInputTokens = 8_000)
        assertEquals(BudgetEnforcer.BudgetStatus.ESCALATE, enforcer.check())
    }
}
