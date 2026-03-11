package com.workflow.orchestrator.core.http

import com.workflow.orchestrator.core.model.ServiceType
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HttpClientFactoryTest {

    private lateinit var server: MockWebServer
    private lateinit var factory: HttpClientFactory

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        factory = HttpClientFactory(
            tokenProvider = { service ->
                if (service == ServiceType.JIRA) "jira-token" else null
            }
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `creates client with auth for service`() {
        server.enqueue(MockResponse().setBody("ok"))

        val client = factory.clientFor(ServiceType.JIRA)
        val response = client.newCall(
            okhttp3.Request.Builder().url(server.url("/api")).build()
        ).execute()

        val recorded = server.takeRequest()
        assertEquals("Bearer jira-token", recorded.getHeader("Authorization"))
        assertEquals(200, response.code)
    }

    @Test
    fun `caches client per service type`() {
        val client1 = factory.clientFor(ServiceType.JIRA)
        val client2 = factory.clientFor(ServiceType.JIRA)
        assertSame(client1, client2)
    }

    @Test
    fun `different services get different clients`() {
        val jiraClient = factory.clientFor(ServiceType.JIRA)
        val bambooClient = factory.clientFor(ServiceType.BAMBOO)
        assertNotSame(jiraClient, bambooClient)
    }
}
