package com.workflow.orchestrator.jira.service

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ActiveTicketServiceTest {

    @Test
    fun `initially has no active ticket`() {
        val service = ActiveTicketService()
        assertNull(service.activeTicketId)
    }

    @Test
    fun `setActiveTicket updates state`() {
        val service = ActiveTicketService()
        service.setActiveTicket("PROJ-123", "Fix login")
        assertEquals("PROJ-123", service.activeTicketId)
        assertEquals("Fix login", service.activeTicketSummary)
    }

    @Test
    fun `clearActiveTicket resets state`() {
        val service = ActiveTicketService()
        service.setActiveTicket("PROJ-123", "Fix login")
        service.clearActiveTicket()
        assertNull(service.activeTicketId)
        assertNull(service.activeTicketSummary)
    }

    @Test
    fun `activeTicketFlow emits updates`() = runTest {
        val service = ActiveTicketService()
        service.setActiveTicket("PROJ-456", "Update API")
        val state = service.activeTicketFlow.first()
        assertEquals("PROJ-456", state?.ticketId)
    }

    @Test
    fun `extractTicketIdFromBranch parses standard branch names`() {
        assertEquals("PROJ-123", ActiveTicketService.extractTicketIdFromBranch("feature/PROJ-123-login-fix"))
        assertEquals("PROJ-456", ActiveTicketService.extractTicketIdFromBranch("bugfix/PROJ-456-crash"))
        assertEquals("ABC-1", ActiveTicketService.extractTicketIdFromBranch("ABC-1-quick-fix"))
        assertNull(ActiveTicketService.extractTicketIdFromBranch("main"))
        assertNull(ActiveTicketService.extractTicketIdFromBranch("develop"))
    }
}
