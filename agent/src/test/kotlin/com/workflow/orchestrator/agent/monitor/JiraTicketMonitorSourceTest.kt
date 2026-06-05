package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.core.model.jira.JiraTicketData
import com.workflow.orchestrator.core.services.JiraService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class JiraTicketMonitorSourceTest {

    /** Minimal JiraTicketData builder — only the fields the diff logic cares about. */
    private fun ticket(
        key: String = "PROJ-123",
        summary: String = "Some ticket",
        status: String = "In Progress",
        assignee: String? = null,
    ) = JiraTicketData(
        key = key,
        summary = summary,
        status = status,
        assignee = assignee,
        reporter = null,
        type = "Story",
        priority = null,
        description = null,
    )

    private fun okResult(t: JiraTicketData) =
        ToolResult(data = t, summary = "ok", isError = false)

    private fun errResult() =
        ToolResult<JiraTicketData>(data = null, summary = "error", isError = true)

    private fun source(jira: JiraService, ticketKey: String = "PROJ-123", scope: TestScope) =
        JiraTicketMonitorSource(
            monitorId = "test-jira",
            description = "watch ticket",
            cs = scope,
            jira = jira,
            ticketKey = ticketKey,
        )

    // ---- JiraTicketDiff pure tests -------------------------------------------

    @Test
    fun `diff first poll (previous null) returns empty`() {
        val cur = ticket(status = "Open")
        val events = JiraTicketDiff.diff("m1", previous = null, current = cur)
        assertTrue(events.isEmpty(), "first poll should be baseline only, got: $events")
    }

    @Test
    fun `diff status change emits one NOTABLE event`() {
        val prev = ticket(status = "Open")
        val cur = ticket(status = "In Progress")
        val events = JiraTicketDiff.diff("m1", prev, cur)
        assertEquals(1, events.size, "expected 1 event, got: $events")
        assertEquals(Severity.NOTABLE, events[0].severity)
        assertTrue(events[0].line.contains("Open"), "line should reference old status: ${events[0].line}")
        assertTrue(events[0].line.contains("In Progress"), "line should reference new status: ${events[0].line}")
    }

    @Test
    fun `diff assignee change null to name emits one NOTABLE event`() {
        val prev = ticket(assignee = null)
        val cur = ticket(assignee = "alice")
        val events = JiraTicketDiff.diff("m1", prev, cur)
        assertEquals(1, events.size, "expected 1 event for assignee change, got: $events")
        assertEquals(Severity.NOTABLE, events[0].severity)
        assertTrue(events[0].line.contains("none"), "line should mention 'none' for null assignee: ${events[0].line}")
        assertTrue(events[0].line.contains("alice"), "line should mention new assignee: ${events[0].line}")
    }

    @Test
    fun `diff assignee change name to null emits one NOTABLE event`() {
        val prev = ticket(assignee = "bob")
        val cur = ticket(assignee = null)
        val events = JiraTicketDiff.diff("m1", prev, cur)
        assertEquals(1, events.size, "expected 1 event for assignee removal, got: $events")
        assertEquals(Severity.NOTABLE, events[0].severity)
        assertTrue(events[0].line.contains("bob"), "line should reference old assignee: ${events[0].line}")
        assertTrue(events[0].line.contains("none"), "line should reference 'none' for removed assignee: ${events[0].line}")
    }

    @Test
    fun `diff status AND assignee both change emits two NOTABLE events`() {
        val prev = ticket(status = "Open", assignee = null)
        val cur = ticket(status = "Done", assignee = "carol")
        val events = JiraTicketDiff.diff("m1", prev, cur)
        assertEquals(2, events.size, "expected 2 events when both status and assignee change, got: $events")
        assertTrue(events.all { it.severity == Severity.NOTABLE })
    }

    @Test
    fun `diff no change returns empty`() {
        val t = ticket(status = "Open", assignee = "dave")
        val events = JiraTicketDiff.diff("m1", t, t.copy())
        assertTrue(events.isEmpty(), "no change should yield no events, got: $events")
    }

    @Test
    fun `diff status compare is case-insensitive — same status different case produces no event`() {
        val prev = ticket(status = "In Progress")
        val cur = ticket(status = "in progress")
        val events = JiraTicketDiff.diff("m1", prev, cur)
        assertTrue(events.isEmpty(), "case-insensitive status match should produce no event, got: $events")
    }

    @Test
    fun `diff events carry the ticket key in the line`() {
        val prev = ticket(key = "MYPROJ-42", status = "Open")
        val cur = ticket(key = "MYPROJ-42", status = "Done")
        val events = JiraTicketDiff.diff("m1", prev, cur)
        assertEquals(1, events.size)
        assertTrue(events[0].line.contains("MYPROJ-42"), "event line should include the ticket key: ${events[0].line}")
    }

    // ---- JiraTicketMonitorSource via pollOnce --------------------------------

    @Test
    fun `getTicket isError results in fetch null and pollOnce returns false with no events`() = runTest {
        val jira = mockk<JiraService>()
        coEvery { jira.getTicket("PROJ-123") } returns errResult()
        val src = source(jira, scope = this)
        val events = mutableListOf<MonitorEvent>()
        val changed = src.pollOnce { events.add(it) }
        assertFalse(changed, "isError fetch should return false")
        assertTrue(events.isEmpty(), "isError should yield no events")
    }

    @Test
    fun `first pollOnce (baseline) returns false and no events`() = runTest {
        val jira = mockk<JiraService>()
        coEvery { jira.getTicket("PROJ-123") } returns okResult(ticket(status = "Open"))
        val src = source(jira, scope = this)
        val events = mutableListOf<MonitorEvent>()
        val changed = src.pollOnce { events.add(it) }
        assertFalse(changed, "first poll should be baseline only (false)")
        assertTrue(events.isEmpty(), "first poll should emit no events")
    }

    @Test
    fun `two pollOnce across status change emits one NOTABLE event on second poll`() = runTest {
        val jira = mockk<JiraService>()
        coEvery { jira.getTicket("PROJ-123") } returnsMany listOf(
            okResult(ticket(status = "Open")),
            okResult(ticket(status = "In Review")),
        )
        val src = source(jira, scope = this)
        val events = mutableListOf<MonitorEvent>()
        // First poll: baseline
        val changed1 = src.pollOnce { events.add(it) }
        assertFalse(changed1, "first poll is baseline")
        assertTrue(events.isEmpty(), "first poll emits nothing")
        // Second poll: status changed
        val changed2 = src.pollOnce { events.add(it) }
        assertTrue(changed2, "second poll with status change should return true")
        assertEquals(1, events.size, "expected 1 event on status change")
        assertEquals(Severity.NOTABLE, events[0].severity)
        coVerify(exactly = 2) { jira.getTicket("PROJ-123") }
    }
}
