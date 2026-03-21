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

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 150_000)
        assertEquals(BudgetEnforcer.BudgetStatus.OK, enforcer.check())
    }

    @Test
    fun `returns OK when token usage is just under 60 percent`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 89_999 // just under 60%

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 150_000)
        assertEquals(BudgetEnforcer.BudgetStatus.OK, enforcer.check())
    }

    @Test
    fun `returns COMPRESS when token usage is at compression threshold`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 90_000 // exactly 60%

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 150_000)
        assertEquals(BudgetEnforcer.BudgetStatus.COMPRESS, enforcer.check())
    }

    @Test
    fun `returns COMPRESS when between compression and nudge thresholds`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 100_000 // ~67% — between 60% and 75%

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 150_000)
        assertEquals(BudgetEnforcer.BudgetStatus.COMPRESS, enforcer.check())
    }

    @Test
    fun `returns NUDGE when token usage reaches nudge threshold`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 112_500 // 75% — at nudge threshold

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 150_000)
        assertEquals(BudgetEnforcer.BudgetStatus.NUDGE, enforcer.check())
    }

    @Test
    fun `returns NUDGE when between nudge and strong nudge thresholds`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 120_000 // 80% — between 75% and 85%

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 150_000)
        assertEquals(BudgetEnforcer.BudgetStatus.NUDGE, enforcer.check())
    }

    @Test
    fun `returns STRONG_NUDGE when token usage reaches strong nudge threshold`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 127_500 // 85% — at strong nudge threshold

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 150_000)
        assertEquals(BudgetEnforcer.BudgetStatus.STRONG_NUDGE, enforcer.check())
    }

    @Test
    fun `returns STRONG_NUDGE when between strong nudge and terminate thresholds`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 135_000 // 90% — between 85% and 95%

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 150_000)
        assertEquals(BudgetEnforcer.BudgetStatus.STRONG_NUDGE, enforcer.check())
    }

    @Test
    fun `returns TERMINATE when token usage reaches terminate threshold`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 142_500 // 95% — at terminate threshold

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 150_000)
        assertEquals(BudgetEnforcer.BudgetStatus.TERMINATE, enforcer.check())
    }

    @Test
    fun `returns TERMINATE when token usage is well over terminate threshold`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 148_000 // ~99%

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 150_000)
        assertEquals(BudgetEnforcer.BudgetStatus.TERMINATE, enforcer.check())
    }

    @Test
    fun `returns OK when zero tokens used`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 0

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 150_000)
        assertEquals(BudgetEnforcer.BudgetStatus.OK, enforcer.check())
    }

    @Test
    fun `utilizationPercent returns correct percentage`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 75_000

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 150_000)
        assertEquals(50, enforcer.utilizationPercent())
    }

    @Test
    fun `works with custom effectiveBudget`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 6_500

        // With a small budget of 8000, 6500 tokens is 81.25% → NUDGE (between 75% and 85%)
        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 8_000)
        assertEquals(BudgetEnforcer.BudgetStatus.NUDGE, enforcer.check())
    }

    @Test
    fun `effective budget accounts for reserved tokens correctly`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 50_000

        // With effectiveBudget=146000 (150K - 4K reserved), 50K = ~34% → OK
        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 146_000)
        assertEquals(BudgetEnforcer.BudgetStatus.OK, enforcer.check())

        // But with effectiveBudget=80_000, 50K = 62.5% → COMPRESS (between 60% and 75%)
        val smallEnforcer = BudgetEnforcer(contextManager, effectiveBudget = 80_000)
        assertEquals(BudgetEnforcer.BudgetStatus.COMPRESS, smallEnforcer.check())
    }
}
