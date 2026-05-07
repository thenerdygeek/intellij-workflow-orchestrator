package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.core.model.jira.DevStatusBundle
import com.workflow.orchestrator.core.model.jira.JiraTicketData
import com.workflow.orchestrator.core.model.jira.MyPermissionsData
import com.workflow.orchestrator.core.model.jira.PermissionFlag
import com.workflow.orchestrator.core.model.jira.RemoteLinkData
import com.workflow.orchestrator.core.model.jira.TicketHistoryEntry
import com.workflow.orchestrator.core.services.JiraService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the [JiraTool] `get_ticket` N-way fan-out:
 *  - include_remote_links flag
 *  - include_history flag
 *  - multiple flags in parallel
 *  - flag omitted = no extra fetch
 *
 * Exercises [JiraTool.executeGetTicketForTest] so tests do not require
 * IntelliJ service infrastructure (mirrors JiraToolDownloadAttachmentTest pattern).
 *
 * Run with: ./gradlew :agent:test --tests "*JiraToolGetTicketTest*"
 */
class JiraToolGetTicketTest {

    private val tool = JiraTool()

    private fun ticketResult() = ToolResult(
        data = JiraTicketData(
            key = "PROJ-1",
            summary = "Fix the login bug",
            status = "In Progress",
            assignee = "jdoe",
            reporter = "alice",
            type = "Bug",
            priority = "High",
            description = "Login fails on Safari"
        ),
        summary = "PROJ-1: Fix the login bug",
        isError = false
    )

    private fun devStatusResult() = ToolResult(
        data = DevStatusBundle(
            branches = emptyList(),
            pullRequests = emptyList(),
            commits = emptyList(),
            builds = emptyList(),
            deployments = emptyList(),
            reviews = emptyList(),
            fetchErrors = 0,
            fetchedAt = 0L
        ),
        summary = "Dev status fetched",
        isError = false
    )

    private fun remoteLinksResult() = ToolResult(
        data = listOf(
            RemoteLinkData(
                id = 42L,
                applicationType = "com.atlassian.confluence",
                applicationName = "Confluence",
                relationship = "Wiki Page",
                url = "https://confluence.example.com/pages/123",
                title = "Design Doc"
            )
        ),
        summary = "1 remote link(s) for PROJ-1",
        isError = false
    )

    private fun historyResult() = ToolResult(
        data = listOf(
            TicketHistoryEntry(
                actorDisplayName = "Jane Smith",
                createdAt = "2026-05-07T10:00:00.000+0000",
                field = "status",
                oldValue = "To Do",
                newValue = "In Progress"
            )
        ),
        summary = "1 history entry(ies) for PROJ-1",
        isError = false
    )

    // ── include_remote_links=true ─────────────────────────────────────────────

    @Test
    fun `get_ticket with include_remote_links=true fetches remote links and appends block`() = runTest {
        val service = mockk<JiraService>()
        coEvery { service.getTicket("PROJ-1") } returns ticketResult()
        coEvery { service.getRemoteLinks("PROJ-1") } returns remoteLinksResult()

        val result = tool.executeGetTicketForTest(
            key = "PROJ-1",
            includeDevStatus = false,
            includeRemoteLinks = true,
            includeHistory = false,
            service = service
        )

        assertFalse(result.isError)
        assertTrue(result.content.contains("Remote Links"), "content must contain 'Remote Links' header")
        assertTrue(result.content.contains("Confluence"), "content must reference applicationName from stub")
        assertTrue(result.content.contains("Design Doc"), "content must reference title from stub")
        coVerify(exactly = 1) { service.getRemoteLinks("PROJ-1") }
    }

    // ── include_remote_links omitted → no fetch ───────────────────────────────

    @Test
    fun `get_ticket with include_remote_links omitted does NOT fetch remote links`() = runTest {
        val service = mockk<JiraService>()
        coEvery { service.getTicket("PROJ-1") } returns ticketResult()

        tool.executeGetTicketForTest(
            key = "PROJ-1",
            includeDevStatus = false,
            includeRemoteLinks = false,
            includeHistory = false,
            service = service
        )

        coVerify(exactly = 0) { service.getRemoteLinks(any()) }
    }

    // ── include_history=true ──────────────────────────────────────────────────

    @Test
    fun `get_ticket with include_history=true fetches history and appends block`() = runTest {
        val service = mockk<JiraService>()
        coEvery { service.getTicket("PROJ-1") } returns ticketResult()
        coEvery { service.getTicketHistory("PROJ-1") } returns historyResult()

        val result = tool.executeGetTicketForTest(
            key = "PROJ-1",
            includeDevStatus = false,
            includeRemoteLinks = false,
            includeHistory = true,
            service = service
        )

        assertFalse(result.isError)
        assertTrue(result.content.contains("History"), "content must contain 'History' header")
        assertTrue(result.content.contains("Jane Smith"), "content must reference author from stub")
        assertTrue(result.content.contains("status"), "content must reference changed field")
        coVerify(exactly = 1) { service.getTicketHistory("PROJ-1") }
    }

    private fun permissionsResult() = ToolResult(
        data = MyPermissionsData(
            permissions = mapOf(
                "TRANSITION_ISSUES" to PermissionFlag(
                    key = "TRANSITION_ISSUES",
                    name = "Transition Issues",
                    havePermission = true
                ),
                "ADD_COMMENTS" to PermissionFlag(
                    key = "ADD_COMMENTS",
                    name = "Add Comments",
                    havePermission = true
                ),
                "WORK_ON_ISSUES" to PermissionFlag(
                    key = "WORK_ON_ISSUES",
                    name = "Work On Issues",
                    havePermission = false
                )
            )
        ),
        summary = "Permissions for project PROJ",
        isError = false
    )

    // ── include_permissions=true ─────────────────────────────────────────────

    @Test
    fun `get_ticket with include_permissions=true fetches permissions and appends block`() = runTest {
        val service = mockk<JiraService>()
        coEvery { service.getTicket("PROJ-1") } returns ticketResult()
        coEvery { service.getMyPermissions("PROJ") } returns permissionsResult()

        val result = tool.executeGetTicketForTest(
            key = "PROJ-1",
            includeDevStatus = false,
            includeRemoteLinks = false,
            includeHistory = false,
            includePermissions = true,
            service = service
        )

        assertFalse(result.isError)
        assertTrue(result.content.contains("Permissions"), "content must contain 'Permissions' header")
        assertTrue(
            result.content.contains("TRANSITION_ISSUES") || result.content.contains("Transition Issues"),
            "content must reference at least one permission name"
        )
        assertTrue(
            result.content.contains("true") || result.content.contains("false") ||
                result.content.contains("granted") || result.content.contains("denied"),
            "content must reference a permission boolean state"
        )
        coVerify(exactly = 1) { service.getMyPermissions("PROJ") }
    }

    // ── include_permissions omitted → no fetch ────────────────────────────────

    @Test
    fun `get_ticket without include_permissions does NOT fetch permissions`() = runTest {
        val service = mockk<JiraService>()
        coEvery { service.getTicket("PROJ-1") } returns ticketResult()

        tool.executeGetTicketForTest(
            key = "PROJ-1",
            includeDevStatus = false,
            includeRemoteLinks = false,
            includeHistory = false,
            service = service
        )

        coVerify(exactly = 0) { service.getMyPermissions(any()) }
    }

    // ── multiple flags fan-out in parallel ────────────────────────────────────

    @Test
    fun `get_ticket with multiple include flags fans out in parallel`() = runTest {
        val service = mockk<JiraService>()
        coEvery { service.getTicket("PROJ-1") } returns ticketResult()
        coEvery { service.getFullDevStatus("PROJ-1") } returns devStatusResult()
        coEvery { service.getRemoteLinks("PROJ-1") } returns remoteLinksResult()
        coEvery { service.getTicketHistory("PROJ-1") } returns historyResult()

        val result = tool.executeGetTicketForTest(
            key = "PROJ-1",
            includeDevStatus = true,
            includeRemoteLinks = true,
            includeHistory = true,
            service = service
        )

        assertFalse(result.isError)
        // All three extension blocks must be present
        assertTrue(result.content.contains("Dev Status"), "content must contain Dev Status block")
        assertTrue(result.content.contains("Remote Links"), "content must contain Remote Links block")
        assertTrue(result.content.contains("History"), "content must contain History block")
        // Each service method called exactly once
        coVerify(exactly = 1) { service.getTicket("PROJ-1") }
        coVerify(exactly = 1) { service.getFullDevStatus("PROJ-1") }
        coVerify(exactly = 1) { service.getRemoteLinks("PROJ-1") }
        coVerify(exactly = 1) { service.getTicketHistory("PROJ-1") }
    }
}
