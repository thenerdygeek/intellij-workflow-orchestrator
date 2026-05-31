package com.workflow.orchestrator.jira.service

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.api.dto.*
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SprintServiceTest {

    private lateinit var apiClient: JiraApiClient
    private lateinit var sprintService: SprintService

    private val testBoard = JiraBoard(id = 1, name = "Board", type = "scrum")
    private val testKanbanBoard = JiraBoard(id = 2, name = "Kanban Board", type = "kanban")
    private val testSprint = JiraSprint(id = 42, name = "Sprint 14", state = "active")
    private val testIssue = JiraIssue(
        id = "10001", key = "PROJ-123",
        fields = JiraIssueFields(
            summary = "Fix login",
            status = JiraStatus(name = "To Do")
        )
    )

    @BeforeEach
    fun setUp() {
        apiClient = mockk()
        sprintService = SprintService(apiClient)
    }

    @Test
    fun `loadSprintIssues returns issues for auto-discovered scrum board and sprint`() = runTest {
        coEvery { apiClient.getBoards(any(), any()) } returns ApiResult.Success(listOf(testBoard))
        coEvery { apiClient.getActiveSprints(1) } returns ApiResult.Success(listOf(testSprint))
        coEvery { apiClient.getSprintIssues(42, any()) } returns ApiResult.Success(listOf(testIssue))

        val result = sprintService.loadSprintIssues()

        assertTrue(result.isSuccess)
        val issues = (result as ApiResult.Success).data
        assertEquals(1, issues.size)
        assertEquals("PROJ-123", issues[0].key)
    }

    @Test
    fun `loadSprintIssues uses configured board ID without fetching board list`() = runTest {
        // When boardId is configured, getBoards should NOT be called — uses saved board directly
        coEvery { apiClient.getActiveSprints(1) } returns ApiResult.Success(listOf(testSprint))
        coEvery { apiClient.getSprintIssues(42, any()) } returns ApiResult.Success(listOf(testIssue))

        val result = sprintService.loadSprintIssues(boardId = 1, boardName = "My Board")

        assertTrue(result.isSuccess)
        assertEquals("My Board", sprintService.discoveredBoard?.name)
    }

    @Test
    fun `loadSprintIssues returns error when no boards found`() = runTest {
        coEvery { apiClient.getBoards(any(), any()) } returns ApiResult.Success(emptyList())

        val result = sprintService.loadSprintIssues()

        assertTrue(result.isError)
    }

    @Test
    fun `loadSprintIssues falls back to board issues when no active sprint`() = runTest {
        coEvery { apiClient.getBoards(any(), any()) } returns ApiResult.Success(listOf(testBoard))
        coEvery { apiClient.getActiveSprints(1) } returns ApiResult.Success(emptyList())
        coEvery { apiClient.getBoardIssues(1, any()) } returns ApiResult.Success(listOf(testIssue))

        val result = sprintService.loadSprintIssues()

        assertTrue(result.isSuccess)
        assertNull(sprintService.activeSprint)
        assertEquals(1, (result as ApiResult.Success).data.size)
    }

    @Test
    fun `loadSprintIssues loads kanban board issues directly`() = runTest {
        coEvery { apiClient.getBoards(any(), any()) } returns ApiResult.Success(listOf(testKanbanBoard))
        coEvery { apiClient.getBoardIssues(2, any()) } returns ApiResult.Success(listOf(testIssue))

        val result = sprintService.loadSprintIssues(boardTypeFilter = "kanban")

        assertTrue(result.isSuccess)
        assertNull(sprintService.activeSprint)
        assertNotNull(sprintService.discoveredBoard)
        assertEquals("kanban", sprintService.discoveredBoard?.type)
    }

    @Test
    fun `getActiveSprint returns current sprint info`() = runTest {
        coEvery { apiClient.getBoards(any(), any()) } returns ApiResult.Success(listOf(testBoard))
        coEvery { apiClient.getActiveSprints(1) } returns ApiResult.Success(listOf(testSprint))
        coEvery { apiClient.getSprintIssues(42, any()) } returns ApiResult.Success(listOf(testIssue))

        sprintService.loadSprintIssues()

        val sprint = sprintService.activeSprint
        assertNotNull(sprint)
        assertEquals("Sprint 14", sprint?.name)
    }

    // ── JIRA-COV-1: board API error paths ────────────────────────────────────


    @Test
    fun `loadSprintIssues falls back to board issues when getActiveSprints returns API error`() = runTest {
        coEvery { apiClient.getBoards(any(), any()) } returns ApiResult.Success(listOf(testBoard))
        coEvery { apiClient.getActiveSprints(1) } returns ApiResult.Error(
            ErrorType.SERVER_ERROR, "500 Internal Server Error"
        )
        coEvery { apiClient.getBoardIssues(1, any()) } returns ApiResult.Success(listOf(testIssue))

        val result = sprintService.loadSprintIssues()

        // The sprint-error fallback path loads kanban-style board issues; activeSprint stays null
        assertTrue(result.isSuccess,
            "getActiveSprints API error must trigger fallback to board issues, not propagate as error")
        assertNull(sprintService.activeSprint,
            "activeSprint must remain null after falling back from a sprint API error")
        assertEquals(1, (result as ApiResult.Success).data.size)
    }

    // ── JIRA-COV-2: loadAvailableSprints + loadIssuesForSprint ───────────────

    @Test
    fun `loadAvailableSprints returns active sprints then sorted closed sprints`() = runTest {
        val activeSprint = JiraSprint(id = 10, name = "Sprint Active", state = "active",
            endDate = "2026-06-01")
        val closedOld = JiraSprint(id = 8, name = "Sprint Old", state = "closed",
            endDate = "2026-04-01")
        val closedRecent = JiraSprint(id = 9, name = "Sprint Recent", state = "closed",
            endDate = "2026-05-01")

        coEvery { apiClient.getActiveSprints(1) } returns ApiResult.Success(listOf(activeSprint))
        // A single page that is the last page (isLast=true), containing 2 closed sprints
        coEvery { apiClient.getClosedSprints(1, any(), any()) } returns ApiResult.Success(
            JiraSprintSearchResult(
                maxResults = 50, startAt = 0, isLast = true,
                values = listOf(closedOld, closedRecent)
            )
        )

        val result = sprintService.loadAvailableSprints(1)

        // Active sprint must come first, closed sprints sorted by endDate descending
        assertEquals(3, result.size)
        assertEquals("Sprint Active", result[0].name, "Active sprint must be first")
        assertEquals("Sprint Recent", result[1].name, "Most recently closed sprint must be second")
        assertEquals("Sprint Old", result[2].name, "Older closed sprint must be last")
    }

    @Test
    fun `loadAvailableSprints handles getClosedSprints API error gracefully`() = runTest {
        val activeSprint = JiraSprint(id = 10, name = "Sprint Active", state = "active")
        coEvery { apiClient.getActiveSprints(1) } returns ApiResult.Success(listOf(activeSprint))
        coEvery { apiClient.getClosedSprints(1, any(), any()) } returns ApiResult.Error(
            ErrorType.SERVER_ERROR, "500 Internal Server Error"
        )

        val result = sprintService.loadAvailableSprints(1)

        // Should return just the active sprint without throwing
        assertEquals(1, result.size,
            "getClosedSprints error must degrade gracefully — return only active sprint without throwing")
        assertEquals("Sprint Active", result[0].name)
    }

    @Test
    fun `loadIssuesForSprint returns issues and updates getCachedIssues`() = runTest {
        coEvery { apiClient.getSprintIssues(99, any()) } returns ApiResult.Success(listOf(testIssue))

        val result = sprintService.loadIssuesForSprint(sprintId = 99, allUsers = false)

        assertTrue(result.isSuccess)
        assertEquals(1, (result as ApiResult.Success).data.size)
        assertEquals("PROJ-123", result.data[0].key)
        // getCachedIssues() should reflect the newly loaded issues
        assertEquals(1, sprintService.getCachedIssues().size,
            "getCachedIssues must be updated after loadIssuesForSprint")
        assertEquals("PROJ-123", sprintService.getCachedIssues()[0].key)
    }
}
