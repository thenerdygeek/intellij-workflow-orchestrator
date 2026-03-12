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
    fun `loadSprintIssues returns issues for auto-discovered board and sprint`() = runTest {
        coEvery { apiClient.getBoards(any()) } returns ApiResult.Success(listOf(testBoard))
        coEvery { apiClient.getActiveSprints(1) } returns ApiResult.Success(listOf(testSprint))
        coEvery { apiClient.getSprintIssues(42) } returns ApiResult.Success(listOf(testIssue))

        val result = sprintService.loadSprintIssues()

        assertTrue(result.isSuccess)
        val issues = (result as ApiResult.Success).data
        assertEquals(1, issues.size)
        assertEquals("PROJ-123", issues[0].key)
    }

    @Test
    fun `loadSprintIssues uses configured board ID when available`() = runTest {
        coEvery { apiClient.getActiveSprints(5) } returns ApiResult.Success(listOf(testSprint))
        coEvery { apiClient.getSprintIssues(42) } returns ApiResult.Success(listOf(testIssue))

        val result = sprintService.loadSprintIssues(boardId = 5)

        assertTrue(result.isSuccess)
    }

    @Test
    fun `loadSprintIssues returns error when no boards found`() = runTest {
        coEvery { apiClient.getBoards(any()) } returns ApiResult.Success(emptyList())

        val result = sprintService.loadSprintIssues()

        assertTrue(result.isError)
    }

    @Test
    fun `loadSprintIssues returns error when no active sprint`() = runTest {
        coEvery { apiClient.getBoards(any()) } returns ApiResult.Success(listOf(testBoard))
        coEvery { apiClient.getActiveSprints(1) } returns ApiResult.Success(emptyList())

        val result = sprintService.loadSprintIssues()

        assertTrue(result.isError)
    }

    @Test
    fun `getActiveSprint returns current sprint info`() = runTest {
        coEvery { apiClient.getBoards(any()) } returns ApiResult.Success(listOf(testBoard))
        coEvery { apiClient.getActiveSprints(1) } returns ApiResult.Success(listOf(testSprint))
        coEvery { apiClient.getSprintIssues(42) } returns ApiResult.Success(listOf(testIssue))

        sprintService.loadSprintIssues()

        val sprint = sprintService.activeSprint
        assertNotNull(sprint)
        assertEquals("Sprint 14", sprint?.name)
    }
}
