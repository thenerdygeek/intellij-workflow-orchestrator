package com.workflow.orchestrator.jira.service

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BranchNameValidatorTest {

    @Test
    fun `generates branch name from default pattern`() {
        val name = BranchNameValidator.generateBranchName(
            pattern = "feature/{ticketId}-{summary}",
            ticketId = "PROJ-123",
            summary = "Fix Login Page Redirect"
        )
        assertEquals("feature/PROJ-123-fix-login-page-redirect", name)
    }

    @Test
    fun `generates branch name from bugfix pattern`() {
        val name = BranchNameValidator.generateBranchName(
            pattern = "bugfix/{ticketId}-{summary}",
            ticketId = "PROJ-456",
            summary = "NPE on Startup"
        )
        assertEquals("bugfix/PROJ-456-npe-on-startup", name)
    }

    @Test
    fun `sanitizes special characters from summary`() {
        val name = BranchNameValidator.generateBranchName(
            pattern = "feature/{ticketId}-{summary}",
            ticketId = "PROJ-789",
            summary = "Add @Transactional & fix N+1 query!!"
        )
        assertEquals("feature/PROJ-789-add-transactional-fix-n-1-query", name)
    }

    @Test
    fun `truncates long summaries to max length`() {
        val name = BranchNameValidator.generateBranchName(
            pattern = "feature/{ticketId}-{summary}",
            ticketId = "PROJ-100",
            summary = "This is a very long summary that should be truncated to avoid extremely long branch names"
        )
        assertTrue(name.length <= 80)
        assertFalse(name.endsWith("-"))
    }

    @Test
    fun `isValidBranchName accepts standard patterns`() {
        assertTrue(BranchNameValidator.isValidBranchName("feature/PROJ-123-login-fix"))
        assertTrue(BranchNameValidator.isValidBranchName("bugfix/PROJ-456-crash"))
        assertFalse(BranchNameValidator.isValidBranchName("feature/no ticket id"))
        assertFalse(BranchNameValidator.isValidBranchName(""))
    }
}
