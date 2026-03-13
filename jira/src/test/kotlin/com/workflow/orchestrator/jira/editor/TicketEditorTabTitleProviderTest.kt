package com.workflow.orchestrator.jira.editor

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class TicketEditorTabTitleProviderTest {

    @Test
    fun `generates tab title with ticket suffix when active`() {
        val title = TicketTabTitleHelper.generateTitle("UserService.kt", "PROJ-123")
        assertEquals("UserService.kt [PROJ-123]", title)
    }

    @Test
    fun `returns null when no active ticket`() {
        val title = TicketTabTitleHelper.generateTitle("UserService.kt", "")
        assertNull(title)
    }

    @Test
    fun `returns null when ticket is blank`() {
        val title = TicketTabTitleHelper.generateTitle("UserService.kt", "  ")
        assertNull(title)
    }
}
