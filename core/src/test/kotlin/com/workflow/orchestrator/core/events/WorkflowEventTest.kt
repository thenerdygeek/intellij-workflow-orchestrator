package com.workflow.orchestrator.core.events

import org.junit.Test
import kotlin.test.assertEquals
import java.time.Instant

class WorkflowEventTest {

    @Test
    fun `HandoverOverride captures ticketId, action, failedChecks, timestamp`() {
        val now = Instant.parse("2026-05-08T10:00:00Z")
        val ev = WorkflowEvent.HandoverOverride(
            ticketId = "AFTER8TE-912",
            action = WorkflowEvent.HandoverAction.POST_JIRA,
            failedChecks = listOf("quality.gate", "suite.web-e2e"),
            timestamp = now
        )
        assertEquals("AFTER8TE-912", ev.ticketId)
        assertEquals(WorkflowEvent.HandoverAction.POST_JIRA, ev.action)
        assertEquals(2, ev.failedChecks.size)
        assertEquals(now, ev.timestamp)
    }

    @Test
    fun `HandoverChipCopied captures chipKey`() {
        val ev = WorkflowEvent.HandoverChipCopied("docker.tagsJson")
        assertEquals("docker.tagsJson", ev.chipKey)
    }
}
