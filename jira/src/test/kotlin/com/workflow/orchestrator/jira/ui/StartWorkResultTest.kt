package com.workflow.orchestrator.jira.ui

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class StartWorkResultTest {

    @Test
    fun `default activateOnly is false — preserves existing call sites`() {
        val r = StartWorkResult(sourceBranch = "main", branchName = "feature/ABC-123")
        assertFalse(r.activateOnly, "default must be false so legacy callers behave unchanged")
        assertFalse(r.useExisting)
        assertEquals(0, r.selectedRepoIndex)
    }

    @Test
    fun `activateOnly=true is constructible without branch fields`() {
        val r = StartWorkResult(sourceBranch = "", branchName = "", activateOnly = true)
        assertTrue(r.activateOnly)
        assertEquals("", r.branchName)
    }
}
