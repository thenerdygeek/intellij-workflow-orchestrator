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
