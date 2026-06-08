package com.workflow.orchestrator.agent.delegation

import com.workflow.orchestrator.agent.AgentService
import com.workflow.orchestrator.agent.loop.LoopResult
import com.workflow.orchestrator.core.delegation.DelegationMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class DelegatedResultMappingTest {
    @Test fun `Completed maps the full summary verbatim`() {
        val long = "X".repeat(5000)
        val r = AgentService.mapLoopResultToDelegationResult(LoopResult.Completed(summary = long, iterations = 3), 7)
        assertEquals(DelegationMessage.ResultStatus.COMPLETED, r.status)
        assertEquals(long, r.summary)
        assertEquals(7L, r.durationSeconds)
    }
    @Test fun `SessionHandoff maps the full context, not the first 200 chars`() {
        val long = "Y".repeat(3000)
        val r = AgentService.mapLoopResultToDelegationResult(LoopResult.SessionHandoff(context = long, iterations = 1), 3)
        assertEquals(long, r.summary)
    }

    // --- delivery-failure mapping (Phase 3 cut G, incision 2) ---

    @Test fun `CancellationException maps to CANCELED with the exception message as reason`() {
        val r = AgentService.delegationResultForDeliveryFailure(
            kotlinx.coroutines.CancellationException("user aborted"),
            12,
        )
        assertEquals(DelegationMessage.ResultStatus.CANCELED, r.status)
        assertEquals("user aborted", r.reason)
        assertEquals(12L, r.durationSeconds)
    }

    @Test fun `generic exception maps to FAILED with the exception message as reason`() {
        val r = AgentService.delegationResultForDeliveryFailure(RuntimeException("boom"), 4)
        assertEquals(DelegationMessage.ResultStatus.FAILED, r.status)
        assertEquals("boom", r.reason)
        assertEquals(4L, r.durationSeconds)
    }

    @Test fun `FAILED falls back to the qualified class name when the message is null`() {
        val r = AgentService.delegationResultForDeliveryFailure(IllegalStateException(), 0)
        assertEquals(DelegationMessage.ResultStatus.FAILED, r.status)
        assertEquals(IllegalStateException::class.qualifiedName, r.reason)
    }
}
