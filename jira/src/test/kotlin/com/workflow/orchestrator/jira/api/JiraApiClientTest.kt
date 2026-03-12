package com.workflow.orchestrator.jira.api

import com.intellij.testFramework.LoggedErrorProcessorEnabler
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

// LoggedErrorProcessorEnabler.DoNoRethrowErrors suppresses the IntelliJ test framework's
// behaviour of converting log.error() calls into test failures. This is needed because
// JiraApiClient deliberately calls log.error() on 4xx/5xx responses, which is correct
// production behaviour but would otherwise cause test failures unrelated to assertions.
@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class JiraApiClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: JiraApiClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = JiraApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" }
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getBoards returns parsed boards`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"maxResults":50,"startAt":0,"total":1,"values":[{"id":1,"name":"My Scrum Board","type":"scrum","location":{"projectId":10001,"projectName":"My Project","projectKey":"PROJ"}}]}"""
            )
        )

        val result = client.getBoards("scrum")

        assertTrue(result.isSuccess)
        val boards = (result as ApiResult.Success).data
        assertEquals(1, boards.size)
        assertEquals("My Scrum Board", boards[0].name)

        val recorded = server.takeRequest()
        assertEquals("/rest/agile/1.0/board?type=scrum", recorded.path)
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun `getBoards without type filter omits query param`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"maxResults":50,"startAt":0,"total":1,"values":[{"id":1,"name":"My Scrum Board","type":"scrum"}]}"""
            )
        )

        client.getBoards()

        val recorded = server.takeRequest()
        assertEquals("/rest/agile/1.0/board", recorded.path)
    }

    @Test
    fun `getActiveSprints returns active sprints for board`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"maxResults":50,"startAt":0,"values":[{"id":42,"name":"Sprint 14","state":"active"}]}"""
            )
        )

        val result = client.getActiveSprints(boardId = 1)

        assertTrue(result.isSuccess)
        val sprints = (result as ApiResult.Success).data
        assertEquals(1, sprints.size)
        assertEquals("Sprint 14", sprints[0].name)

        val recorded = server.takeRequest()
        assertEquals("/rest/agile/1.0/board/1/sprint?state=active", recorded.path)
    }

    @Test
    fun `getSprintIssues returns assigned issues`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"maxResults":50,"startAt":0,"total":1,"issues":[{"id":"10001","key":"PROJ-123","fields":{"summary":"Fix login","status":{"name":"In Progress"}}}]}"""
            )
        )

        val result = client.getSprintIssues(sprintId = 42)

        assertTrue(result.isSuccess)
        val issues = (result as ApiResult.Success).data
        assertEquals(1, issues.size)
        assertEquals("PROJ-123", issues[0].key)

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.startsWith("/rest/agile/1.0/sprint/42/issue"))
        assertTrue(recorded.path!!.contains("assignee"))
    }

    @Test
    fun `getTransitions returns available transitions`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"transitions":[{"id":"21","name":"In Progress","to":{"name":"In Progress"}}]}"""
            )
        )

        val result = client.getTransitions("PROJ-123")

        assertTrue(result.isSuccess)
        val transitions = (result as ApiResult.Success).data
        assertEquals(1, transitions.size)
        assertEquals("In Progress", transitions[0].name)

        val recorded = server.takeRequest()
        assertEquals("/rest/api/2/issue/PROJ-123/transitions", recorded.path)
    }

    @Test
    fun `returns AUTH_FAILED on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.getBoards()

        assertTrue(result.isError)
        assertEquals(ErrorType.AUTH_FAILED, (result as ApiResult.Error).type)
    }

    @Test
    fun `transitionIssue sends correct POST body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = client.transitionIssue("PROJ-123", "21")

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/rest/api/2/issue/PROJ-123/transitions", recorded.path)
        assertTrue(recorded.body.readUtf8().contains(""""id":"21""""))
    }
}
