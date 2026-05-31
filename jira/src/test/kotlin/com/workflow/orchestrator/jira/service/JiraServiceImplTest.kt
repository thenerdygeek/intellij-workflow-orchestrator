package com.workflow.orchestrator.jira.service

import com.intellij.testFramework.LoggedErrorProcessorEnabler
import com.workflow.orchestrator.jira.api.JiraApiClient
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
 * Tests for [JiraServiceImpl] focusing on the new endpoint methods added in
 * the 2026-05-06 Jira audit (R-PROJ extensions). Uses the [JiraServiceImpl.testClient]
 * seam to drive a real [JiraApiClient] against a [MockWebServer], then asserts
 * on the [com.workflow.orchestrator.core.services.ToolResult] mapping.
 */
@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class JiraServiceImplTest {

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

    // ── getMyPermissions: filters deprecated keys + caches per project ────

    @Test
    fun `getMyPermissions filters deprecatedKey entries before returning to caller`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"permissions":{
                   "EDIT_ISSUES":{"id":"12","key":"EDIT_ISSUES","name":"Edit Issues","havePermission":true},
                   "EDIT_ISSUE":{"id":"12","key":"EDIT_ISSUE","name":"Edit Issue","havePermission":true,"deprecatedKey":true},
                   "CREATE_ISSUES":{"id":"11","key":"CREATE_ISSUES","name":"Create Issues","havePermission":true}
                   }}"""
            )
        )

        val result = service.getMyPermissions("PROJ")

        assertFalse(result.isError)
        assertEquals(2, result.data!!.permissions.size,
            "Deprecated keys must be filtered out; expected EDIT_ISSUES + CREATE_ISSUES only.")
        assertTrue(result.data!!.permissions.containsKey("EDIT_ISSUES"))
        assertTrue(result.data!!.permissions.containsKey("CREATE_ISSUES"))
        assertFalse(result.data!!.permissions.containsKey("EDIT_ISSUE"),
            "Deprecated EDIT_ISSUE must not be present.")
    }

    @Test
    fun `getMyPermissions caches per project key — second call hits cache`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"permissions":{"CREATE_ISSUES":{"id":"11","key":"CREATE_ISSUES","name":"Create","havePermission":true}}}"""
            )
        )

        val first = service.getMyPermissions("PROJ")
        val second = service.getMyPermissions("PROJ")

        assertFalse(first.isError)
        assertFalse(second.isError)
        assertEquals(1, server.requestCount, "Second call must hit the 5-min cache.")
        assertTrue(second.summary.contains("cached", ignoreCase = true),
            "Cached summary should advertise the cache; got: ${second.summary}")
    }

    @Test
    fun `getMyPermissions cache key differs by projectKey vs global`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"permissions":{}}""")
        )
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"permissions":{}}""")
        )

        service.getMyPermissions("PROJ")
        service.getMyPermissions(null)

        assertEquals(2, server.requestCount, "Different cache keys must produce 2 HTTP requests.")
    }

    // ── getFields: caches globally ────────────────────────────────────────

    @Test
    fun `getFields caches result — second call hits cache`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """[{"id":"summary","name":"Summary","custom":false,"schema":{"type":"string"}}]"""
            )
        )

        val first = service.getFields()
        val second = service.getFields()

        assertFalse(first.isError)
        assertFalse(second.isError)
        assertEquals(1, server.requestCount, "Second call must hit the 5-min cache.")
        assertEquals(1, second.data!!.size)
        assertEquals("Summary", second.data!![0].name)
    }

    // ── getRemoteLinks: shape mapping ─────────────────────────────────────

    @Test
    fun `getRemoteLinks pulls applicationType,name and object url,title`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """[{"id":42,"relationship":"links to",
                    "application":{"type":"com.atlassian.confluence","name":"Wiki"},
                    "object":{"url":"https://wiki.example/page","title":"Spec"}}]"""
            )
        )

        val result = service.getRemoteLinks("PROJ-1")

        assertFalse(result.isError)
        assertEquals(1, result.data!!.size)
        val link = result.data!![0]
        assertEquals(42L, link.id)
        assertEquals("com.atlassian.confluence", link.applicationType)
        assertEquals("Wiki", link.applicationName)
        assertEquals("https://wiki.example/page", link.url)
        assertEquals("Spec", link.title)
    }

    @Test
    fun `getRemoteLinks drops entries with no usable URL`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """[
                    {"id":1,"application":{"type":"com.atlassian.confluence","name":"Wiki"},
                     "object":{"url":"https://wiki.example/page","title":"Real"}},
                    {"id":2,"application":{"type":"com.atlassian.confluence","name":"Wiki"},
                     "object":{"title":"No URL"}},
                    {"id":3,"application":{"type":"com.atlassian.confluence","name":"Wiki"},
                     "object":{"url":"","title":"Blank URL"}}
                  ]"""
            )
        )

        val result = service.getRemoteLinks("PROJ-1")

        assertFalse(result.isError)
        assertEquals(1, result.data!!.size, "Only the link with a non-blank URL should be emitted.")
        assertEquals(1L, result.data!![0].id)
    }

    // ── getMyselfExpanded: flattens groups.items → list of names ──────────

    @Test
    fun `getMyselfExpanded flattens groups items and applicationRoles items to name lists`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"name":"jdoe","displayName":"Jane","emailAddress":"jdoe@x.com",
                    "groups":{"size":2,"items":[{"name":"jira-users"},{"name":"dev"}]},
                    "applicationRoles":{"size":1,"items":[{"name":"jira-software-users"}]}}"""
            )
        )

        val result = service.getMyselfExpanded()

        assertFalse(result.isError)
        assertEquals(listOf("jira-users", "dev"), result.data!!.groups)
        assertEquals(listOf("jira-software-users"), result.data!!.applicationRoles)
    }

    // ── getIssueSuggestions: flattens across sections ─────────────────────

    @Test
    fun `getIssueSuggestions flattens across sections regardless of section id`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"sections":[
                   {"id":"hs","label":"History","sub":"…","issues":[
                     {"key":"PROJ-1","summary":"<b>S</b>1","summaryText":"S1"}
                   ]},
                   {"id":"cs","label":"Current","sub":"…","issues":[
                     {"key":"PROJ-2","summary":"S2","summaryText":"S2"},
                     {"key":"PROJ-3","summary":"S3","summaryText":"S3"}
                   ]}
                   ]}"""
            )
        )

        val result = service.getIssueSuggestions("PROJ")

        assertFalse(result.isError)
        assertEquals(3, result.data!!.size,
            "All issues across all sections must be returned, not just one section.")
        assertEquals(listOf("PROJ-1", "PROJ-2", "PROJ-3"), result.data!!.map { it.key })
        assertEquals("S1", result.data!![0].summaryText)
    }

    // ── getFavouriteFilters / getFilter mapping ───────────────────────────

    @Test
    fun `getFavouriteFilters maps owner displayName and parses id as Long`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """[{"id":"91483","name":"My Sprint","description":"",
                    "jql":"sprint=…","viewUrl":"https://x.com/issues/?filter=91483",
                    "owner":{"name":"jdoe","displayName":"Jane Doe"}}]"""
            )
        )

        val result = service.getFavouriteFilters()

        assertFalse(result.isError)
        assertEquals(1, result.data!!.size)
        val f = result.data!![0]
        assertEquals(91483L, f.id)
        assertEquals("My Sprint", f.name)
        assertEquals("Jane Doe", f.owner)
        assertNull(f.description, "Empty description string should map to null.")
        assertEquals("sprint=…", f.jql)
    }

    @Test
    fun `getFilter detail always carries jql`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"91483","name":"My Sprint",
                    "jql":"assignee=currentUser()",
                    "viewUrl":"https://x.com/issues/?filter=91483",
                    "owner":{"name":"jdoe","displayName":"Jane Doe"}}"""
            )
        )

        val result = service.getFilter(91483L)

        assertFalse(result.isError)
        assertEquals("assignee=currentUser()", result.data!!.jql)
    }

    @Test
    fun `getFilter surfaces isError when the response id cannot be parsed`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"not-a-number","name":"Broken","jql":"x=y"}"""
            )
        )

        val result = service.getFilter(91483L)

        assertTrue(result.isError, "Malformed id should not be silently masked as success.")
        assertEquals(91483L, result.data!!.id)
    }

    // ── getTicketHistory: flattens (history, item) pairs ──────────────────

    @Test
    fun `getTicketHistory flattens histories and items into per-field rows`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"1","key":"PROJ-1","fields":{"summary":"S","status":{"name":"Open"}},
                    "renderedFields":null,
                    "changelog":{"histories":[
                      {"id":"h1","author":{"name":"jdoe","displayName":"Jane"},
                       "created":"2026-01-01T00:00:00.000+0000","items":[
                         {"field":"status","fieldtype":"jira","fromString":"Open","toString":"In Progress"},
                         {"field":"assignee","fieldtype":"jira","fromString":null,"toString":"Jane"}
                       ]},
                      {"id":"h2","author":{"name":"jsmith","displayName":"John"},
                       "created":"2026-01-02T00:00:00.000+0000","items":[
                         {"field":"resolution","fieldtype":"jira","fromString":null,"toString":"Done"}
                       ]}
                    ]}}"""
            )
        )

        val result = service.getTicketHistory("PROJ-1")

        assertFalse(result.isError)
        assertEquals(3, result.data!!.size,
            "2 histories with 2+1 items must flatten to 3 entries.")
        assertEquals("Jane", result.data!![0].actorDisplayName)
        assertEquals("status", result.data!![0].field)
        assertEquals("Open", result.data!![0].oldValue)
        assertEquals("In Progress", result.data!![0].newValue)
        assertEquals("assignee", result.data!![1].field)
        assertEquals("John", result.data!![2].actorDisplayName)
        assertEquals("resolution", result.data!![2].field)
    }

    @Test
    fun `getTicketHistory returns empty list when changelog is missing`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody(
                """{"id":"1","key":"PROJ-1","fields":{"summary":"S","status":{"name":"Open"}}}"""
            )
        )

        val result = service.getTicketHistory("PROJ-1")

        assertFalse(result.isError)
        assertTrue(result.data.isNullOrEmpty())
    }

    // ── addWatcher / removeWatcher mapping ────────────────────────────────

    @Test
    fun `addWatcher returns success summary`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = service.addWatcher("PROJ-1", "jdoe")

        assertFalse(result.isError)
        assertTrue(result.summary.contains("jdoe"))
        assertTrue(result.summary.contains("PROJ-1"))
    }

    @Test
    fun `removeWatcher returns success summary`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = service.removeWatcher("PROJ-1", "jdoe")

        assertFalse(result.isError)
        assertTrue(result.summary.contains("jdoe"))
    }

    // ── getSprintIssues: current-user filter routes through the probed server-side JQL ──

    @Test
    fun `getSprintIssues with currentUserOnly true requests server-side assignee=currentUser()`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody("""{"issues":[]}""")
        )

        val result = service.getSprintIssues(12345, currentUserOnly = true)

        assertFalse(result.isError)
        val decoded = java.net.URLDecoder.decode(server.takeRequest().path ?: "", "UTF-8")
        assertTrue(
            decoded.contains("assignee=currentUser()"),
            "currentUserOnly=true must apply the probed Sprint-tab server-side filter; path=$decoded"
        )
    }

    @Test
    fun `getSprintIssues default returns the whole sprint with no current-user filter`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json").setBody("""{"issues":[]}""")
        )

        val result = service.getSprintIssues(12345)

        assertFalse(result.isError)
        val decoded = java.net.URLDecoder.decode(server.takeRequest().path ?: "", "UTF-8")
        assertFalse(
            decoded.contains("assignee=currentUser()"),
            "Default (currentUserOnly=false) must not inject a current-user filter; path=$decoded"
        )
    }

    // ── getWorklogs: pages past the first 20 and keeps the author username ──

    @Test
    fun `getWorklogs pages to exhaustion instead of capping at 20`() = runTest {
        // Page 1: 100 worklogs, total=102 → must fetch page 2.
        val page1 = (1..100).joinToString(",") {
            """{"author":{"displayName":"Jane Doe","name":"jdoe"},"timeSpent":"1h","timeSpentSeconds":3600,"started":"2026-05-10T09:00:00.000+0000"}"""
        }
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"total":102,"worklogs":[$page1]}"""))
        val page2 = (1..2).joinToString(",") {
            """{"author":{"displayName":"Jane Doe","name":"jdoe"},"timeSpent":"1h","timeSpentSeconds":3600,"started":"2026-05-11T09:00:00.000+0000"}"""
        }
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json")
            .setBody("""{"total":102,"worklogs":[$page2]}"""))

        val result = service.getWorklogs("PROJ-1")

        assertFalse(result.isError)
        assertEquals(102, result.data!!.size, "all worklogs across both pages must be returned")
        // Second request advances startAt past the first page.
        server.takeRequest()
        assertTrue(server.takeRequest().path!!.contains("startAt=100"))
    }

    @Test
    fun `getWorklogs retains the author username alongside the display name`() = runTest {
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody(
            """{"total":1,"worklogs":[{"author":{"displayName":"Jane Doe","name":"jdoe"},"timeSpent":"1h","timeSpentSeconds":3600,"started":"2026-05-10T09:00:00.000+0000"}]}"""
        ))

        val result = service.getWorklogs("PROJ-1")

        assertFalse(result.isError)
        assertEquals("Jane Doe", result.data!![0].author)
        assertEquals("jdoe", result.data!![0].authorUsername)
    }

    // ── JIRA-COV-7: getWorklogs mid-pagination error path ─────────────────

    @Test
    fun `getWorklogs returns error immediately when second page request fails`() = runTest {
        // Page 1 succeeds: 100 worklogs, total=200 → must fetch page 2
        val page1 = (1..100).joinToString(",") {
            """{"author":{"displayName":"Jane Doe","name":"jdoe"},"timeSpent":"1h","timeSpentSeconds":3600,"started":"2026-05-10T09:00:00.000+0000"}"""
        }
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"total":200,"worklogs":[$page1]}""")
        )
        // Page 2 returns 401 — mid-pagination auth failure
        server.enqueue(MockResponse().setResponseCode(401))

        val result = service.getWorklogs("PROJ-1")

        assertTrue(result.isError,
            "A 401 on the second page must propagate as an error, discarding already-collected page 1 data")
        assertTrue(result.summary.contains("PROJ-1"),
            "Error summary should identify the issue; got: ${result.summary}")
        assertEquals(2, server.requestCount,
            "Exactly two HTTP requests must be issued — page 1 (success) and page 2 (failure)")
    }

    @Test
    fun `getWorklogs returns empty success list when server reports total zero`() = runTest {
        server.enqueue(
            MockResponse().setHeader("Content-Type", "application/json")
                .setBody("""{"total":0,"worklogs":[]}""")
        )

        val result = service.getWorklogs("PROJ-1")

        assertFalse(result.isError, "total=0 with empty worklogs must be a success, not an error")
        assertTrue(result.data.isNullOrEmpty(),
            "total=0 response must yield an empty list, got: ${result.data}")
        assertEquals(1, server.requestCount,
            "Only one HTTP request must be issued when the first page is the last page")
    }
}
