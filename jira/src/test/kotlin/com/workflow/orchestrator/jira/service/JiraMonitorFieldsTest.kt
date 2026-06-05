package com.workflow.orchestrator.jira.service

import com.intellij.openapi.project.Project
import com.intellij.testFramework.LoggedErrorProcessorEnabler
import com.workflow.orchestrator.jira.api.JiraApiClient
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

/**
 * Mock-HTTP integration tests pinning the monitor-critical JSON→model parse paths for
 * [JiraServiceImpl.getTicket], [JiraServiceImpl.getAvailableSprints], and
 * [JiraServiceImpl.getSprintIssues].
 *
 * Monitor-relevant fields asserted:
 * - [getTicket]: [JiraTicketData.status], [JiraTicketData.assignee] (nullable)
 * - [getAvailableSprints]: [SprintData.id], [SprintData.state] ("active"/"closed")
 * - [getSprintIssues]: [JiraTicketData.key], [JiraTicketData.status]
 */
@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class JiraMonitorFieldsTest {

    private val project: Project = mockk(relaxed = true)
    private lateinit var server: MockWebServer
    private lateinit var apiClient: JiraApiClient
    private lateinit var service: JiraServiceImpl

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        apiClient = JiraApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" }
        )
        service = JiraServiceImpl(project).also { it.testClient = apiClient }
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    // ── getTicket: status + assignee ─────────────────────────────────────────────
    //
    // getTicket launches getIssue and getTransitions in parallel (async{} in coroutineScope).
    // Parallel IO requests arrive at MockWebServer in non-deterministic order, so FIFO
    // enqueue is not reliable here.  We use a URL-routing Dispatcher (same pattern as
    // JiraServiceImplCommentAndWorklogTest.pathDispatcher) to route each request to the
    // correct response regardless of arrival order.

    /** Routes /issue/{key}/transitions → transitionsBody, /issue/{key} → issueBody. */
    private fun ticketDispatcher(issueBody: String, transitionsBody: String): Dispatcher =
        object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return when {
                    path.contains("/transitions") ->
                        MockResponse().setHeader("Content-Type", "application/json")
                            .setBody(transitionsBody)
                    path.contains("/issue/") ->
                        MockResponse().setHeader("Content-Type", "application/json")
                            .setBody(issueBody)
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }

    @Test
    fun `getTicket parses status and non-null assignee from realistic Jira DC JSON`() = runTest {
        // Use a URL-routing dispatcher so getIssue and getTransitions each get their own
        // response regardless of which async{} block hits MockWebServer first.
        server.dispatcher = ticketDispatcher(
            issueBody = """{
              "id": "10042",
              "key": "PROJ-42",
              "self": "https://jira.example.com/rest/api/2/issue/10042",
              "fields": {
                "summary": "Fix null pointer in UserService",
                "status": {"id": "3", "name": "In Progress"},
                "issuetype": {"id": "1", "name": "Bug", "subtask": false},
                "priority": {"id": "2", "name": "High"},
                "assignee": {"displayName": "Alice Smith", "emailAddress": "alice@example.com", "name": "asmith"},
                "reporter": {"displayName": "Bob Jones", "emailAddress": "bob@example.com", "name": "bjones"},
                "description": "NPE occurs when user is null",
                "labels": ["backend", "critical"],
                "issuelinks": [],
                "subtasks": [],
                "attachment": [],
                "closedSprints": []
              }
            }""",
            transitionsBody = """{"transitions":[{"id":"11","name":"Done","to":{"id":"10001","name":"Done"}}]}"""
        )

        val result = service.getTicket("PROJ-42")

        assertFalse(result.isError, "Expected success; got: ${result.summary}")
        val ticket = result.data!!

        // Monitor-critical fields
        assertEquals("In Progress", ticket.status, "status must be 'In Progress'")
        assertEquals("Alice Smith", ticket.assignee, "assignee must be 'Alice Smith' (displayName)")
        assertEquals("PROJ-42", ticket.key)
        assertEquals("Fix null pointer in UserService", ticket.summary)
    }

    @Test
    fun `getTicket parses status with null assignee when ticket is unassigned`() = runTest {
        server.dispatcher = ticketDispatcher(
            issueBody = """{
              "id": "10043",
              "key": "PROJ-43",
              "self": "https://jira.example.com/rest/api/2/issue/10043",
              "fields": {
                "summary": "Unassigned task",
                "status": {"id": "1", "name": "Open"},
                "issuetype": {"id": "2", "name": "Task", "subtask": false},
                "assignee": null,
                "reporter": {"displayName": "Carol Dev", "name": "carol"},
                "issuelinks": [],
                "subtasks": [],
                "attachment": [],
                "closedSprints": []
              }
            }""",
            transitionsBody = """{"transitions":[]}"""
        )

        val result = service.getTicket("PROJ-43")

        assertFalse(result.isError)
        val ticket = result.data!!
        assertEquals("Open", ticket.status)
        assertNull(ticket.assignee, "assignee must be null for an unassigned ticket")
        assertEquals("PROJ-43", ticket.key)
    }

    @Test
    fun `getTicket surfaces isError on 404`() = runTest {
        // Both issue and transitions get 404; transitions failure is swallowed, issue 404 → isError.
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(404)
        }

        val result = service.getTicket("PROJ-999")

        assertTrue(result.isError)
        assertTrue(result.summary.contains("PROJ-999"))
    }

    @Test
    fun `getTicket surfaces isError on 401 auth failure`() = runTest {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                MockResponse().setResponseCode(401)
        }

        val result = service.getTicket("PROJ-1")

        assertTrue(result.isError)
    }

    // ── getAvailableSprints: id + state ───────────────────────────────────────────

    @Test
    fun `getAvailableSprints parses active sprint id and state`() = runTest {
        // getAvailableSprints calls getActiveSprints first, then getClosedSprints.
        // Active sprints endpoint: GET /rest/agile/1.0/board/{id}/sprint?state=active
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{
                  "maxResults": 50,
                  "startAt": 0,
                  "isLast": true,
                  "values": [
                    {
                      "id": 101,
                      "name": "Sprint 2026-Q1",
                      "state": "active",
                      "startDate": "2026-01-05T09:00:00.000Z",
                      "endDate": "2026-01-19T09:00:00.000Z",
                      "originBoardId": 7
                    }
                  ]
                }"""
            )
        )
        // Closed sprints endpoint: GET /rest/agile/1.0/board/{id}/sprint?state=closed
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{
                  "maxResults": 50,
                  "startAt": 0,
                  "isLast": true,
                  "values": [
                    {
                      "id": 98,
                      "name": "Sprint 2025-Q4",
                      "state": "closed",
                      "startDate": "2025-12-08T09:00:00.000Z",
                      "endDate": "2025-12-22T09:00:00.000Z",
                      "originBoardId": 7
                    },
                    {
                      "id": 95,
                      "name": "Sprint 2025-Q3",
                      "state": "closed",
                      "startDate": "2025-11-10T09:00:00.000Z",
                      "endDate": "2025-11-24T09:00:00.000Z",
                      "originBoardId": 7
                    }
                  ]
                }"""
            )
        )

        val result = service.getAvailableSprints(boardId = 7)

        assertFalse(result.isError, "Expected success; got: ${result.summary}")
        val sprints = result.data!!
        assertEquals(3, sprints.size)

        // Monitor-critical fields: id + state
        val active = sprints.first { it.id == 101 }
        assertEquals("active", active.state)
        assertEquals("Sprint 2026-Q1", active.name)

        val closed1 = sprints.first { it.id == 98 }
        assertEquals("closed", closed1.state)

        val closed2 = sprints.first { it.id == 95 }
        assertEquals("closed", closed2.state)
    }

    @Test
    fun `getAvailableSprints returns empty list gracefully when both endpoints return empty`() = runTest {
        // Both active and closed sprint calls return empty
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"maxResults":50,"startAt":0,"isLast":true,"values":[]}"""
            )
        )
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"maxResults":50,"startAt":0,"isLast":true,"values":[]}"""
            )
        )

        val result = service.getAvailableSprints(boardId = 7)

        assertFalse(result.isError)
        assertTrue(result.data!!.isEmpty())
    }

    // ── getSprintIssues: key + status ─────────────────────────────────────────────

    @Test
    fun `getSprintIssues parses issue key and status for each issue in sprint`() = runTest {
        // GET /rest/agile/1.0/sprint/{sprintId}/issue
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{
                  "maxResults": 200,
                  "startAt": 0,
                  "total": 2,
                  "issues": [
                    {
                      "id": "10010",
                      "key": "PROJ-10",
                      "self": "https://jira.example.com/rest/api/2/issue/10010",
                      "fields": {
                        "summary": "Implement login feature",
                        "status": {"id": "3", "name": "In Progress"},
                        "issuetype": {"id": "1", "name": "Story", "subtask": false},
                        "assignee": {"displayName": "Alice Smith", "name": "asmith"},
                        "reporter": {"displayName": "PM User", "name": "pm"},
                        "issuelinks": [],
                        "subtasks": [],
                        "attachment": [],
                        "closedSprints": []
                      }
                    },
                    {
                      "id": "10011",
                      "key": "PROJ-11",
                      "self": "https://jira.example.com/rest/api/2/issue/10011",
                      "fields": {
                        "summary": "Fix regression in payment module",
                        "status": {"id": "10001", "name": "Done"},
                        "issuetype": {"id": "2", "name": "Bug", "subtask": false},
                        "assignee": null,
                        "reporter": {"displayName": "QA User", "name": "qa"},
                        "issuelinks": [],
                        "subtasks": [],
                        "attachment": [],
                        "closedSprints": []
                      }
                    }
                  ]
                }"""
            )
        )

        val result = service.getSprintIssues(sprintId = 101)

        assertFalse(result.isError, "Expected success; got: ${result.summary}")
        val tickets = result.data!!
        assertEquals(2, tickets.size)

        // Monitor-critical fields: key + status
        val first = tickets[0]
        assertEquals("PROJ-10", first.key)
        assertEquals("In Progress", first.status)
        assertEquals("Alice Smith", first.assignee)

        val second = tickets[1]
        assertEquals("PROJ-11", second.key)
        assertEquals("Done", second.status)
        assertNull(second.assignee)
    }

    @Test
    fun `getSprintIssues returns empty list when sprint has no issues`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"maxResults":200,"startAt":0,"total":0,"issues":[]}"""
            )
        )

        val result = service.getSprintIssues(sprintId = 999)

        assertFalse(result.isError)
        assertTrue(result.data!!.isEmpty())
    }

    @Test
    fun `getSprintIssues surfaces isError on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = service.getSprintIssues(sprintId = 42)

        assertTrue(result.isError)
    }
}
