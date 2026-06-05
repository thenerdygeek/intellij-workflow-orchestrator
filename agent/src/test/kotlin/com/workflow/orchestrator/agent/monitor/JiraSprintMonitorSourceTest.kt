package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.core.model.jira.JiraTicketData
import com.workflow.orchestrator.core.model.jira.SprintData
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
class JiraSprintMonitorSourceTest {

    /** Minimal JiraTicketData builder — only key/status are varied in sprint diff logic. */
    private fun ticket(
        key: String = "PROJ-1",
        status: String = "In Progress",
    ) = JiraTicketData(
        key = key,
        summary = "Some ticket",
        status = status,
        assignee = null,
        reporter = null,
        type = "Story",
        priority = null,
        description = null,
    )

    private fun sprint(id: Int, state: String = "active") = SprintData(
        id = id,
        name = "Sprint $id",
        state = state,
        startDate = null,
        endDate = null,
    )

    private fun okIssues(vararg tickets: JiraTicketData) =
        ToolResult(data = tickets.toList(), summary = "ok", isError = false)

    private fun okSprints(vararg sprints: SprintData) =
        ToolResult(data = sprints.toList(), summary = "ok", isError = false)

    private fun errIssues() =
        ToolResult<List<JiraTicketData>>(data = null, summary = "error", isError = true)

    private fun errSprints() =
        ToolResult<List<SprintData>>(data = null, summary = "error", isError = true)

    private fun sourceWithSprintId(jira: JiraService, sprintId: Int, scope: TestScope) =
        JiraSprintMonitorSource(
            monitorId = "test-sprint",
            description = "watch sprint",
            cs = scope,
            jira = jira,
            boardId = null,
            sprintId = sprintId,
        )

    private fun sourceWithBoardId(jira: JiraService, boardId: Int, scope: TestScope) =
        JiraSprintMonitorSource(
            monitorId = "test-sprint",
            description = "watch sprint",
            cs = scope,
            jira = jira,
            boardId = boardId,
            sprintId = null,
        )

    // ---- JiraSprintDiff pure tests -------------------------------------------

    @Test
    fun `diff first poll (previous null) returns empty`() {
        val cur = listOf(ticket("PROJ-1"), ticket("PROJ-2"))
        val events = JiraSprintDiff.diff("m1", previous = null, current = cur)
        assertTrue(events.isEmpty(), "first poll should be silent baseline, got: $events")
    }

    @Test
    fun `diff issue added to sprint emits NOTABLE added event`() {
        val prev = listOf(ticket("PROJ-1", "Open"))
        val cur = listOf(ticket("PROJ-1", "Open"), ticket("PROJ-2", "To Do"))
        val events = JiraSprintDiff.diff("m1", prev, cur)
        assertEquals(1, events.size, "expected 1 event for added issue, got: $events")
        assertEquals(Severity.NOTABLE, events[0].severity)
        assertTrue(events[0].line.contains("PROJ-2"), "line should reference the added key: ${events[0].line}")
        assertTrue(events[0].line.contains("added"), "line should say 'added': ${events[0].line}")
        assertTrue(events[0].line.contains("To Do"), "line should include status of added issue: ${events[0].line}")
    }

    @Test
    fun `diff issue removed from sprint emits NOTABLE removed event`() {
        val prev = listOf(ticket("PROJ-1", "Open"), ticket("PROJ-2", "To Do"))
        val cur = listOf(ticket("PROJ-1", "Open"))
        val events = JiraSprintDiff.diff("m1", prev, cur)
        assertEquals(1, events.size, "expected 1 event for removed issue, got: $events")
        assertEquals(Severity.NOTABLE, events[0].severity)
        assertTrue(events[0].line.contains("PROJ-2"), "line should reference the removed key: ${events[0].line}")
        assertTrue(events[0].line.contains("removed"), "line should say 'removed': ${events[0].line}")
    }

    @Test
    fun `diff issue status change emits NOTABLE status arrow event`() {
        val prev = listOf(ticket("PROJ-1", "Open"))
        val cur = listOf(ticket("PROJ-1", "Done"))
        val events = JiraSprintDiff.diff("m1", prev, cur)
        assertEquals(1, events.size, "expected 1 event for status change, got: $events")
        assertEquals(Severity.NOTABLE, events[0].severity)
        assertTrue(events[0].line.contains("PROJ-1"), "line should reference the key: ${events[0].line}")
        assertTrue(events[0].line.contains("Open"), "line should show old status: ${events[0].line}")
        assertTrue(events[0].line.contains("Done"), "line should show new status: ${events[0].line}")
        assertTrue(events[0].line.contains("→") || events[0].line.contains("->"),
            "line should contain transition arrow: ${events[0].line}")
    }

    @Test
    fun `diff multiple simultaneous changes produce multiple events`() {
        val prev = listOf(ticket("PROJ-1", "Open"), ticket("PROJ-2", "In Progress"))
        // PROJ-1 status change, PROJ-2 removed, PROJ-3 added
        val cur = listOf(ticket("PROJ-1", "Done"), ticket("PROJ-3", "To Do"))
        val events = JiraSprintDiff.diff("m1", prev, cur)
        assertEquals(3, events.size, "expected 3 events (1 status change + 1 removed + 1 added), got: $events")
        assertTrue(events.all { it.severity == Severity.NOTABLE })
    }

    @Test
    fun `diff no change returns empty`() {
        val issues = listOf(ticket("PROJ-1", "Open"), ticket("PROJ-2", "Done"))
        val events = JiraSprintDiff.diff("m1", issues, issues.map { it.copy() })
        assertTrue(events.isEmpty(), "no change should yield no events, got: $events")
    }

    @Test
    fun `diff status compare is case-insensitive same status different case produces no event`() {
        val prev = listOf(ticket("PROJ-1", "In Progress"))
        val cur = listOf(ticket("PROJ-1", "in progress"))
        val events = JiraSprintDiff.diff("m1", prev, cur)
        assertTrue(events.isEmpty(), "case-insensitive status match should produce no event, got: $events")
    }

    // ---- JiraSprintMonitorSource via pollOnce — sprintId path ----------------

    @Test
    fun `source with sprintId pollOnce calls getSprintIssues and getAvailableSprints is never called`() = runTest {
        val jira = mockk<JiraService>()
        coEvery { jira.getSprintIssues(42) } returns okIssues(ticket("PROJ-1"))
        val src = sourceWithSprintId(jira, sprintId = 42, scope = this)
        val events = mutableListOf<MonitorEvent>()
        src.pollOnce { events.add(it) }
        coVerify(exactly = 1) { jira.getSprintIssues(42) }
        coVerify(exactly = 0) { jira.getAvailableSprints(any()) }
    }

    @Test
    fun `source with sprintId first poll is baseline no events`() = runTest {
        val jira = mockk<JiraService>()
        coEvery { jira.getSprintIssues(10) } returns okIssues(ticket("PROJ-1", "Open"))
        val src = sourceWithSprintId(jira, sprintId = 10, scope = this)
        val events = mutableListOf<MonitorEvent>()
        val changed = src.pollOnce { events.add(it) }
        assertFalse(changed, "first poll should be baseline (false)")
        assertTrue(events.isEmpty(), "first poll should emit no events")
    }

    @Test
    fun `source with sprintId getSprintIssues isError results in fetch null and no events`() = runTest {
        val jira = mockk<JiraService>()
        coEvery { jira.getSprintIssues(99) } returns errIssues()
        val src = sourceWithSprintId(jira, sprintId = 99, scope = this)
        val events = mutableListOf<MonitorEvent>()
        val changed = src.pollOnce { events.add(it) }
        assertFalse(changed, "isError should return false")
        assertTrue(events.isEmpty(), "isError should yield no events")
    }

    // ---- JiraSprintMonitorSource via pollOnce — boardId resolution path -------

    @Test
    fun `source with boardId resolves active sprint via getAvailableSprints then calls getSprintIssues`() = runTest {
        val jira = mockk<JiraService>()
        coEvery { jira.getAvailableSprints(5) } returns okSprints(
            sprint(id = 99, state = "closed"),
            sprint(id = 100, state = "active"),
        )
        coEvery { jira.getSprintIssues(100) } returns okIssues(ticket("PROJ-1"))
        val src = sourceWithBoardId(jira, boardId = 5, scope = this)
        val events = mutableListOf<MonitorEvent>()
        src.pollOnce { events.add(it) }
        coVerify(exactly = 1) { jira.getAvailableSprints(5) }
        coVerify(exactly = 1) { jira.getSprintIssues(100) }
    }

    @Test
    fun `source with boardId no active sprint fetch returns null and no events`() = runTest {
        val jira = mockk<JiraService>()
        coEvery { jira.getAvailableSprints(7) } returns okSprints(sprint(id = 200, state = "closed"))
        val src = sourceWithBoardId(jira, boardId = 7, scope = this)
        val events = mutableListOf<MonitorEvent>()
        val changed = src.pollOnce { events.add(it) }
        assertFalse(changed, "no active sprint should return false")
        assertTrue(events.isEmpty(), "no active sprint should yield no events")
        coVerify(exactly = 0) { jira.getSprintIssues(any()) }
    }

    @Test
    fun `source with boardId getAvailableSprints isError fetch returns null and no events`() = runTest {
        val jira = mockk<JiraService>()
        coEvery { jira.getAvailableSprints(8) } returns errSprints()
        val src = sourceWithBoardId(jira, boardId = 8, scope = this)
        val events = mutableListOf<MonitorEvent>()
        val changed = src.pollOnce { events.add(it) }
        assertFalse(changed, "getAvailableSprints isError should return false")
        assertTrue(events.isEmpty(), "getAvailableSprints isError should yield no events")
        coVerify(exactly = 0) { jira.getSprintIssues(any()) }
    }

    @Test
    fun `source with boardId active sprint resolved case-insensitively when state is ACTIVE uppercase`() = runTest {
        val jira = mockk<JiraService>()
        coEvery { jira.getAvailableSprints(9) } returns okSprints(
            sprint(id = 300, state = "ACTIVE"),
        )
        coEvery { jira.getSprintIssues(300) } returns okIssues(ticket("PROJ-1"))
        val src = sourceWithBoardId(jira, boardId = 9, scope = this)
        val events = mutableListOf<MonitorEvent>()
        src.pollOnce { events.add(it) }
        coVerify(exactly = 1) { jira.getSprintIssues(300) }
    }

    @Test
    fun `source with boardId once active sprint is resolved subsequent pollOnce does not call getAvailableSprints again`() = runTest {
        val jira = mockk<JiraService>()
        coEvery { jira.getAvailableSprints(11) } returns okSprints(sprint(id = 400, state = "active"))
        coEvery { jira.getSprintIssues(400) } returns okIssues(ticket("PROJ-1", "Open"))
        val src = sourceWithBoardId(jira, boardId = 11, scope = this)
        // First poll: resolves active sprint
        src.pollOnce { }
        // Second poll: should NOT call getAvailableSprints again (cached resolvedSprintId)
        src.pollOnce { }
        coVerify(exactly = 1) { jira.getAvailableSprints(any()) }
        coVerify(exactly = 2) { jira.getSprintIssues(400) }
    }
}
