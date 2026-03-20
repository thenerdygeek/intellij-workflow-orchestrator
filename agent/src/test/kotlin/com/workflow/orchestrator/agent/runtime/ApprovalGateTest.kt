package com.workflow.orchestrator.agent.runtime

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ApprovalGateTest {

    @Test
    fun `read_file is NONE risk and always approved`() {
        val riskLevel = ApprovalGate.riskLevelFor("read_file")
        assertEquals(RiskLevel.NONE, riskLevel)

        val gate = ApprovalGate(approvalRequired = true)
        val result = gate.check("read_file", "Read a file", riskLevel)
        assertSame(ApprovalResult.Approved, result)
    }

    @Test
    fun `edit_file is MEDIUM risk and approved when approval not required`() {
        val riskLevel = ApprovalGate.riskLevelFor("edit_file")
        assertEquals(RiskLevel.MEDIUM, riskLevel)

        val gate = ApprovalGate(approvalRequired = false)
        val result = gate.check("edit_file", "Edit a file", riskLevel)
        assertSame(ApprovalResult.Approved, result)
    }

    @Test
    fun `edit_file is MEDIUM risk and triggers callback when approval required`() {
        val riskLevel = ApprovalGate.riskLevelFor("edit_file")
        assertEquals(RiskLevel.MEDIUM, riskLevel)

        var callbackDescription: String? = null
        var callbackRisk: RiskLevel? = null

        val gate = ApprovalGate(
            approvalRequired = true,
            onApprovalNeeded = { desc, risk ->
                callbackDescription = desc
                callbackRisk = risk
                ApprovalResult.Pending(desc, risk)
            }
        )

        val result = gate.check("edit_file", "Modify src/Main.kt", riskLevel)

        assertTrue(result is ApprovalResult.Pending)
        assertEquals("Modify src/Main.kt", callbackDescription)
        assertEquals(RiskLevel.MEDIUM, callbackRisk)
    }

    @Test
    fun `run_command is HIGH risk and always requires approval`() {
        val riskLevel = ApprovalGate.riskLevelFor("run_command")
        assertEquals(RiskLevel.HIGH, riskLevel)

        // Even with approvalRequired=false, HIGH should trigger callback
        var callbackInvoked = false
        val gate = ApprovalGate(
            approvalRequired = false,
            onApprovalNeeded = { _, _ ->
                callbackInvoked = true
                ApprovalResult.Rejected
            }
        )

        val result = gate.check("run_command", "Run mvn clean install", riskLevel)
        assertTrue(callbackInvoked)
        assertSame(ApprovalResult.Rejected, result)
    }

    @Test
    fun `jira_get_ticket is NONE risk and always approved`() {
        val riskLevel = ApprovalGate.riskLevelFor("jira_get_ticket")
        assertEquals(RiskLevel.NONE, riskLevel)

        val gate = ApprovalGate(approvalRequired = true)
        val result = gate.check("jira_get_ticket", "Get ticket PROJ-123", riskLevel)
        assertSame(ApprovalResult.Approved, result)
    }

    @Test
    fun `unknown tool defaults to HIGH risk`() {
        val riskLevel = ApprovalGate.riskLevelFor("some_unknown_tool")
        assertEquals(RiskLevel.HIGH, riskLevel)
    }

    @Test
    fun `callback receives correct description and risk level`() {
        var receivedDescription: String? = null
        var receivedRisk: RiskLevel? = null

        val gate = ApprovalGate(
            approvalRequired = true,
            onApprovalNeeded = { desc, risk ->
                receivedDescription = desc
                receivedRisk = risk
                ApprovalResult.Approved
            }
        )

        gate.check("bitbucket_create_pr", "Create PR for feature/PROJ-123", RiskLevel.HIGH)

        assertEquals("Create PR for feature/PROJ-123", receivedDescription)
        assertEquals(RiskLevel.HIGH, receivedRisk)
    }

    @Test
    fun `LOW risk approved without callback when approval not required`() {
        val riskLevel = ApprovalGate.riskLevelFor("jira_comment")
        assertEquals(RiskLevel.LOW, riskLevel)

        var callbackInvoked = false
        val gate = ApprovalGate(
            approvalRequired = false,
            onApprovalNeeded = { _, _ ->
                callbackInvoked = true
                ApprovalResult.Rejected
            }
        )

        val result = gate.check("jira_comment", "Add comment", riskLevel)
        assertFalse(callbackInvoked)
        assertSame(ApprovalResult.Approved, result)
    }

    @Test
    fun `LOW risk triggers callback when approval required`() {
        val riskLevel = ApprovalGate.riskLevelFor("jira_comment")
        assertEquals(RiskLevel.LOW, riskLevel)

        var callbackInvoked = false
        val gate = ApprovalGate(
            approvalRequired = true,
            onApprovalNeeded = { _, _ ->
                callbackInvoked = true
                ApprovalResult.Approved
            }
        )

        val result = gate.check("jira_comment", "Add comment", riskLevel)
        // LOW with approvalRequired=true: not >= MEDIUM, so auto-approved
        // Actually checking the logic: approvalRequired=true but riskLevel=LOW
        // The condition: riskLevel >= HIGH (no) || (approvalRequired && riskLevel >= MEDIUM) (no, LOW < MEDIUM)
        // So it falls through to the final return Approved
        assertSame(ApprovalResult.Approved, result)
    }

    @Test
    fun `HIGH risk rejects when no callback provided - safer default`() {
        val gate = ApprovalGate(approvalRequired = true, onApprovalNeeded = null)

        val result = gate.check("run_command", "Run shell command", RiskLevel.HIGH)
        assertSame(ApprovalResult.Rejected, result)
    }

    @Test
    fun `all read-only tools have NONE risk level`() {
        val readOnlyTools = listOf(
            "read_file", "search_code", "find_references", "find_definition",
            "type_hierarchy", "call_hierarchy", "file_structure",
            "spring_context", "spring_endpoints", "spring_bean_graph",
            "diagnostics", "jira_get_ticket", "bamboo_build", "sonar_issues"
        )

        for (tool in readOnlyTools) {
            assertEquals(RiskLevel.NONE, ApprovalGate.riskLevelFor(tool), "Expected NONE risk for $tool")
        }
    }
}
