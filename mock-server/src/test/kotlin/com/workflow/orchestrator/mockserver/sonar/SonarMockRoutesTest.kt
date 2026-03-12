package com.workflow.orchestrator.mockserver.sonar

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

class SonarMockRoutesTest {

    private fun ApplicationTestBuilder.setupSonar(state: SonarState = SonarDataFactory.createDefaultState()) {
        application {
            install(ContentNegotiation) { json() }
            routing { sonarRoutes { state } }
        }
    }

    @Test
    fun `GET auth validate returns valid true`() = testApplication {
        setupSonar()
        val response = client.get("/api/authentication/validate")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertTrue(body["valid"]?.jsonPrimitive?.boolean == true)
    }

    @Test
    fun `auth-invalid scenario returns valid false`() = testApplication {
        setupSonar(SonarDataFactory.createAuthInvalidState())
        val response = client.get("/api/authentication/validate")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        assertFalse(body["valid"]?.jsonPrimitive?.boolean ?: true)
    }

    @Test
    fun `GET quality gate returns divergent WARN status`() = testApplication {
        setupSonar()
        val response = client.get("/api/qualitygates/project_status?projectKey=com.example:service")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val status = body["projectStatus"]?.jsonObject?.get("status")?.jsonPrimitive?.content
        assertEquals("WARN", status, "Should return 'WARN' not just 'OK' or 'ERROR'")
    }

    @Test
    fun `GET issues returns divergent severity CRITICAL_SECURITY`() = testApplication {
        setupSonar()
        val response = client.get("/api/issues/search?resolved=false")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val severities = body["issues"]?.jsonArray?.map {
            it.jsonObject["severity"]?.jsonPrimitive?.content
        } ?: emptyList()
        assertTrue("CRITICAL_SECURITY" in severities, "Should include divergent severity")
    }

    @Test
    fun `GET issues returns divergent type SECURITY_AUDIT`() = testApplication {
        setupSonar()
        val response = client.get("/api/issues/search?resolved=false")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val types = body["issues"]?.jsonArray?.map {
            it.jsonObject["type"]?.jsonPrimitive?.content
        } ?: emptyList()
        assertTrue("SECURITY_AUDIT" in types, "Should include divergent issue type")
    }

    @Test
    fun `GET measures omits uncovered_conditions`() = testApplication {
        setupSonar()
        val response = client.get("/api/measures/component_tree?component=com.example:service&metricKeys=coverage,uncovered_conditions")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val metrics = body["baseComponent"]?.jsonObject?.get("measures")?.jsonArray?.map {
            it.jsonObject["metric"]?.jsonPrimitive?.content
        } ?: emptyList()
        assertTrue("coverage" in metrics)
        assertFalse("uncovered_conditions" in metrics, "Should NOT include uncovered_conditions")
    }

    @Test
    fun `metrics-missing state returns only coverage`() = testApplication {
        setupSonar(SonarDataFactory.createMetricsMissingState())
        val response = client.get("/api/measures/component_tree?component=com.example:service")
        val body = Json.parseToJsonElement(response.bodyAsText()).jsonObject
        val metrics = body["baseComponent"]?.jsonObject?.get("measures")?.jsonArray
        assertEquals(1, metrics?.size)
    }
}
