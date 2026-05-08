package com.workflow.orchestrator.jira.service

import com.intellij.testFramework.LoggedErrorProcessorEnabler
import com.workflow.orchestrator.core.model.jira.CommentVisibility
import com.workflow.orchestrator.core.model.jira.VisibilityType
import com.workflow.orchestrator.core.model.jira.WorklogEstimateAdjustment
import com.workflow.orchestrator.jira.api.JiraApiClient
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Service-layer coverage for PR 5 of the 2026-05-07 write-ops audit:
 *
 *  - `addComment(.., visibility)` threads the role/group payload through to the API client.
 *  - `logWork(.., started, adjustEstimate)` formats `started` as `yyyy-MM-dd'T'HH:mm:ss.SSSZ`
 *    and forwards `adjustEstimate=<lowercase>` only when non-AUTO.
 *  - `getCommentVisibilityOptions(projectKey)` flattens the role-name-keyed object + the
 *    groups picker payload, and caches the merged result per project.
 */
@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class JiraServiceImplCommentAndWorklogTest {

    private val project = mockk<com.intellij.openapi.project.Project>(relaxed = true)

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

    /**
     * Routes responses by request path so concurrent /role + /groups/picker fan-out
     * (via async{} in JiraServiceImpl.getCommentVisibilityOptions) doesn't get matched
     * to the wrong response by FIFO enqueue order.
     */
    private fun pathDispatcher(rolesBody: String, groupsBody: String): Dispatcher = object : Dispatcher() {
        override fun dispatch(request: RecordedRequest): MockResponse {
            val path = request.path.orEmpty()
            return when {
                path.contains("/groups/picker") ->
                    MockResponse().setHeader("Content-Type", "application/json").setBody(groupsBody)
                path.endsWith("/role") || path.contains("/role?") ->
                    MockResponse().setHeader("Content-Type", "application/json").setBody(rolesBody)
                else -> MockResponse().setResponseCode(404)
            }
        }
    }

    // ── addComment visibility ────────────────────────────────────────────────

    @Test
    fun `addComment with role visibility serializes lowercase type and surfaces it in summary`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"100"}"""))

        val result = service.addComment(
            "ABC-1",
            body = "ship it",
            visibility = CommentVisibility(VisibilityType.ROLE, "Developers")
        )

        assertFalse(result.isError)
        assertTrue(
            result.summary.contains("role 'Developers'"),
            "Expected summary to mention the visibility; got: ${result.summary}"
        )
        val body = server.takeRequest().body.readUtf8()
        assertTrue(
            body.contains(""""visibility":{"type":"role","value":"Developers"}"""),
            "Expected lowercase 'role' on the wire; got: $body"
        )
    }

    @Test
    fun `addComment without visibility omits the visibility key`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"100"}"""))

        val result = service.addComment("ABC-1", body = "public note", visibility = null)

        assertFalse(result.isError)
        val body = server.takeRequest().body.readUtf8()
        assertFalse(
            body.contains("visibility"),
            "Expected no visibility key when caller passes null; got: $body"
        )
    }

    // ── logWork: started + adjustEstimate ────────────────────────────────────

    @Test
    fun `logWork serializes user-picked started in Jira format`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"77"}"""))

        val started = OffsetDateTime.of(2026, 5, 8, 9, 0, 0, 0, ZoneOffset.UTC)
        val result = service.logWork(
            key = "ABC-1",
            timeSpent = "1h",
            comment = null,
            started = started,
            adjustEstimate = WorklogEstimateAdjustment.AUTO
        )

        assertFalse(result.isError)
        val recorded = server.takeRequest()
        // AUTO → no query param.
        assertEquals("/rest/api/2/issue/ABC-1/worklog", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(
            body.contains(""""started":"2026-05-08T09:00:00.000+0000""""),
            "Expected started in Jira format `yyyy-MM-dd'T'HH:mm:ss.SSSZ`; got: $body"
        )
    }

    @Test
    fun `logWork with NEW adjustEstimate appends adjustEstimate=new query param`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"77"}"""))

        val result = service.logWork(
            key = "ABC-1",
            timeSpent = "1h",
            adjustEstimate = WorklogEstimateAdjustment.NEW
        )

        assertFalse(result.isError)
        val recorded = server.takeRequest()
        assertEquals(
            "/rest/api/2/issue/ABC-1/worklog?adjustEstimate=new",
            recorded.path,
            "Expected adjustEstimate=new query param when caller picks NEW."
        )
    }

    @Test
    fun `logWork with default arguments preserves pre-PR-5 wire shape (no started, no adjustEstimate)`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":"77"}"""))

        // Existing callers (e.g., TimeTrackingCheckinHandlerFactory.afterCommit) must keep
        // working without changes — `started=null` + `adjustEstimate=AUTO` → unchanged shape.
        val result = service.logWork("ABC-1", "30m", comment = null)

        assertFalse(result.isError)
        val recorded = server.takeRequest()
        assertEquals("/rest/api/2/issue/ABC-1/worklog", recorded.path)
        val body = recorded.body.readUtf8()
        assertFalse(body.contains("started"), "Expected no started field; got: $body")
    }

    // ── getCommentVisibilityOptions ──────────────────────────────────────────

    @Test
    fun `getCommentVisibilityOptions merges roles + groups and sorts roles by name`() = runTest {
        // The impl fans /role + /groups/picker out concurrently via async, so a FIFO
        // enqueue() can match responses to the wrong requests. Route by path instead.
        server.dispatcher = pathDispatcher(
            rolesBody = """{
                   "Developers":"https://jira.example/rest/api/2/project/PROJ/role/10001",
                   "Administrators":"https://jira.example/rest/api/2/project/PROJ/role/10002",
                   "Users":"https://jira.example/rest/api/2/project/PROJ/role/10003"
                   }""",
            groupsBody = """{"groups":[{"name":"jira-developers"},{"name":"jira-administrators"}]}""",
        )

        val result = service.getCommentVisibilityOptions("PROJ")

        assertFalse(result.isError)
        val data = result.data!!
        assertEquals(3, data.roles.size, "Expected 3 roles; got: ${data.roles}")
        // Sorted alphabetically: Administrators, Developers, Users.
        assertEquals("Administrators", data.roles[0].name)
        assertEquals("Developers", data.roles[1].name)
        assertEquals("Users", data.roles[2].name)
        // Role id parsed from URL tail.
        assertEquals(10002L, data.roles[0].id)
        assertEquals(10001L, data.roles[1].id)
        // Groups preserved in payload order.
        assertEquals(2, data.groups.size)
        assertEquals("jira-developers", data.groups[0].name)
    }

    @Test
    fun `getCommentVisibilityOptions caches per project — second call hits cache`() = runTest {
        // Concurrent fan-out — same routing fix as the merge test above.
        server.dispatcher = pathDispatcher(
            rolesBody = """{"Developers":"https://jira.example/rest/api/2/project/PROJ/role/10001"}""",
            groupsBody = """{"groups":[{"name":"jira-developers"}]}""",
        )

        val first = service.getCommentVisibilityOptions("PROJ")
        val second = service.getCommentVisibilityOptions("PROJ")

        assertFalse(first.isError)
        assertFalse(second.isError)
        assertEquals(
            2, server.requestCount,
            "First call hits both /role and /groups/picker (=2); cached second call must add no requests."
        )
        assertTrue(
            second.summary.contains("cached", ignoreCase = true),
            "Cached summary should advertise the cache; got: ${second.summary}"
        )
    }

    @Test
    fun `getCommentVisibilityOptions surfaces error when both roles and groups fail`() = runTest {
        // Both endpoints return 500 — but RetryInterceptor enqueues 4 attempts each.
        repeat(8) { server.enqueue(MockResponse().setResponseCode(500)) }

        val result = service.getCommentVisibilityOptions("PROJ")

        assertTrue(result.isError, "Expected error when both upstream calls fail; got: ${result.summary}")
    }
}
