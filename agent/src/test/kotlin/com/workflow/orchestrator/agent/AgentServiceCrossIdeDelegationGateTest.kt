package com.workflow.orchestrator.agent

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * Source-text pins for Plan 1 Task 11 — outbound-delegation gate.
 *
 * These tests verify that:
 * 1. The delegation tool registrations are placed AFTER the
 *    `enableOutboundCrossIdeDelegation` gate check, so the LLM does not
 *    see the tools when the setting is off (§3.3).
 * 2. The `reregisterCrossIdeDelegationTools` helper exists for runtime
 *    toggle support (added/removed without an IDE restart).
 */
class AgentServiceCrossIdeDelegationGateTest {

    private val source: String = Files.readString(
        Path.of("src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt")
    )

    @Test
    fun `delegation tools are gated on enableOutboundCrossIdeDelegation`() {
        assertTrue(source.contains("enableOutboundCrossIdeDelegation"),
            "AgentService must reference enableOutboundCrossIdeDelegation")

        // Find the gate check inside registerAllTools (first occurrence of the flag).
        val gateIdx = source.indexOf("enableOutboundCrossIdeDelegation")
        assertTrue(gateIdx >= 0, "gate check not found in AgentService")

        // Both tool class names must appear AFTER the gate check (i.e. inside the if-block
        // or in reregisterCrossIdeDelegationTools). We look for the first occurrence of each
        // that comes after gateIdx.
        val sendIdxAfterGate = source.indexOf("DelegationSendTool", gateIdx)
        val closeIdxAfterGate = source.indexOf("DelegationCloseTool", gateIdx)

        assertTrue(sendIdxAfterGate > gateIdx,
            "DelegationSendTool registration must be after the enableOutboundCrossIdeDelegation gate check")
        assertTrue(closeIdxAfterGate > gateIdx,
            "DelegationCloseTool registration must be after the enableOutboundCrossIdeDelegation gate check")
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
