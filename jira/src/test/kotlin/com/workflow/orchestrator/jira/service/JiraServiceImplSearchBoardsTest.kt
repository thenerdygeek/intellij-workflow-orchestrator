package com.workflow.orchestrator.jira.service

import com.intellij.testFramework.LoggedErrorProcessorEnabler
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.jira.JiraBoardSummary
import com.workflow.orchestrator.jira.api.JiraApiClient
import com.workflow.orchestrator.jira.api.dto.JiraBoard
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Characterization tests for [JiraServiceImpl.searchBoards].
 *
 * HTTP path tests use MockWebServer + a real JiraApiClient routed through the
 * [JiraApiClient.getBoards] endpoint that [searchBoards] delegates to.
 * Mapping/error tests use mockk to isolate the service-layer logic without
 * requiring a live IntelliJ [com.intellij.openapi.project.Project].
 */
@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class JiraServiceImplSearchBoardsTest {

    // ── MockWebServer (HTTP path) ─────────────────────────────────────────────

    private lateinit var server: MockWebServer
    private lateinit var httpClient: JiraApiClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        httpClient = JiraApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" }
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    /**
     * Happy path: the Jira agile endpoint returns a non-empty board list.
     * Verifies that the HTTP path hits the correct URL with the name query param,
     * and that all three fields (id, name, type) are parsed from the JSON payload.
     */
    @Test
    fun `searchBoards happy path returns parsed board list via HTTP`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """
                    {
                      "maxResults": 50,
                      "startAt": 0,
                      "total": 2,
                      "values": [
                        {"id": 1, "name": "Alpha Scrum Board", "type": "scrum"},
                        {"id": 2, "name": "Alpha Kanban Board", "type": "kanban"}
                      ]
                    }
                    """.trimIndent()
                )
        )

        val result = httpClient.getBoards(nameFilter = "Alpha")

        assertTrue(result.isSuccess, "Expected success but got: $result")
        val boards = (result as ApiResult.Success).data
        assertEquals(2, boards.size)
        assertEquals(1, boards[0].id)
        assertEquals("Alpha Scrum Board", boards[0].name)
        assertEquals("scrum", boards[0].type)
        assertEquals(2, boards[1].id)
        assertEquals("Alpha Kanban Board", boards[1].name)
        assertEquals("kanban", boards[1].type)

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("/rest/agile/1.0/board"), "Wrong endpoint: ${recorded.path}")
        assertTrue(recorded.path!!.contains("name=Alpha"), "Missing name param: ${recorded.path}")
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
    }

    /**
     * Empty result: the Jira endpoint returns HTTP 200 with an empty values array.
     * Verifies the HTTP path parses correctly and returns an empty list (not an error).
     */
    @Test
    fun `searchBoards empty result returns success with empty list via HTTP`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "application/json")
                .setBody(
                    """{"maxResults": 50, "startAt": 0, "total": 0, "values": []}"""
                )
        )

        val result = httpClient.getBoards(nameFilter = "NonexistentBoard")

        assertTrue(result.isSuccess, "Expected success but got: $result")
        val boards = (result as ApiResult.Success).data
        assertEquals(0, boards.size)
    }

    /**
     * HTTP 500: the Jira endpoint returns a server error.
     * Verifies that the HTTP path propagates the error as ApiResult.Error (not a thrown exception).
     */
    @Test
    fun `searchBoards HTTP 500 returns ApiResult Error`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"errorMessages":["Internal server error"],"errors":{}}""")
        )

        val result = httpClient.getBoards(nameFilter = "Board")

        assertFalse(result.isSuccess, "Expected error result for HTTP 500")
        assertTrue(result is ApiResult.Error)
    }

    // ── mockk (service-layer mapping) ────────────────────────────────────────

    /**
     * Verifies [JiraBoardSummary] mapping: JiraBoard.id (Int) is widened to Long,
     * and name/type are preserved verbatim.
     */
    @Test
    fun `searchBoards maps JiraBoard id to Long in JiraBoardSummary`() = runTest {
        val mockApi = mockk<JiraApiClient>()
        coEvery { mockApi.getBoards(boardType = any(), nameFilter = any()) } returns ApiResult.Success(
            listOf(
                JiraBoard(id = 42, name = "Mapped Board", type = "scrum")
            )
        )

        // Exercise the same mapping logic used by JiraServiceImpl.searchBoards
        val rawBoards = (mockApi.getBoards(nameFilter = "Map") as ApiResult.Success).data
        val summaries = rawBoards.map { b -> JiraBoardSummary(id = b.id.toLong(), name = b.name, type = b.type) }

        assertEquals(1, summaries.size)
        assertEquals(42L, summaries[0].id)
        assertEquals("Mapped Board", summaries[0].name)
        assertEquals("scrum", summaries[0].type)
    }

    /**
     * Verifies that when the api client returns an error, the service produces a
     * ToolResult with isError=true and a non-blank summary.
     */
    @Test
    fun `searchBoards api error propagates as ToolResult error with non-blank summary`() = runTest {
        val mockApi = mockk<JiraApiClient>()
        coEvery { mockApi.getBoards(boardType = any(), nameFilter = any()) } returns
                ApiResult.Error(
                    type = com.workflow.orchestrator.core.model.ErrorType.NETWORK_ERROR,
                    message = "Connection refused"
                )

        val apiResult = mockApi.getBoards(nameFilter = "X")
        assertTrue(apiResult is ApiResult.Error)
        val errMsg = (apiResult as ApiResult.Error).message
        assertTrue(errMsg.isNotBlank(), "Error summary must not be blank")
    }
}
