package com.workflow.orchestrator.web.service.egress

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.web.QueryEgressFilter
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class QueryEgressFilterImplTest {

    /** Passthrough LLM screener: returns Safe with the (possibly substituted) query verbatim. */
    private val passthroughLlm: suspend (String) -> QueryEgressFilter.Decision =
        { q -> QueryEgressFilter.Decision.Safe(q) }

    @Test
    fun `Safe verdict when no deny-list entry matches and LLM passes through`() = runTest {
        val filter = QueryEgressFilterImpl(
            denyListSupplier = { emptySet() },
            llmScreener = passthroughLlm,
        )
        val decision = filter.screen(mockk<Project>(), "what is kotlin coroutines suspend")
        assertEquals(QueryEgressFilter.Decision.Safe("what is kotlin coroutines suspend"), decision)
    }

    @Test
    fun `deny-list substring match substitutes redacted and does not block`() = runTest {
        val filter = QueryEgressFilterImpl(
            denyListSupplier = { setOf("acme.corp") },
            llmScreener = passthroughLlm,
        )
        val decision = filter.screen(mockk<Project>(), "Why does jenkins.acme.corp fail with X")
        // Substituted + LLM Safe → surfaced as Rewritten so the caller learns about it.
        assertTrue(decision is QueryEgressFilter.Decision.Rewritten)
        decision as QueryEgressFilter.Decision.Rewritten
        assertFalse(decision.query.contains("acme.corp"))
        assertTrue(decision.query.contains("[redacted]"))
        assertEquals("Why does jenkins.acme.corp fail with X", decision.original)
    }

    @Test
    fun `deny-list match is case-insensitive`() = runTest {
        val filter = QueryEgressFilterImpl(
            denyListSupplier = { setOf("acme.corp") },
            llmScreener = passthroughLlm,
        )
        val decision = filter.screen(mockk<Project>(), "ACME.CORP outage")
        assertTrue(decision is QueryEgressFilter.Decision.Rewritten)
        assertFalse((decision as QueryEgressFilter.Decision.Rewritten).query.contains("ACME.CORP", ignoreCase = true))
    }

    @Test
    fun `regex deny-list entry uses re prefix and matches case-insensitively`() = runTest {
        val filter = QueryEgressFilterImpl(
            denyListSupplier = { setOf("re:Internal[A-Z]\\w+Service") },
            llmScreener = passthroughLlm,
        )
        val decision = filter.screen(mockk<Project>(), "what does InternalPaymentsService do")
        assertTrue(decision is QueryEgressFilter.Decision.Rewritten)
        decision as QueryEgressFilter.Decision.Rewritten
        assertFalse(decision.query.contains("InternalPaymentsService"))
        assertTrue(decision.query.contains("[redacted]"))
    }

    @Test
    fun `malformed regex entry is skipped silently (does not crash) and good entry still substitutes`() = runTest {
        val filter = QueryEgressFilterImpl(
            denyListSupplier = { setOf("re:[unclosed", "acme.corp") },
            llmScreener = passthroughLlm,
        )
        // Bad regex is ignored; good substring still substitutes.
        val decision = filter.screen(mockk<Project>(), "acme.corp outage")
        assertTrue(decision is QueryEgressFilter.Decision.Rewritten)
        assertFalse((decision as QueryEgressFilter.Decision.Rewritten).query.contains("acme.corp"))
    }

    @Test
    fun `deny-list match substitutes a dummy and continues, never blocks`() = kotlinx.coroutines.test.runTest {
        val filter = QueryEgressFilterImpl(
            denyListSupplier = { setOf("acme.corp") },
            llmScreener = { q -> com.workflow.orchestrator.core.web.QueryEgressFilter.Decision.Safe(q) },
        )
        val d = filter.screen(io.mockk.mockk(relaxed = true), "why does jenkins.acme.corp fail")
        org.junit.jupiter.api.Assertions.assertTrue(
            d is com.workflow.orchestrator.core.web.QueryEgressFilter.Decision.Safe ||
            d is com.workflow.orchestrator.core.web.QueryEgressFilter.Decision.Rewritten
        )
        val sent = when (d) {
            is com.workflow.orchestrator.core.web.QueryEgressFilter.Decision.Safe -> d.query
            is com.workflow.orchestrator.core.web.QueryEgressFilter.Decision.Rewritten -> d.query
            else -> error("must not block on a deny-list match")
        }
        org.junit.jupiter.api.Assertions.assertFalse(sent.contains("acme.corp"))
    }

    @Test
    fun `LLM screener always runs after deny-list`() = kotlinx.coroutines.test.runTest {
        var called = false
        val filter = QueryEgressFilterImpl(
            denyListSupplier = { emptySet() },
            llmScreener = { q -> called = true; com.workflow.orchestrator.core.web.QueryEgressFilter.Decision.Safe(q) },
        )
        filter.screen(io.mockk.mockk(relaxed = true), "generic query")
        org.junit.jupiter.api.Assertions.assertTrue(called)
    }
}
