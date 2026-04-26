package com.workflow.orchestrator.core.workflow

import com.workflow.orchestrator.core.model.workflow.PrRef
import com.workflow.orchestrator.core.model.workflow.TicketRef
import com.workflow.orchestrator.core.model.workflow.WorkflowContext
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class WorkflowContextEqualsTest {
    @Test fun `identical declared fields are equal`() {
        val t = TicketRef("AFTER8TE-912", "Fix login")
        assertEquals(WorkflowContext(activeTicket = t), WorkflowContext(activeTicket = t))
    }

    @Test fun `different focusPr are not equal`() {
        val a = WorkflowContext(focusPr = PrRef(42, "f", "m", "r", null, null))
        val b = WorkflowContext(focusPr = PrRef(43, "f", "m", "r", null, null))
        assertNotEquals(a, b)
    }
}
