package com.workflow.orchestrator.core.auth

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ServiceType
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import com.intellij.testFramework.LoggedErrorProcessorEnabler

@ExtendWith(LoggedErrorProcessorEnabler.DoNoRethrowErrors::class)
class AuthTestServiceTest {

    private lateinit var server: MockWebServer
    private lateinit var service: AuthTestService

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        service = AuthTestService()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `Jira test connection succeeds on 200`() = runTest {
        server.enqueue(MockResponse()
            .setBody("""{"displayName":"John","emailAddress":"john@co.com"}""")
            .setResponseCode(200))

        val result = service.testConnection(
            serviceType = ServiceType.JIRA,
            baseUrl = server.url("/").toString().trimEnd('/'),
            token = "test-token"
        )

        assertTrue(result.isSuccess)
        val recorded = server.takeRequest()
        assertEquals("/rest/api/2/myself", recorded.path)
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun `test connection returns error on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = service.testConnection(
            serviceType = ServiceType.JIRA,
            baseUrl = server.url("/").toString().trimEnd('/'),
            token = "bad-token"
        )

        assertTrue(result.isError)
    }

    @Test
    fun `Bamboo test connection hits correct endpoint`() = runTest {
        server.enqueue(MockResponse()
            .setBody("""{"name":"admin"}""")
            .setResponseCode(200))

        service.testConnection(
            serviceType = ServiceType.BAMBOO,
            baseUrl = server.url("/").toString().trimEnd('/'),
            token = "bamboo-token"
        )

        val recorded = server.takeRequest()
        assertEquals("/rest/api/latest/currentUser", recorded.path)
    }

    @Test
    fun `SonarQube test connection hits correct endpoint`() = runTest {
        server.enqueue(MockResponse()
            .setBody("""{"login":"admin"}""")
            .setResponseCode(200))

        service.testConnection(
            serviceType = ServiceType.SONARQUBE,
            baseUrl = server.url("/").toString().trimEnd('/'),
            token = "sonar-token"
        )

        val recorded = server.takeRequest()
        assertEquals("/api/authentication/validate", recorded.path)
    }

    @Test
    fun `Bitbucket test connection hits correct endpoint`() = runTest {
        server.enqueue(MockResponse()
            .setBody("""{"name":"admin"}""")
            .setResponseCode(200))

        service.testConnection(
            serviceType = ServiceType.BITBUCKET,
            baseUrl = server.url("/").toString().trimEnd('/'),
            token = "bb-token"
        )

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("/rest/api/"))
    }
}
