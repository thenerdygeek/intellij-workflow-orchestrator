package com.workflow.orchestrator.mockserver.jira

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

class JiraMockRoutesTest {

    private fun ApplicationTestBuilder.setupJira(state: JiraState = JiraDataFactory.createDefaultState()) {
        application {
            install(ContentNegotiation) { json() }
            routing { jiraRoutes { state } }
        }
    }

    @Test
    fun `GET myself returns current user`() = testApplication {
        setupJira()
        val response = client.get("/rest/api/2/myself")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertEquals("mock.user", body["name"]?.jsonPrimitive?.content)
    }

    @Test
    fun `GET board returns scrum boards`() = testApplication {
        setupJira()
        val response = client.get("/rest/agile/1.0/board?type=scrum")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val values = body["values"]?.jsonArray
        assertNotNull(values)
        assertTrue(values!!.isNotEmpty())
        assertEquals(42, values[0].jsonObject["id"]?.jsonPrimitive?.int)
    }

    @Test
    fun `GET sprint issues returns assigned tickets with divergent statuses`() = testApplication {
        setupJira()
        val response = client.get("/rest/agile/1.0/sprint/7/issue")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val issues = body["issues"]?.jsonArray ?: fail("no issues array")
        assertEquals(6, issues.size)
        val statusNames = issues.map {
            it.jsonObject["fields"]?.jsonObject?.get("status")?.jsonObject?.get("name")?.jsonPrimitive?.content
        }
        assertFalse("In Progress" in statusNames)
    }

    @Test
    fun `GET transitions returns fields with requirements`() = testApplication {
        setupJira()
        val response = client.get("/rest/api/2/issue/PROJ-101/transitions")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val transitions = body["transitions"]?.jsonArray ?: fail("no transitions")
        val startWorking = transitions.find {
            it.jsonObject["name"]?.jsonPrimitive?.content == "Start Working"
        }?.jsonObject
        assertNotNull(startWorking)
        val assigneeField = startWorking!!["fields"]?.jsonObject?.get("assignee")?.jsonObject
        assertTrue(assigneeField?.get("required")?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun `POST transition without required fields returns 400`() = testApplication {
        setupJira()
        val response = client.post("/rest/api/2/issue/PROJ-101/transitions") {
            contentType(ContentType.Application.Json)
            setBody("""{"transition":{"id":"11"}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val errors = body["errors"]?.jsonObject
        assertNotNull(errors?.get("assignee"))
    }

    @Test
    fun `POST transition with required fields succeeds`() = testApplication {
        setupJira()
        val response = client.post("/rest/api/2/issue/PROJ-101/transitions") {
            contentType(ContentType.Application.Json)
            setBody("""{"transition":{"id":"11"},"fields":{"assignee":{"name":"mock.user"}}}""")
        }
        assertEquals(HttpStatusCode.NoContent, response.status)
    }

    @Test
    fun `GET nonexistent issue returns 404`() = testApplication {
        setupJira()
        val response = client.get("/rest/api/2/issue/NONEXIST-1")
        assertEquals(HttpStatusCode.NotFound, response.status)
    }
}
