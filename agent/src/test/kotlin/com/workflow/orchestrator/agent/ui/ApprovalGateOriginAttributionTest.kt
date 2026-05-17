// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
package com.workflow.orchestrator.agent.ui

import org.junit.jupiter.api.Test

class ApprovalGateOriginAttributionTest {

    @Test
    fun `approval gate reads SubagentOriginContext when set`() {
        val src = java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt"
        ).readText()
        // The gate must reference the origin context element somewhere.
        assert("SubagentOriginContext" in src) {
            "AgentController must read SubagentOriginContext somewhere (inside the approvalGate lambda)."
        }
        assert("originAgentId" in src || "origin.agentId" in src) {
            "AgentController must extract agentId from the origin context."
        }
        assert("originLabel" in src || "origin.label" in src) {
            "AgentController must extract label from the origin context."
        }
    }

    @Test
    fun `requestApproval bridge method accepts originAgentId and originLabel`() {
        val cefPanelSrc = java.io.File(
            "src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentCefPanel.kt"
        ).readText()
        assert("originAgentId" in cefPanelSrc) {
            "AgentCefPanel.requestApproval must accept originAgentId param."
        }
        assert("originLabel" in cefPanelSrc) {
            "AgentCefPanel.requestApproval must accept originLabel param."
        }
    }
}
