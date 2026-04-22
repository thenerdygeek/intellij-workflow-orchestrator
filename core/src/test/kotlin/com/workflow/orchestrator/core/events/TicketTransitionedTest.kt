package com.workflow.orchestrator.core.events

import com.workflow.orchestrator.core.model.jira.StatusCategory
import com.workflow.orchestrator.core.model.jira.StatusRef
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TicketTransitionedTest {
    @Test
    fun `TicketTransitioned is a WorkflowEvent`() {
        val e: WorkflowEvent = TicketTransitioned(
            key = "ABC-1",
            fromStatus = StatusRef("1", "To Do", StatusCategory.TO_DO),
            toStatus = StatusRef("3", "In Progress", StatusCategory.IN_PROGRESS),
            transitionId = "21"
        )
        assertTrue(e is TicketTransitioned)
    }
}
