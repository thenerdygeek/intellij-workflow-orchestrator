package com.workflow.orchestrator.jira.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.model.workflow.TicketRef
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import com.workflow.orchestrator.core.workflow.WorkflowContextService
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ActiveTicketServiceFacadeTest {

    @Test
    fun `setActiveTicket on facade updates local cache synchronously and dispatches canonical write`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val canonical = mockk<WorkflowContextService>(relaxed = true)
        val eventBus = mockk<EventBus>(relaxed = true)
        every { project.getService(WorkflowContextService::class.java) } returns canonical
        every { project.getService(EventBus::class.java) } returns eventBus
        every { canonical.state } returns MutableStateFlow(WorkflowContext())
        every { canonical.activeTicketFlow } returns MutableStateFlow(null)

        val scope = TestScope(testScheduler)
        val facade = ActiveTicketService(project, scope)
        facade.setActiveTicket("AFTER8TE-912", "Fix login")

        // Synchronous local cache — assert immediately, no advance needed.
        assertEquals("AFTER8TE-912", facade.activeTicketId)
        assertEquals("Fix login", facade.activeTicketSummary)

        // Background dispatch to canonical:
        scope.advanceUntilIdle()
        coVerify { canonical.setActiveTicket(TicketRef("AFTER8TE-912", "Fix login")) }
        coVerify { eventBus.emit(WorkflowEvent.TicketChanged("AFTER8TE-912", "Fix login")) }
    }

    @Test
    fun `clearActiveTicket clears local cache synchronously and dispatches null to canonical`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val canonical = mockk<WorkflowContextService>(relaxed = true)
        val eventBus = mockk<EventBus>(relaxed = true)
        every { project.getService(WorkflowContextService::class.java) } returns canonical
        every { project.getService(EventBus::class.java) } returns eventBus
        every { canonical.state } returns MutableStateFlow(WorkflowContext(activeTicket = TicketRef("X-1", "bootstrapped")))
        every { canonical.activeTicketFlow } returns MutableStateFlow(null)

        val scope = TestScope(testScheduler)
        val facade = ActiveTicketService(project, scope)
        // Bootstrap shows up in local cache from canonical state.value at construction:
        assertEquals("X-1", facade.activeTicketId)

        facade.clearActiveTicket()
        assertEquals(null, facade.activeTicketId)

        scope.advanceUntilIdle()
        coVerify { canonical.setActiveTicket(null) }
        coVerify { eventBus.emit(WorkflowEvent.TicketChanged("", "")) }
    }
}
