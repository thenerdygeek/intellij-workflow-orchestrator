package com.workflow.orchestrator.jira.service

import com.workflow.orchestrator.core.model.ApiResult
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
        coEvery { apiClient.getBoards(any()) } returns ApiResult.Success(listOf(testBoard))
        coEvery { apiClient.getActiveSprints(1) } returns ApiResult.Success(listOf(testSprint))
        coEvery { apiClient.getSprintIssues(42, any()) } returns ApiResult.Success(listOf(testIssue))

        val result = sprintService.loadSprintIssues()

        assertTrue(result.isSuccess)
        val issues = (result as ApiResult.Success).data
        assertEquals(1, issues.size)
        assertEquals("PROJ-123", issues[0].key)
    }

    @Test
    fun `loadSprintIssues uses configured board ID when available`() = runTest {
        coEvery { apiClient.getBoards(any()) } returns ApiResult.Success(listOf(testBoard))
        coEvery { apiClient.getActiveSprints(1) } returns ApiResult.Success(listOf(testSprint))
        coEvery { apiClient.getSprintIssues(42, any()) } returns ApiResult.Success(listOf(testIssue))

        val result = sprintService.loadSprintIssues(boardId = 1)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `loadSprintIssues returns error when no boards found`() = runTest {
        coEvery { apiClient.getBoards(any()) } returns ApiResult.Success(emptyList())

        val result = sprintService.loadSprintIssues()

        assertTrue(result.isError)
    }

    @Test
    fun `loadSprintIssues falls back to board issues when no active sprint`() = runTest {
        coEvery { apiClient.getBoards(any()) } returns ApiResult.Success(listOf(testBoard))
        coEvery { apiClient.getActiveSprints(1) } returns ApiResult.Success(emptyList())
        coEvery { apiClient.getBoardIssues(1, any()) } returns ApiResult.Success(listOf(testIssue))

        val result = sprintService.loadSprintIssues()

        assertTrue(result.isSuccess)
        assertNull(sprintService.activeSprint)
        assertEquals(1, (result as ApiResult.Success).data.size)
    }

    @Test
    fun `loadSprintIssues loads kanban board issues directly`() = runTest {
        coEvery { apiClient.getBoards(any()) } returns ApiResult.Success(listOf(testKanbanBoard))
        coEvery { apiClient.getBoardIssues(2, any()) } returns ApiResult.Success(listOf(testIssue))

        val result = sprintService.loadSprintIssues(boardTypeFilter = "kanban")

        assertTrue(result.isSuccess)
        assertNull(sprintService.activeSprint)
        assertNotNull(sprintService.discoveredBoard)
        assertEquals("kanban", sprintService.discoveredBoard?.type)
    }

    @Test
    fun `getActiveSprint returns current sprint info`() = runTest {
        coEvery { apiClient.getBoards(any()) } returns ApiResult.Success(listOf(testBoard))
        coEvery { apiClient.getActiveSprints(1) } returns ApiResult.Success(listOf(testSprint))
        coEvery { apiClient.getSprintIssues(42, any()) } returns ApiResult.Success(listOf(testIssue))

        sprintService.loadSprintIssues()

        val sprint = sprintService.activeSprint
        assertNotNull(sprint)
        assertEquals("Sprint 14", sprint?.name)
    }
}
