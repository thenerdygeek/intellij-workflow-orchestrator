package com.workflow.orchestrator.jira.api

import com.intellij.testFramework.LoggedErrorProcessorEnabler
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.core.model.jira.TransitionInput
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
        assertTrue(recorded.path!!.contains("/rest/agile/1.0/board"))
        assertTrue(recorded.path!!.contains("type=scrum"))
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
        assertTrue(recorded.path!!.startsWith("/rest/agile/1.0/board"))
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
    fun `getTransitions returns available transitions with expand by default`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"transitions":[{"id":"21","name":"In Progress","to":{"id":"3","name":"In Progress","statusCategory":{"key":"indeterminate"}}}]}"""
            )
        )

        val result = client.getTransitions("PROJ-123")

        assertTrue(result.isSuccess)
        val transitions = (result as ApiResult.Success).data
        assertEquals(1, transitions.size)
        assertEquals("In Progress", transitions[0].name)

        val recorded = server.takeRequest()
        assertEquals("/rest/api/2/issue/PROJ-123/transitions?expand=transitions.fields", recorded.path)
    }

    @Test
    fun `getTransitions without expand omits query param`() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"transitions":[{"id":"21","name":"In Progress","to":{"id":"3","name":"In Progress","statusCategory":{"key":"indeterminate"}}}]}"""
            )
        )

        val result = client.getTransitions("PROJ-123", expandFields = false)

        assertTrue(result.isSuccess)
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

        val result = client.transitionIssue("PROJ-123", TransitionInput("21", emptyMap(), null))

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/rest/api/2/issue/PROJ-123/transitions", recorded.path)
        assertTrue(recorded.body.readUtf8().contains(""""id":"21""""))
    }

    // ── R-RISK-1: HTML content-type guard ─────────────────────────────────

    @Test
    fun `200 with text-html content-type is mapped to AUTH_FAILED (login redirect)`() = runTest {
        // When auth expires, Jira responds 302 -> /login.jsp?permissionViolation=true.
        // With followRedirects=true the client receives a 200 + HTML body, which we treat as
        // an auth failure rather than a parse error.
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setHeader("Content-Type", "text/html; charset=utf-8")
                .setBody("<html><head><title>Log In</title></head><body>Please log in</body></html>")
        )

        val result = client.getCurrentUser()

        assertTrue(result.isError, "HTML on a 200 should be mapped to an error")
        val err = result as ApiResult.Error
        assertEquals(ErrorType.AUTH_FAILED, err.type)
        assertTrue(err.message.contains("HTML", ignoreCase = true),
            "Error message should mention HTML, got: ${err.message}")
    }

    // ── R-SWAP-1: validateTicketKeys uses POST body, no chunking ──────────

    @Test
    fun `validateTicketKeys uses POST with key in jql body and parses response`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"expand":"names,schema","startAt":0,"maxResults":2,"total":2,
                   "issues":[
                     {"id":"1","key":"PROJ-1","fields":{"summary":"First","status":{"name":"Open"}}},
                     {"id":"2","key":"PROJ-2","fields":{"summary":"Second","status":{"name":"Done"}}}
                   ]}"""
            )
        )

        val result = client.validateTicketKeys(listOf("PROJ-1", "PROJ-2"))

        assertTrue(result.isSuccess)
        val map = (result as ApiResult.Success).data
        assertEquals(2, map.size)
        assertEquals("First", map["PROJ-1"]?.summary)
        assertEquals("Open", map["PROJ-1"]?.status)
        assertEquals("Second", map["PROJ-2"]?.summary)

        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/rest/api/2/search", recorded.path)
        val body = recorded.body.readUtf8()
        assertTrue(body.contains(""""jql":"key in (PROJ-1,PROJ-2)""""),
            "Expected JQL key-in body, got: $body")
        assertTrue(body.contains(""""fields":["summary","status"]"""),
            "Expected fields=[summary,status], got: $body")
    }

    @Test
    fun `validateTicketKeys empty list short-circuits with no HTTP call`() = runTest {
        val result = client.validateTicketKeys(emptyList())

        assertTrue(result.isSuccess)
        assertTrue((result as ApiResult.Success).data.isEmpty())
        assertEquals(0, server.requestCount)
    }

    // ── New endpoint coverage ─────────────────────────────────────────────

    @Test
    fun `getMyPermissions GETs the project-scoped path and parses permissions`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"permissions":{
                   "CREATE_ISSUES":{"id":"11","key":"CREATE_ISSUES","name":"Create Issues","type":"PROJECT","havePermission":true},
                   "BULK_CHANGE":{"id":"33","key":"BULK_CHANGE","name":"Bulk Change","type":"GLOBAL","havePermission":true},
                   "VIEW_WORKFLOW_READONLY":{"id":"45","key":"VIEW_WORKFLOW_READONLY","name":"View Read-Only Workflow","type":"PROJECT","havePermission":true,"deprecatedKey":true}
                   }}"""
            )
        )

        val result = client.getMyPermissions("MYPROJ")

        assertTrue(result.isSuccess)
        val perms = (result as ApiResult.Success).data.permissions
        assertEquals(3, perms.size, "Client returns raw payload; service-layer filters deprecated.")
        assertTrue(perms["VIEW_WORKFLOW_READONLY"]?.deprecatedKey == true)

        val recorded = server.takeRequest()
        assertEquals("GET", recorded.method)
        assertTrue(recorded.path!!.startsWith("/rest/api/2/mypermissions"),
            "Expected /rest/api/2/mypermissions, got: ${recorded.path}")
        assertTrue(recorded.path!!.contains("projectKey=MYPROJ"),
            "Expected projectKey=MYPROJ, got: ${recorded.path}")
    }

    @Test
    fun `getMyPermissions without projectKey omits query string`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"permissions":{}}""")
        )

        client.getMyPermissions(null)

        val recorded = server.takeRequest()
        assertEquals("/rest/api/2/mypermissions", recorded.path)
    }

    @Test
    fun `getFields parses flat array response`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """[
                   {"id":"summary","name":"Summary","custom":false,"schema":{"type":"string","system":"summary"}},
                   {"id":"customfield_10001","name":"AC","custom":true,"schema":{"type":"string","custom":"…"}}
                   ]"""
            )
        )

        val result = client.getFields()

        assertTrue(result.isSuccess)
        val fields = (result as ApiResult.Success).data
        assertEquals(2, fields.size)
        assertFalse(fields[0].custom)
        assertTrue(fields[1].custom)
        assertEquals("string", fields[0].schema?.type)

        val recorded = server.takeRequest()
        assertEquals("/rest/api/2/field", recorded.path)
    }

    @Test
    fun `getRemoteLinks parses application and object sub-objects`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """[{"id":1234,"relationship":"links to",
                    "application":{"type":"com.atlassian.confluence","name":"Confluence"},
                    "object":{"url":"https://wiki.example/page","title":"Design Doc"}}]"""
            )
        )

        val result = client.getRemoteLinks("PROJ-1")

        assertTrue(result.isSuccess)
        val links = (result as ApiResult.Success).data
        assertEquals(1, links.size)
        assertEquals(1234L, links[0].id)
        assertEquals("com.atlassian.confluence", links[0].application?.type)
        assertEquals("https://wiki.example/page", links[0].`object`?.url)
        assertEquals("Design Doc", links[0].`object`?.title)

        val recorded = server.takeRequest()
        assertEquals("/rest/api/2/issue/PROJ-1/remotelink", recorded.path)
    }

    @Test
    fun `getWatchers parses watchCount and watchers list`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"watchCount":2,"isWatching":true,"watchers":[
                   {"name":"jdoe","displayName":"Jane Doe","emailAddress":"jdoe@example.com"},
                   {"name":"jsmith","displayName":"John Smith"}]}"""
            )
        )

        val result = client.getWatchers("PROJ-1")

        assertTrue(result.isSuccess)
        val w = (result as ApiResult.Success).data
        assertEquals(2, w.watchCount)
        assertTrue(w.isWatching)
        assertEquals(2, w.watchers.size)
        assertEquals("Jane Doe", w.watchers[0].displayName)
    }

    @Test
    fun `addWatcher POSTs username as a JSON string body`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = client.addWatcher("PROJ-1", "jdoe")

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("POST", recorded.method)
        assertEquals("/rest/api/2/issue/PROJ-1/watchers", recorded.path)
        // The body is literally "jdoe" with quotes — that's the documented Jira shape.
        assertEquals("\"jdoe\"", recorded.body.readUtf8())
    }

    @Test
    fun `removeWatcher DELETEs with username query param`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = client.removeWatcher("PROJ-1", "jdoe")

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("DELETE", recorded.method)
        assertTrue(recorded.path!!.startsWith("/rest/api/2/issue/PROJ-1/watchers"),
            "Expected watchers path, got: ${recorded.path}")
        assertTrue(recorded.path!!.contains("username=jdoe"),
            "Expected username=jdoe, got: ${recorded.path}")
    }

    @Test
    fun `getMyselfExpanded requests expand=groups,applicationRoles and parses items`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"name":"jdoe","displayName":"Jane Doe","emailAddress":"jdoe@example.com",
                   "groups":{"size":2,"items":[{"name":"jira-users"},{"name":"dev-team"}]},
                   "applicationRoles":{"size":1,"items":[{"name":"jira-software-users"}]}}"""
            )
        )

        val result = client.getMyselfExpanded()

        assertTrue(result.isSuccess)
        val m = (result as ApiResult.Success).data
        assertEquals("jdoe", m.name)
        assertEquals(2, m.groups?.items?.size)
        assertEquals("dev-team", m.groups?.items?.get(1)?.name)
        assertEquals(1, m.applicationRoles?.items?.size)

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("expand=groups,applicationRoles"),
            "Expected expand=groups,applicationRoles, got: ${recorded.path}")
    }

    @Test
    fun `getIssueSuggestions uses showSubTasks=true and parses sections`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"sections":[
                   {"id":"hs","label":"History Search","sub":"…","issues":[
                     {"key":"PROJ-1","keyHtml":"<b>PROJ-</b>1","img":"/avatar","summary":"<b>PROJ</b> issue 1","summaryText":"Issue 1"}
                   ]},
                   {"id":"cs","label":"Current Search","sub":"…","issues":[
                     {"key":"PROJ-2","summary":"Issue 2","summaryText":"Issue 2"}
                   ]}
                   ]}"""
            )
        )

        val result = client.getIssueSuggestions("PROJ")

        assertTrue(result.isSuccess)
        val sections = (result as ApiResult.Success).data.sections
        assertEquals(2, sections.size)
        assertEquals("hs", sections[0].id)
        assertEquals("PROJ-1", sections[0].issues[0].key)
        assertEquals("Issue 1", sections[0].issues[0].summaryText)

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("showSubTasks=true"))
        assertTrue(recorded.path!!.contains("showSubTaskParent=true"))
        assertTrue(recorded.path!!.contains("query=PROJ"))
    }

    @Test
    fun `getFavouriteFilters parses filter list`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """[{"id":"91483","name":"My Sprint","description":"","jql":"sprint=…",
                    "viewUrl":"https://jira.example/issues/?filter=91483",
                    "owner":{"name":"jdoe","displayName":"Jane Doe"}}]"""
            )
        )

        val result = client.getFavouriteFilters()

        assertTrue(result.isSuccess)
        val filters = (result as ApiResult.Success).data
        assertEquals(1, filters.size)
        assertEquals("91483", filters[0].id)
        assertEquals("My Sprint", filters[0].name)

        val recorded = server.takeRequest()
        assertEquals("/rest/api/2/filter/favourite", recorded.path)
    }

    @Test
    fun `getFilter parses single filter detail`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"91483","name":"My Sprint","jql":"assignee=currentUser()",
                    "viewUrl":"https://jira.example/issues/?filter=91483",
                    "owner":{"name":"jdoe","displayName":"Jane Doe"}}"""
            )
        )

        val result = client.getFilter(91483L)

        assertTrue(result.isSuccess)
        val f = (result as ApiResult.Success).data
        assertEquals("91483", f.id)
        assertEquals("assignee=currentUser()", f.jql)

        val recorded = server.takeRequest()
        assertEquals("/rest/api/2/filter/91483", recorded.path)
    }

    @Test
    fun `getIssueWithContextAndChangelog requests both renderedFields and changelog and parses histories`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"1","key":"PROJ-1","fields":{"summary":"S","status":{"name":"Open"}},
                    "renderedFields":{"description":"<p>html</p>"},
                    "changelog":{"histories":[
                      {"id":"h1","author":{"name":"jdoe","displayName":"Jane"},"created":"2026-01-01T00:00:00.000+0000",
                       "items":[{"field":"status","fieldtype":"jira","fromString":"Open","toString":"In Progress"}]}
                    ]}}"""
            )
        )

        val result = client.getIssueWithContextAndChangelog("PROJ-1")

        assertTrue(result.isSuccess)
        val data = (result as ApiResult.Success).data
        assertEquals("PROJ-1", data.key)
        assertEquals("<p>html</p>", data.renderedFields?.description)
        assertEquals(1, data.changelog?.histories?.size)
        val item = data.changelog?.histories?.first()?.items?.first()
        assertEquals("status", item?.field)
        assertEquals("Open", item?.fromString)
        assertEquals("In Progress", item?.toString)

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("expand=renderedFields,changelog"),
            "Expected expand=renderedFields,changelog, got: ${recorded.path}")
    }
}
