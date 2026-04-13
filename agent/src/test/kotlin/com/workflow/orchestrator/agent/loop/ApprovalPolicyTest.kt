package com.workflow.orchestrator.agent.loop

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class ApprovalPolicyTest {

    @Test
    fun `run_command requires approval but never allows session-wide`() {
        val policy = ApprovalPolicy.forTool("run_command")
        assertTrue(policy.requiresApproval)
        assertFalse(policy.allowSessionApproval)
    }

    @Test
    fun `edit_file allows session-wide approval`() {
        val policy = ApprovalPolicy.forTool("edit_file")
        assertTrue(policy.requiresApproval)
        assertTrue(policy.allowSessionApproval)
    }

    @Test
    fun `create_file allows session-wide approval`() {
        val policy = ApprovalPolicy.forTool("create_file")
        assertTrue(policy.requiresApproval)
        assertTrue(policy.allowSessionApproval)
    }

    @Test
    fun `revert_file allows session-wide approval`() {
        val policy = ApprovalPolicy.forTool("revert_file")
        assertTrue(policy.requiresApproval)
        assertTrue(policy.allowSessionApproval)
    }

    @Test
    fun `read_file does not require approval`() {
        val policy = ApprovalPolicy.forTool("read_file")
        assertFalse(policy.requiresApproval)
        assertFalse(policy.allowSessionApproval)
    }

    @Test
    fun `search_code does not require approval`() {
        val policy = ApprovalPolicy.forTool("search_code")
        assertFalse(policy.requiresApproval)
        assertFalse(policy.allowSessionApproval)
    }

    @Test
    fun `unknown tool does not require approval`() {
        val policy = ApprovalPolicy.forTool("some_unknown_tool")
        assertFalse(policy.requiresApproval)
        assertFalse(policy.allowSessionApproval)
    }

    @Test
    fun `APPROVAL_TOOLS contains all approval-required tools`() {
        assertEquals(
            setOf("run_command", "edit_file", "create_file", "revert_file"),
            ApprovalPolicy.APPROVAL_TOOLS
        )
    }

    @Test
    fun `every tool in APPROVAL_TOOLS has requiresApproval true`() {
        for (toolName in ApprovalPolicy.APPROVAL_TOOLS) {
            val policy = ApprovalPolicy.forTool(toolName)
            assertTrue(policy.requiresApproval, "$toolName should require approval")
        }
    }

    @Test
    fun `only run_command has allowSessionApproval false among approval tools`() {
        for (toolName in ApprovalPolicy.APPROVAL_TOOLS) {
            val policy = ApprovalPolicy.forTool(toolName)
            if (toolName == "run_command") {
                assertFalse(policy.allowSessionApproval, "run_command must not allow session approval")
            } else {
                assertTrue(policy.allowSessionApproval, "$toolName should allow session approval")
            }
        }
    }
}
