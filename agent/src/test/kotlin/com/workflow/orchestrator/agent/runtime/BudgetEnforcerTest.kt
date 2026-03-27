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
    fun `returns COMPRESS when between compression and terminate thresholds`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 140_000 // ~93%

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 150_000)
        assertEquals(BudgetEnforcer.BudgetStatus.COMPRESS, enforcer.check())
    }

    @Test
    fun `returns COMPRESS at 90 percent`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 135_000 // 90%

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 150_000)
        assertEquals(BudgetEnforcer.BudgetStatus.COMPRESS, enforcer.check())
    }

    @Test
    fun `returns COMPRESS just under terminate threshold`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 145_499 // just under 97%

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 150_000)
        assertEquals(BudgetEnforcer.BudgetStatus.COMPRESS, enforcer.check())
    }

    @Test
    fun `returns TERMINATE when token usage reaches terminate threshold`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 145_500 // 97%

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

        // With a small budget of 8000, 6500 tokens is 81.25% → COMPRESS
        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 8_000)
        assertEquals(BudgetEnforcer.BudgetStatus.COMPRESS, enforcer.check())
    }

    @Test
    fun `effective budget accounts for reserved tokens correctly`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 50_000

        // With effectiveBudget=146000, 50K = ~34% → OK
        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 146_000)
        assertEquals(BudgetEnforcer.BudgetStatus.OK, enforcer.check())

        // But with effectiveBudget=60_000, 50K = 83.3% → COMPRESS
        val smallEnforcer = BudgetEnforcer(contextManager, effectiveBudget = 60_000)
        assertEquals(BudgetEnforcer.BudgetStatus.COMPRESS, smallEnforcer.check())
    }

    // --- Small budget boundary precision tests ---

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
    fun `small budget - COMPRESS at 90 percent`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 9_000 // 90% of 10K

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 10_000)
        assertEquals(BudgetEnforcer.BudgetStatus.COMPRESS, enforcer.check())
    }

    @Test
    fun `small budget - TERMINATE at exactly 97 percent`() {
        val contextManager = mockk<ContextManager>()
        every { contextManager.currentTokens } returns 9_700 // exactly 97% of 10K

        val enforcer = BudgetEnforcer(contextManager, effectiveBudget = 10_000)
        assertEquals(BudgetEnforcer.BudgetStatus.TERMINATE, enforcer.check())
    }

    @Test
    fun `only three budget statuses exist`() {
        assertEquals(3, BudgetEnforcer.BudgetStatus.entries.size)
        assertEquals(
            setOf("OK", "COMPRESS", "TERMINATE"),
            BudgetEnforcer.BudgetStatus.entries.map { it.name }.toSet()
        )
    }
}
