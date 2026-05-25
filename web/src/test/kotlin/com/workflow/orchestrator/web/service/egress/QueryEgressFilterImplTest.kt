package com.workflow.orchestrator.web.service.egress

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.web.QueryEgressFilter
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class QueryEgressFilterImplTest {

    private val noLlm: suspend (String) -> QueryEgressFilter.Decision = { error("LLM path not exercised in this test") }

    @Test
    fun `Safe verdict when no deny-list entry matches and LLM screener is disabled`() = runTest {
        val filter = QueryEgressFilterImpl(
            denyListSupplier = { emptySet() },
            llmScreenerEnabled = false,
            llmScreener = noLlm,
        )
        val decision = filter.screen(mockk<Project>(), "what is kotlin coroutines suspend")
        assertEquals(QueryEgressFilter.Decision.Safe("what is kotlin coroutines suspend"), decision)
    }

    @Test
    fun `Blocked verdict with first-3-char preservation when deny-list substring matches`() = runTest {
        val filter = QueryEgressFilterImpl(
            denyListSupplier = { setOf("acme.corp") },
            llmScreenerEnabled = false,
            llmScreener = noLlm,
        )
        val decision = filter.screen(mockk<Project>(), "Why does jenkins.acme.corp fail with X")
        assertTrue(decision is QueryEgressFilter.Decision.Blocked)
        decision as QueryEgressFilter.Decision.Blocked
        assertEquals("DENYLIST", decision.reason)
        // length >= 6 → first 3 chars preserved + ***
        assertEquals("acm***", decision.maskedTerm)
    }

    @Test
    fun `Blocked verdict fully masks term shorter than 6 chars`() = runTest {
        val filter = QueryEgressFilterImpl(
            denyListSupplier = { setOf("Foo42") },
            llmScreenerEnabled = false,
            llmScreener = noLlm,
        )
        val decision = filter.screen(mockk<Project>(), "Why does Foo42 fail")
        assertTrue(decision is QueryEgressFilter.Decision.Blocked)
        assertEquals("***", (decision as QueryEgressFilter.Decision.Blocked).maskedTerm)
    }

    @Test
    fun `deny-list match is case-insensitive`() = runTest {
        val filter = QueryEgressFilterImpl(
            denyListSupplier = { setOf("acme.corp") },
            llmScreenerEnabled = false,
            llmScreener = noLlm,
        )
        val decision = filter.screen(mockk<Project>(), "ACME.CORP outage")
        assertTrue(decision is QueryEgressFilter.Decision.Blocked)
    }

    @Test
    fun `regex deny-list entry uses re prefix and matches case-insensitively`() = runTest {
        val filter = QueryEgressFilterImpl(
            denyListSupplier = { setOf("re:Internal[A-Z]\\w+Service") },
            llmScreenerEnabled = false,
            llmScreener = noLlm,
        )
        val decision = filter.screen(mockk<Project>(), "what does InternalPaymentsService do")
        assertTrue(decision is QueryEgressFilter.Decision.Blocked)
        decision as QueryEgressFilter.Decision.Blocked
        assertEquals("DENYLIST", decision.reason)
        assertEquals("Int***", decision.maskedTerm)
    }

    @Test
    fun `malformed regex entry is skipped silently (does not crash)`() = runTest {
        val filter = QueryEgressFilterImpl(
            denyListSupplier = { setOf("re:[unclosed", "acme.corp") },
            llmScreenerEnabled = false,
            llmScreener = noLlm,
        )
        // Bad regex is ignored; good substring still matches.
        val decision = filter.screen(mockk<Project>(), "acme.corp outage")
        assertTrue(decision is QueryEgressFilter.Decision.Blocked)
    }

    @Test
    fun `LLM screener is NOT invoked when deny-list already blocked`() = runTest {
        var llmCalled = false
        val filter = QueryEgressFilterImpl(
            denyListSupplier = { setOf("acme.corp") },
            llmScreenerEnabled = true,
            llmScreener = { _ -> llmCalled = true; QueryEgressFilter.Decision.Safe("x") },
        )
        filter.screen(mockk<Project>(), "jenkins.acme.corp restart command")
        assertFalse(llmCalled, "deny-list block must short-circuit before LLM")
    }
}
