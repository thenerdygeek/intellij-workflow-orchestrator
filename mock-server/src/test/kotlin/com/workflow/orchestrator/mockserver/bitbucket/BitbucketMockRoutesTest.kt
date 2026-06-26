package com.workflow.orchestrator.mockserver.bitbucket

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class BitbucketMockRoutesTest {

    private fun ApplicationTestBuilder.setupBitbucket(
        state: BitbucketState = BitbucketDataFactory.createDefaultState()
    ) {
        application {
            install(ContentNegotiation) { json() }
            routing { bitbucketRoutes { state } }
        }
    }

    // --- User lookup --------------------------------------------------------

    @Test
    fun `GET users returns all users when no filter`() = testApplication {
        setupBitbucket()
        val response = client.get("/rest/api/1.0/users")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val values = body["values"]?.jsonArray ?: fail("no values")
        assertTrue(values.isNotEmpty())
        val names = values.map { it.jsonObject["name"]?.jsonPrimitive?.content }
        assertTrue("mock.user" in names)
    }

    @Test
    fun `GET users with filter returns matching users only`() = testApplication {
        setupBitbucket()
        val response = client.get("/rest/api/1.0/users?filter=jane")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val values = body["values"]?.jsonArray ?: fail("no values")
        assertEquals(1, values.size)
        assertEquals("jane.smith", values[0].jsonObject["name"]?.jsonPrimitive?.content)
    }

    // --- Dashboard PRs ------------------------------------------------------

    @Test
    fun `GET dashboard pull-requests returns authored OPEN PRs`() = testApplication {
        setupBitbucket()
        val response = client.get("/rest/api/1.0/dashboard/pull-requests?role=AUTHOR&state=OPEN")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val values = body["values"]?.jsonArray ?: fail("no values")
        assertTrue(values.isNotEmpty(), "expected at least one authored OPEN PR")
        values.forEach { pr ->
            assertEquals("OPEN", pr.jsonObject["state"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `GET dashboard pull-requests returns MERGED PRs when state=MERGED`() = testApplication {
        setupBitbucket()
        val response = client.get("/rest/api/1.0/dashboard/pull-requests?role=AUTHOR&state=MERGED")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val values = body["values"]?.jsonArray ?: fail("no values")
        assertTrue(values.isNotEmpty(), "expected at least one MERGED PR")
        values.forEach { pr ->
            assertEquals("MERGED", pr.jsonObject["state"]?.jsonPrimitive?.content)
        }
    }

    // --- PR list (per-repo) -------------------------------------------------

    @Test
    fun `GET pull-requests returns paged envelope with values`() = testApplication {
        setupBitbucket()
        val response = client.get(
            "/rest/api/1.0/projects/${BitbucketDataFactory.DEFAULT_PROJECT}" +
                "/repos/${BitbucketDataFactory.DEFAULT_REPO}/pull-requests?state=OPEN"
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val values = body["values"]?.jsonArray ?: fail("no values array")
        assertNotNull(body["size"])
        assertNotNull(body["isLastPage"])
        assertTrue(values.isNotEmpty())
        // All returned PRs must have state=OPEN
        values.forEach { pr ->
            assertEquals("OPEN", pr.jsonObject["state"]?.jsonPrimitive?.content)
        }
    }

    @Test
    fun `GET pull-requests with state=DECLINED returns only declined PRs`() = testApplication {
        setupBitbucket()
        val response = client.get(
            "/rest/api/1.0/projects/${BitbucketDataFactory.DEFAULT_PROJECT}" +
                "/repos/${BitbucketDataFactory.DEFAULT_REPO}/pull-requests?state=DECLINED"
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val values = body["values"]?.jsonArray ?: fail("no values")
        assertTrue(values.isNotEmpty())
        values.forEach { assertEquals("DECLINED", it.jsonObject["state"]?.jsonPrimitive?.content) }
    }

    // --- PR detail ----------------------------------------------------------

    @Test
    fun `GET pull-request detail returns expected fields`() = testApplication {
        setupBitbucket()
        val response = client.get(
            "/rest/api/1.0/projects/${BitbucketDataFactory.DEFAULT_PROJECT}" +
                "/repos/${BitbucketDataFactory.DEFAULT_REPO}/pull-requests/1"
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val pr = Json.parseToJsonElement(response.bodyAsText()).jsonObject

        assertEquals(1, pr["id"]?.jsonPrimitive?.int)
        assertEquals("OPEN", pr["state"]?.jsonPrimitive?.content)
        assertNotNull(pr["title"])
        assertNotNull(pr["description"])
        assertNotNull(pr["version"])
        assertNotNull(pr["author"]?.jsonObject?.get("user")?.jsonObject?.get("name"))
        assertNotNull(pr["fromRef"]?.jsonObject?.get("displayId"))
        assertNotNull(pr["toRef"]?.jsonObject?.get("displayId"))
        assertNotNull(pr["links"]?.jsonObject?.get("self"))

        // Reviewers: PR 1 has 2 reviewers, one approved
        val reviewers = pr["reviewers"]?.jsonArray ?: fail("no reviewers")
        assertEquals(2, reviewers.size)
        val approved = reviewers.count { it.jsonObject["approved"]?.jsonPrimitive?.boolean == true }
        assertEquals(1, approved)
    }

    @Test
    fun `GET pull-request detail for nonexistent PR returns 404`() = testApplication {
        setupBitbucket()
        val response = client.get(
            "/rest/api/1.0/projects/PROJ/repos/my-repo/pull-requests/9999"
        )
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // --- Activities ---------------------------------------------------------

    @Test
    fun `GET activities returns OPENED and COMMENTED actions`() = testApplication {
        setupBitbucket()
        val response = client.get(
            "/rest/api/1.0/projects/${BitbucketDataFactory.DEFAULT_PROJECT}" +
                "/repos/${BitbucketDataFactory.DEFAULT_REPO}/pull-requests/1/activities"
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val values = body["values"]?.jsonArray ?: fail("no values")
        assertTrue(values.isNotEmpty())
        assertTrue(body["isLastPage"]?.jsonPrimitive?.boolean == true)

        val actions = values.map { it.jsonObject["action"]?.jsonPrimitive?.content }
        assertTrue("OPENED" in actions)
        assertTrue("COMMENTED" in actions)

        // COMMENTED entries must carry the comment payload
        val commented = values.first { it.jsonObject["action"]?.jsonPrimitive?.content == "COMMENTED" }
        assertNotNull(commented.jsonObject["comment"]?.jsonObject?.get("text"))
    }

    @Test
    fun `GET activities for merged PR includes MERGED action`() = testApplication {
        setupBitbucket()
        val response = client.get(
            "/rest/api/1.0/projects/${BitbucketDataFactory.DEFAULT_PROJECT}" +
                "/repos/${BitbucketDataFactory.DEFAULT_REPO}/pull-requests/3/activities"
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val actions = body["values"]?.jsonArray?.map { it.jsonObject["action"]?.jsonPrimitive?.content }
        assertTrue("MERGED" in actions.orEmpty())
    }

    // --- Commits ------------------------------------------------------------

    @Test
    fun `GET commits returns commit list with required fields`() = testApplication {
        setupBitbucket()
        val response = client.get(
            "/rest/api/1.0/projects/${BitbucketDataFactory.DEFAULT_PROJECT}" +
                "/repos/${BitbucketDataFactory.DEFAULT_REPO}/pull-requests/1/commits"
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val values = body["values"]?.jsonArray ?: fail("no values")
        assertTrue(values.isNotEmpty())
        assertNotNull(body["size"])
        assertNotNull(body["isLastPage"])
        val first = values[0].jsonObject
        assertNotNull(first["id"])
        assertNotNull(first["displayId"])
        assertNotNull(first["message"])
        assertNotNull(first["author"])
    }

    // --- Changes ------------------------------------------------------------

    @Test
    fun `GET changes returns file change list`() = testApplication {
        setupBitbucket()
        val response = client.get(
            "/rest/api/1.0/projects/${BitbucketDataFactory.DEFAULT_PROJECT}" +
                "/repos/${BitbucketDataFactory.DEFAULT_REPO}/pull-requests/1/changes"
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val values = body["values"]?.jsonArray ?: fail("no values")
        assertTrue(values.isNotEmpty())
        val first = values[0].jsonObject
        assertNotNull(first["path"]?.jsonObject?.get("toString"))
        assertNotNull(first["type"])
        assertEquals("FILE", first["nodeType"]?.jsonPrimitive?.content)
    }

    // --- Diff ---------------------------------------------------------------

    @Test
    fun `GET diff returns plain text unified diff`() = testApplication {
        setupBitbucket()
        val response = client.get(
            "/rest/api/1.0/projects/${BitbucketDataFactory.DEFAULT_PROJECT}" +
                "/repos/${BitbucketDataFactory.DEFAULT_REPO}/pull-requests/1/diff"
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val text = response.bodyAsText()
        assertTrue(text.contains("diff --git"), "expected unified diff header")
        assertTrue(text.contains("@@"), "expected diff hunk marker")
    }

    @Test
    fun `GET diff for nonexistent PR returns 404`() = testApplication {
        setupBitbucket()
        val response = client.get(
            "/rest/api/1.0/projects/PROJ/repos/my-repo/pull-requests/9999/diff"
        )
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    // --- Merge precondition -------------------------------------------------

    @Test
    fun `GET merge returns canMerge=false when reviewer not approved`() = testApplication {
        setupBitbucket()
        // PR 2 has reviewer with NEEDS_WORK — canMerge should be false
        val response = client.get(
            "/rest/api/1.0/projects/PROJ/repos/my-repo/pull-requests/2/merge"
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(false, body["canMerge"]?.jsonPrimitive?.boolean)
        val vetoes = body["vetoes"]?.jsonArray ?: fail("no vetoes")
        assertTrue(vetoes.isNotEmpty())
    }

    // --- Browse -------------------------------------------------------------

    @Test
    fun `GET browse returns plain text file content`() = testApplication {
        setupBitbucket()
        val response = client.get(
            "/rest/api/1.0/projects/PROJ/repos/my-repo/browse/src/main/kotlin/Hello.kt"
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val text = response.bodyAsText()
        assertTrue(text.contains("Hello.kt"), "expected file path in mock content")
    }

    // --- Merge strategy settings --------------------------------------------

    @Test
    fun `GET repo settings returns mergeConfig with strategies`() = testApplication {
        setupBitbucket()
        val response = client.get(
            "/rest/api/1.0/projects/PROJ/repos/my-repo/settings/pull-requests/git"
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val mergeConfig = body["mergeConfig"]?.jsonObject ?: fail("no mergeConfig")
        assertNotNull(mergeConfig["defaultStrategy"])
        val strategies = mergeConfig["strategies"]?.jsonArray ?: fail("no strategies")
        assertTrue(strategies.size >= 4)
    }

    @Test
    fun `GET project settings returns mergeConfig as fallback`() = testApplication {
        setupBitbucket()
        val response = client.get(
            "/rest/api/1.0/projects/PROJ/settings/pull-requests/git"
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotNull(body["mergeConfig"])
    }

    // --- Write endpoints ---------------------------------------------------

    @Test
    fun `POST merge transitions PR to MERGED and returns updated PR`() = testApplication {
        setupBitbucket()
        val response = client.post(
            "/rest/api/1.0/projects/PROJ/repos/my-repo/pull-requests/1/merge"
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("MERGED", body["state"]?.jsonPrimitive?.content)
        assertEquals(1, body["id"]?.jsonPrimitive?.int)
    }

    @Test
    fun `POST merge for nonexistent PR returns 404`() = testApplication {
        setupBitbucket()
        val response = client.post(
            "/rest/api/1.0/projects/PROJ/repos/my-repo/pull-requests/9999/merge"
        )
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST approve sets current user as approved reviewer`() = testApplication {
        setupBitbucket()
        val response = client.post(
            "/rest/api/1.0/projects/PROJ/repos/my-repo/pull-requests/2/approve"
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(true, body["approved"]?.jsonPrimitive?.boolean)
        assertEquals("APPROVED", body["status"]?.jsonPrimitive?.content)
        assertNotNull(body["user"]?.jsonObject?.get("name"))
    }

    @Test
    fun `POST decline transitions PR to DECLINED`() = testApplication {
        setupBitbucket()
        val response = client.post(
            "/rest/api/1.0/projects/PROJ/repos/my-repo/pull-requests/1/decline"
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("DECLINED", body["state"]?.jsonPrimitive?.content)
    }

    @Test
    fun `POST comment adds comment and returns it with id`() = testApplication {
        setupBitbucket()
        val response = client.post(
            "/rest/api/1.0/projects/PROJ/repos/my-repo/pull-requests/1/comments"
        ) {
            contentType(ContentType.Application.Json)
            setBody("""{"text":"This looks good to me."}""")
        }
        assertEquals(HttpStatusCode.Created, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotNull(body["id"])
        assertEquals("This looks good to me.", body["text"]?.jsonPrimitive?.content)
        assertNotNull(body["author"]?.jsonObject?.get("name"))
    }

    @Test
    fun `PUT participant status updates reviewer approval state`() = testApplication {
        setupBitbucket()
        val response = client.put(
            "/rest/api/1.0/projects/PROJ/repos/my-repo/pull-requests/2/participants/jane.smith"
        ) {
            contentType(ContentType.Application.Json)
            setBody("""{"status":"APPROVED","approved":true}""")
        }
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("APPROVED", body["status"]?.jsonPrimitive?.content)
        assertEquals(true, body["approved"]?.jsonPrimitive?.boolean)
    }

    @Test
    fun `DELETE participant removes reviewer and returns 204`() = testApplication {
        setupBitbucket()
        val response = client.delete(
            "/rest/api/1.0/projects/PROJ/repos/my-repo/pull-requests/1/participants/jane.smith"
        )
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `DELETE approve unapproves current user`() = testApplication {
        setupBitbucket()
        val response = client.delete(
            "/rest/api/1.0/projects/PROJ/repos/my-repo/pull-requests/1/approve"
        )
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    // --- State isolation: mutations are per-state-instance ------------------

    @Test
    fun `merge mutation does not leak between separate state instances`() = testApplication {
        val state1 = BitbucketDataFactory.createDefaultState()
        val state2 = BitbucketDataFactory.createDefaultState()

        setupBitbucket(state1)

        // Merge PR 1 on state1 via a second test application
        val mergeResponse = client.post(
            "/rest/api/1.0/projects/PROJ/repos/my-repo/pull-requests/1/merge"
        )
        assertEquals(HttpStatusCode.OK, mergeResponse.status)

        // state2 still sees PR 1 as OPEN
        assertEquals("OPEN", state2.prs[1]?.state)
    }

    // --- Scenario: empty state ---------------------------------------------

    @Test
    fun `empty state returns empty PR list`() = testApplication {
        setupBitbucket(BitbucketDataFactory.createEmptyState())
        val response = client.get(
            "/rest/api/1.0/projects/PROJ/repos/my-repo/pull-requests?state=OPEN"
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val values = body["values"]?.jsonArray ?: fail("no values")
        assertTrue(values.isEmpty())
    }

    // --- Comments endpoint -------------------------------------------------

    @Test
    fun `GET comments returns comment list`() = testApplication {
        setupBitbucket()
        val response = client.get(
            "/rest/api/1.0/projects/PROJ/repos/my-repo/pull-requests/1/comments"
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val values = body["values"]?.jsonArray ?: fail("no values")
        assertTrue(values.isNotEmpty())
        val first = values[0].jsonObject
        assertNotNull(first["id"])
        assertNotNull(first["text"])
        assertNotNull(first["author"])
    }

    // --- Repo and branches --------------------------------------------------

    @Test
    fun `GET repo returns slug and project key`() = testApplication {
        setupBitbucket()
        val response = client.get(
            "/rest/api/1.0/projects/PROJ/repos/my-repo"
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("my-repo", body["slug"]?.jsonPrimitive?.content)
        assertEquals("PROJ", body["project"]?.jsonObject?.get("key")?.jsonPrimitive?.content)
        assertNotNull(body["links"]?.jsonObject?.get("clone"))
    }

    @Test
    fun `GET branches returns branch list including main`() = testApplication {
        setupBitbucket()
        val response = client.get(
            "/rest/api/1.0/projects/PROJ/repos/my-repo/branches"
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val values = body["values"]?.jsonArray ?: fail("no values")
        assertTrue(values.isNotEmpty())
        val displayIds = values.map { it.jsonObject["displayId"]?.jsonPrimitive?.content }
        assertTrue("main" in displayIds)
    }

    @Test
    fun `GET default-branch returns main`() = testApplication {
        setupBitbucket()
        val response = client.get(
            "/rest/api/1.0/projects/PROJ/repos/my-repo/default-branch"
        )
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("main", body["displayId"]?.jsonPrimitive?.content)
        assertEquals(true, body["isDefault"]?.jsonPrimitive?.boolean)
    }
}
