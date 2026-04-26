package com.workflow.orchestrator.core.workflow

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.settings.PluginSettings
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class WorkflowContextServiceBootTest {

    @Test fun `boot with persisted ticket — state activeTicket hydrated synchronously`() {
        val project = mockk<Project>(relaxed = true)
        val settings = mockk<PluginSettings>(relaxed = true)
        every { settings.state.activeTicketId } returns "AFTER8TE-912"
        every { settings.state.activeTicketSummary } returns "Fix login"
        every { project.getService(PluginSettings::class.java) } returns settings

        val service = WorkflowContextService(project, TestScope())
        val ticket = service.state.value.activeTicket
        assertNotNull(ticket)
        assertEquals("AFTER8TE-912", ticket!!.key)
        assertEquals("Fix login", ticket.summary)
    }

    @Test fun `boot with no persisted ticket — state activeTicket is null`() {
        val project = mockk<Project>(relaxed = true)
        val settings = mockk<PluginSettings>(relaxed = true)
        every { settings.state.activeTicketId } returns null
        every { settings.state.activeTicketSummary } returns null
        every { project.getService(PluginSettings::class.java) } returns settings

        val service = WorkflowContextService(project, TestScope())
        assertNull(service.state.value.activeTicket)
    }
}
