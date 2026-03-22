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
    fun `returns OK when token usage is just under 80 percent`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 119_999 // just under 80%

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 150_000)
        assertEquals(BudgetEnforcer.BudgetStatus.OK, enforcer.check())
    }

    @Test
    fun `returns COMPRESS when token usage is at compression threshold`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 120_000 // exactly 80%

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 150_000)
        assertEquals(BudgetEnforcer.BudgetStatus.COMPRESS, enforcer.check())
    }

    @Test
    fun `returns COMPRESS when between compression and nudge thresholds`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 125_000 // ~83% — between 80% and 88%

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 150_000)
        assertEquals(BudgetEnforcer.BudgetStatus.COMPRESS, enforcer.check())
    }

    @Test
    fun `returns NUDGE when token usage reaches nudge threshold`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 132_000 // 88% — at nudge threshold

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 150_000)
        assertEquals(BudgetEnforcer.BudgetStatus.NUDGE, enforcer.check())
    }

    @Test
    fun `returns NUDGE when between nudge and strong nudge thresholds`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 136_000 // ~91% — between 88% and 93%

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 150_000)
        assertEquals(BudgetEnforcer.BudgetStatus.NUDGE, enforcer.check())
    }

    @Test
    fun `returns STRONG_NUDGE when token usage reaches strong nudge threshold`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 139_500 // 93% — at strong nudge threshold

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 150_000)
        assertEquals(BudgetEnforcer.BudgetStatus.STRONG_NUDGE, enforcer.check())
    }

    @Test
    fun `returns STRONG_NUDGE when between strong nudge and terminate thresholds`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 143_000 // ~95% — between 93% and 97%

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 150_000)
        assertEquals(BudgetEnforcer.BudgetStatus.STRONG_NUDGE, enforcer.check())
    }

    @Test
    fun `returns TERMINATE when token usage reaches terminate threshold`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 145_500 // 97% — at terminate threshold

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

        // With a small budget of 8000, 6500 tokens is 81.25% → COMPRESS (between 80% and 88%)
        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 8_000)
        assertEquals(BudgetEnforcer.BudgetStatus.COMPRESS, enforcer.check())
    }

    @Test
    fun `effective budget accounts for reserved tokens correctly`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 50_000

        // With effectiveBudget=146000 (150K - 4K reserved), 50K = ~34% → OK
        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 146_000)
        assertEquals(BudgetEnforcer.BudgetStatus.OK, enforcer.check())

        // But with effectiveBudget=60_000, 50K = 83.3% → COMPRESS (between 80% and 88%)
        val smallEnforcer = BudgetEnforcer(contextManager, effectiveBudget = 60_000)
        assertEquals(BudgetEnforcer.BudgetStatus.COMPRESS, smallEnforcer.check())
    }

    // --- New tests with budget=10,000 for boundary precision ---

    @Test
    fun `small budget - OK just below compression threshold`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 7_999 // just under 80% of 10K

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 10_000)
        assertEquals(BudgetEnforcer.BudgetStatus.OK, enforcer.check())
    }

    @Test
    fun `small budget - COMPRESS at exactly 80 percent`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 8_000 // exactly 80% of 10K

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 10_000)
        assertEquals(BudgetEnforcer.BudgetStatus.COMPRESS, enforcer.check())
    }

    @Test
    fun `small budget - NUDGE at exactly 88 percent`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 8_800 // exactly 88% of 10K

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 10_000)
        assertEquals(BudgetEnforcer.BudgetStatus.NUDGE, enforcer.check())
    }

    @Test
    fun `small budget - STRONG_NUDGE at exactly 93 percent`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 9_300 // exactly 93% of 10K

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 10_000)
        assertEquals(BudgetEnforcer.BudgetStatus.STRONG_NUDGE, enforcer.check())
    }

    @Test
    fun `small budget - TERMINATE at exactly 97 percent`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 9_700 // exactly 97% of 10K

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 10_000)
        assertEquals(BudgetEnforcer.BudgetStatus.TERMINATE, enforcer.check())
    }
}
