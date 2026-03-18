package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.agent.tools.ToolResult
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IntegrationToolSupportTest {

    @Test
    fun `buildClient creates client with AuthInterceptor`() {
        val client = IntegrationToolSupport.buildClient("test-token")

        // Client should have at least 2 interceptors (AuthInterceptor + RetryInterceptor)
        assertTrue(client.interceptors.size >= 2)
        assertEquals(10_000, client.connectTimeoutMillis)
        assertEquals(30_000, client.readTimeoutMillis)
    }

    @Test
    fun `resolveCredentials returns null when URL is blank`() {
        val result = IntegrationToolSupport.resolveCredentials(
            urlProvider = { "" },
            tokenProvider = { "test-token" },
            serviceName = "TestService"
        )

        assertNull(result)
    }

    @Test
    fun `resolveCredentials returns null when URL is null`() {
        val result = IntegrationToolSupport.resolveCredentials(
            urlProvider = { null },
            tokenProvider = { "test-token" },
            serviceName = "TestService"
        )

        assertNull(result)
    }

    @Test
    fun `resolveCredentials returns null when token is null`() {
        val result = IntegrationToolSupport.resolveCredentials(
            urlProvider = { "https://jira.example.com" },
            tokenProvider = { null },
            serviceName = "TestService"
        )

        assertNull(result)
    }

    @Test
    fun `resolveCredentials returns null when token is blank`() {
        val result = IntegrationToolSupport.resolveCredentials(
            urlProvider = { "https://jira.example.com" },
            tokenProvider = { "" },
            serviceName = "TestService"
        )

        assertNull(result)
    }

    @Test
    fun `resolveCredentials returns triple when both URL and token present`() {
        val result = IntegrationToolSupport.resolveCredentials(
            urlProvider = { "https://jira.example.com/" },
            tokenProvider = { "my-token" },
            serviceName = "TestService"
        )

        assertNotNull(result)
        val (baseUrl, token, client) = result!!
        assertEquals("https://jira.example.com", baseUrl) // trailing slash trimmed
        assertEquals("my-token", token)
        assertNotNull(client)
    }

    @Test
    fun `resolveCredentials trims trailing slash from URL`() {
        val result = IntegrationToolSupport.resolveCredentials(
            urlProvider = { "https://example.com///" },
            tokenProvider = { "token" },
            serviceName = "Test"
        )

        assertNotNull(result)
        // trimEnd('/') removes all trailing slashes
        assertFalse(result!!.first.endsWith("/"))
    }

    @Test
    fun `credentialError returns ToolResult with isError true`() {
        val result = IntegrationToolSupport.credentialError("Jira", "URL")

        assertTrue(result.isError)
        assertTrue(result.content.contains("Jira URL not configured"))
        assertTrue(result.summary.contains("Jira URL not configured"))
    }

    @Test
    fun `credentialError for token field`() {
        val result = IntegrationToolSupport.credentialError("Bamboo", "token")

        assertTrue(result.isError)
        assertTrue(result.content.contains("Bamboo token not configured"))
    }

    @Test
    fun `credentialError uses ERROR_TOKEN_ESTIMATE`() {
        val result = IntegrationToolSupport.credentialError("SonarQube", "URL")

        assertTrue(result.isError)
        assertEquals(ToolResult.ERROR_TOKEN_ESTIMATE, result.tokenEstimate)
    }
}
