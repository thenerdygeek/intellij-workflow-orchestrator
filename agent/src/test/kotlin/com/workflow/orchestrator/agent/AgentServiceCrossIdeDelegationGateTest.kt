package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Source-text pins for Plan 1 Task 11 — outbound-delegation gate.
 *
 * These tests verify that:
 * 1. The single `delegation` meta-tool registration is placed AFTER the
 *    `enableOutboundCrossIdeDelegation` gate check, so the LLM does not
 *    see the tool when the setting is off (§3.3).
 * 2. The `reregisterCrossIdeDelegationTools` helper exists for runtime
 *    toggle support (added/removed without an IDE restart).
 */
class AgentServiceCrossIdeDelegationGateTest {

    private val source: String = Files.readString(
        Path.of("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt")
    )

    @Test
    fun `delegation tool is gated on enableOutboundCrossIdeDelegation`() {
        assertTrue(source.contains("enableOutboundCrossIdeDelegation"),
            "AgentService must reference enableOutboundCrossIdeDelegation")

        // Find the gate check inside reregisterCrossIdeDelegationTools.
        val gateIdx = source.indexOf("enableOutboundCrossIdeDelegation")
        assertTrue(gateIdx >= 0, "gate check not found in AgentService")

        // The DelegationTool class name must appear AFTER the gate check (i.e. inside
        // the if-block in reregisterCrossIdeDelegationTools).
        val toolIdxAfterGate = source.indexOf("DelegationTool", gateIdx)
        assertTrue(toolIdxAfterGate > gateIdx,
            "DelegationTool registration must be after the enableOutboundCrossIdeDelegation gate check")
    }

    @Test
    fun `reregisterCrossIdeDelegationTools is defined`() {
        assertTrue(source.contains("fun reregisterCrossIdeDelegationTools"),
            "AgentService must define reregisterCrossIdeDelegationTools()")
    }

    @Test
    fun `outbound settings change listener is subscribed in init`() {
        assertTrue(source.contains("outboundSettingChanged"),
            "AgentService init must subscribe to outboundSettingChanged for runtime toggle support")
    }
}
