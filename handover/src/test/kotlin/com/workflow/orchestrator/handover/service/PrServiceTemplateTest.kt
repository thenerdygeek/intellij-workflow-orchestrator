package com.workflow.orchestrator.handover.service

import com.workflow.orchestrator.core.bitbucket.PrTitleRenderer
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class PrServiceTemplateTest {
    @Test
    fun `default format renders correctly`() {
        assertEquals("PROJ-123: Fix login bug", PrTitleRenderer.render("{ticketId}: {summary}", "PROJ-123", "Fix login bug", "feature/PROJ-123", 120))
    }

    @Test
    fun `custom format with brackets`() {
        assertEquals("[PROJ-123] Fix login bug", PrTitleRenderer.render("[{ticketId}] {summary}", "PROJ-123", "Fix login bug", "feature/PROJ-123", 120))
    }

    @Test
    fun `truncates to max length`() {
        val title = PrTitleRenderer.render("{ticketId}: {summary}", "PROJ-123", "A".repeat(200), "branch", 50)
        assertTrue(title.length <= 50)
    }

    @Test
    fun `branch variable substitution`() {
        assertEquals("PROJ-123 (feature/PROJ-123-fix)", PrTitleRenderer.render("{ticketId} ({branch})", "PROJ-123", "Fix", "feature/PROJ-123-fix", 120))
    }
}
