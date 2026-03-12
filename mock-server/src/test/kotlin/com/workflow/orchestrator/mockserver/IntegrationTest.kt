package com.workflow.orchestrator.mockserver

import com.workflow.orchestrator.mockserver.jira.*
import com.workflow.orchestrator.mockserver.sonar.*
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

class IntegrationTest {

    @Test
    fun `full Jira workflow - discover board, find sprint, load issues, transition`() = testApplication {
        val state = JiraDataFactory.createDefaultState()
        application {
            install(ContentNegotiation) { json() }
            routing { jiraRoutes { state } }
        }

        // Step 1: Discover board
        val boardsResponse = client.get("/rest/agile/1.0/board?type=scrum")
        val boards = Json.parseToJsonElement(boardsResponse.bodyAsText()).jsonObject["values"]?.jsonArray
        val boardId = boards?.get(0)?.jsonObject?.get("id")?.jsonPrimitive?.int
        assertEquals(42, boardId)

        // Step 2: Find active sprint
        val sprintsResponse = client.get("/rest/agile/1.0/board/$boardId/sprint?state=active")
        val sprints = Json.parseToJsonElement(sprintsResponse.bodyAsText()).jsonObject["values"]?.jsonArray
        val sprintId = sprints?.get(0)?.jsonObject?.get("id")?.jsonPrimitive?.int
        assertEquals(7, sprintId)

        // Step 3: Load issues
        val issuesResponse = client.get("/rest/agile/1.0/sprint/$sprintId/issue")
        val issues = Json.parseToJsonElement(issuesResponse.bodyAsText()).jsonObject["issues"]?.jsonArray
        assertEquals(6, issues?.size)

        // Step 4: Get transitions for an Open issue
        val transResponse = client.get("/rest/api/2/issue/PROJ-101/transitions")
        val transitions = Json.parseToJsonElement(transResponse.bodyAsText()).jsonObject["transitions"]?.jsonArray
        assertNotNull(transitions)
        // None should be named "In Progress"
        val transNames = transitions!!.map { it.jsonObject["name"]?.jsonPrimitive?.content }
        assertFalse("In Progress" in transNames)

        // Step 5: Try transition without required fields — should fail
        val failResponse = client.post("/rest/api/2/issue/PROJ-101/transitions") {
            contentType(ContentType.Application.Json)
            setBody("""{"transition":{"id":"11"}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, failResponse.status)

        // Step 6: Transition with required fields — should succeed
        val successResponse = client.post("/rest/api/2/issue/PROJ-101/transitions") {
            contentType(ContentType.Application.Json)
            setBody("""{"transition":{"id":"11"},"fields":{"assignee":{"name":"mock.user"}}}""")
        }
        assertEquals(HttpStatusCode.NoContent, successResponse.status)

        // Verify state changed
        val updatedIssue = client.get("/rest/api/2/issue/PROJ-101")
        val updatedStatus = Json.parseToJsonElement(updatedIssue.bodyAsText())
            .jsonObject["fields"]?.jsonObject?.get("status")?.jsonObject?.get("name")?.jsonPrimitive?.content
        assertEquals("WIP", updatedStatus)
    }

    @Test
    fun `SonarQube returns all divergent data correctly`() = testApplication {
        val state = SonarDataFactory.createDefaultState()
        application {
            install(ContentNegotiation) { json() }
            routing { sonarRoutes { state } }
        }

        // Quality gate has WARN
        val gateResponse = client.get("/api/qualitygates/project_status")
        val gate = Json.parseToJsonElement(gateResponse.bodyAsText()).jsonObject
        assertEquals("WARN", gate["projectStatus"]?.jsonObject?.get("status")?.jsonPrimitive?.content)

        // Issues have CRITICAL_SECURITY severity and SECURITY_AUDIT type
        val issuesResponse = client.get("/api/issues/search?resolved=false")
        val issues = Json.parseToJsonElement(issuesResponse.bodyAsText()).jsonObject["issues"]?.jsonArray!!
        val severities = issues.map { it.jsonObject["severity"]?.jsonPrimitive?.content }.toSet()
        val types = issues.map { it.jsonObject["type"]?.jsonPrimitive?.content }.toSet()
        assertTrue("CRITICAL_SECURITY" in severities)
        assertTrue("SECURITY_AUDIT" in types)

        // Metrics omit uncovered_conditions
        val metricsResponse = client.get("/api/measures/component_tree?component=com.example:service&metricKeys=coverage,uncovered_conditions")
        val metrics = Json.parseToJsonElement(metricsResponse.bodyAsText()).jsonObject
        val metricKeys = metrics["baseComponent"]?.jsonObject?.get("measures")?.jsonArray?.map {
            it.jsonObject["metric"]?.jsonPrimitive?.content
        }
        assertTrue(metricKeys?.contains("coverage") == true)
        assertFalse(metricKeys?.contains("uncovered_conditions") == true)
    }
}
