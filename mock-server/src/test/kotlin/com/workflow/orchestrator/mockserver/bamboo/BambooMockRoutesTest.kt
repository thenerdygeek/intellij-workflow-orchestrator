package com.workflow.orchestrator.mockserver.bamboo

import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.routing.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

class BambooMockRoutesTest {

    private fun ApplicationTestBuilder.setupBamboo(state: BambooState = BambooDataFactory.createDefaultState()) {
        application {
            install(ContentNegotiation) { json() }
            routing { bambooRoutes { state } }
        }
    }

    @Test
    fun `GET currentUser returns mock user`() = testApplication {
        setupBamboo()
        val response = client.get("/rest/api/latest/currentUser")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("mock.user", body["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `GET result returns divergent lifeCycleState`() = testApplication {
        setupBamboo()
        val response = client.get("/rest/api/latest/result/PROJ-TEST-50")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Running", body["lifeCycleState"]?.jsonPrimitive?.content,
            "Should return 'Running' not 'InProgress'")
    }

    @Test
    fun `GET result with expand=logEntries includes log`() = testApplication {
        setupBamboo()
        val response = client.get("/rest/api/latest/result/PROJ-BUILD-99?expand=logEntries")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertNotNull(body["logEntries"])
    }

    @Test
    fun `POST queue triggers build and returns Queued`() = testApplication {
        val state = BambooDataFactory.createDefaultState()
        setupBamboo(state)
        val response = client.post("/rest/api/latest/queue/PROJ-BUILD")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("Queued", body["lifeCycleState"]?.jsonPrimitive?.content)
        state.shutdown()
    }

    @Test
    fun `DELETE queue cancels build`() = testApplication {
        val state = BambooDataFactory.createDefaultState()
        setupBamboo(state)
        val build = state.triggerBuild("PROJ-BUILD")
        val response = client.delete("/rest/api/latest/queue/${build.buildResultKey}")
        assertEquals(HttpStatusCode.NoContent, response.status)
        state.shutdown()
    }

    @Test
    fun `GET plan lists all plans`() = testApplication {
        setupBamboo()
        val response = client.get("/rest/api/latest/plan")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val plans = body["plans"]?.jsonObject?.get("plan")?.jsonArray
        assertEquals(3, plans?.size)
    }

    @Test
    fun `GET project lists projects with key and name`() = testApplication {
        setupBamboo()
        val response = client.get("/rest/api/latest/project?max-results=100&start-index=0")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val projects = body["projects"]?.jsonObject?.get("project")?.jsonArray
        assertNotNull(projects)
        assertTrue(projects!!.isNotEmpty(), "Project list must be non-empty for the picker")
        val proj = projects[0].jsonObject
        assertEquals("PROJ", proj["key"]?.jsonPrimitive?.content)
        assertNotNull(proj["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `GET project past end returns empty page (pagination terminates)`() = testApplication {
        setupBamboo()
        val response = client.get("/rest/api/latest/project?max-results=100&start-index=100")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val projects = body["projects"]?.jsonObject?.get("project")?.jsonArray
        assertEquals(0, projects?.size)
    }

    @Test
    fun `GET project detail resolves the project's plans`() = testApplication {
        setupBamboo()
        val response = client.get("/rest/api/latest/project/PROJ?expand=plans.plan")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val plans = body["plans"]?.jsonObject?.get("plan")?.jsonArray
        assertNotNull(plans)
        assertEquals(3, plans!!.size, "PROJ owns the 3 default plans")
        val keys = plans.map { it.jsonObject["key"]?.jsonPrimitive?.content }
        assertTrue(keys.contains("PROJ-BUILD"))
    }

    @Test
    fun `GET project detail for unknown project returns 404`() = testApplication {
        setupBamboo()
        val response = client.get("/rest/api/latest/project/NOPE?expand=plans.plan")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `project to plan to stages chain resolves for picker and ManualStageDialog`() = testApplication {
        setupBamboo()
        // 1. Project list resolves a project key.
        val projects = Json.parseToJsonElement(
            client.get("/rest/api/latest/project").bodyAsText()
        ).jsonObject["projects"]?.jsonObject?.get("project")?.jsonArray
        val projectKey = projects!![0].jsonObject["key"]!!.jsonPrimitive.content

        // 2. Project detail resolves a plan key.
        val plans = Json.parseToJsonElement(
            client.get("/rest/api/latest/project/$projectKey?expand=plans.plan").bodyAsText()
        ).jsonObject["plans"]?.jsonObject?.get("plan")?.jsonArray
        val planKey = plans!!.first { it.jsonObject["key"]?.jsonPrimitive?.content == "PROJ-BUILD" }
            .jsonObject["key"]!!.jsonPrimitive.content

        // 3. Latest result for that plan exposes stages (drives ManualStageDialog's checkboxes).
        val result = Json.parseToJsonElement(
            client.get("/rest/api/latest/result/$planKey/latest?expand=stages.stage.results.result").bodyAsText()
        ).jsonObject
        val stages = result["stages"]?.jsonObject?.get("stage")?.jsonArray
        assertNotNull(stages, "Plan's latest result must expose stages for ManualStageDialog")
        assertTrue(stages!!.isNotEmpty())
        assertNotNull(stages[0].jsonObject["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `GET search plans filters by search term`() = testApplication {
        setupBamboo()
        val response = client.get("/rest/api/latest/search/plans?searchTerm=Sonar")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(1, body["size"]?.jsonPrimitive?.int)
    }

    @Test
    fun `GET plan branches returns branches for plan`() = testApplication {
        setupBamboo()
        val response = client.get("/rest/api/latest/plan/PROJ-BUILD/branch")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val branches = body["branches"]?.jsonObject?.get("branch")?.jsonArray
        assertEquals(2, branches?.size)
    }

    @Test
    fun `GET plan variables returns variables`() = testApplication {
        setupBamboo()
        val response = client.get("/rest/api/latest/plan/PROJ-BUILD/variable")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val vars = body["variables"]?.jsonArray
        assertNotNull(vars)
        assertTrue(vars!!.size >= 2)
    }

    @Test
    fun `GET result latest returns latest build for plan`() = testApplication {
        setupBamboo()
        val response = client.get("/rest/api/latest/result/PROJ-BUILD/latest")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals(99, body["buildNumber"]?.jsonPrimitive?.int)
    }

    @Test
    fun `GET result latest returns 404 for unknown plan`() = testApplication {
        setupBamboo()
        val response = client.get("/rest/api/latest/result/NONEXIST/latest")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }

    @Test
    fun `POST queue for unknown plan returns 404`() = testApplication {
        setupBamboo()
        val response = client.post("/rest/api/latest/queue/NONEXIST")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
