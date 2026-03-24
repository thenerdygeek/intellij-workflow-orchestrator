package com.workflow.orchestrator.automation.api

import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DockerRegistryClientTest {

    private lateinit var server: MockWebServer
    private lateinit var client: DockerRegistryClient

    private fun fixture(name: String): String =
        javaClass.classLoader.getResource("fixtures/$name")!!.readText()

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = DockerRegistryClient(
            registryUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-basic-token" },
            skipRealmValidation = true // localhost mock server would fail private-IP check
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `tagExists returns true when HEAD returns 200`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        val result = client.tagExists("service-auth", "2.4.0")

        assertTrue(result.isSuccess)
        assertTrue((result as ApiResult.Success).data)

        val recorded = server.takeRequest()
        assertEquals("HEAD", recorded.method)
        assertTrue(recorded.path!!.contains("/v2/service-auth/manifests/2.4.0"))
    }

    @Test
    fun `tagExists returns false when HEAD returns 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))

        val result = client.tagExists("service-auth", "nonexistent")

        assertTrue(result.isSuccess)
        assertFalse((result as ApiResult.Success).data)
    }

    @Test
    fun `listTags returns parsed tag list`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("docker-tags-list.json")))

        val result = client.listTags("service-auth")

        assertTrue(result.isSuccess)
        val tags = (result as ApiResult.Success).data
        assertEquals(5, tags.size)
        assertTrue(tags.contains("2.4.0"))
        assertTrue(tags.contains("feature-PROJ-123-a1b2c3d"))
    }

    @Test
    fun `listTags handles empty tags`() = runTest {
        server.enqueue(MockResponse().setBody("""{"name":"service-new","tags":null}"""))

        val result = client.listTags("service-new")

        assertTrue(result.isSuccess)
        val tags = (result as ApiResult.Success).data
        assertTrue(tags.isEmpty())
    }

    @Test
    fun `auth handshake on 401 with WWW-Authenticate header`() = runTest {
        val authUrl = server.url("/token").toString()
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader(
                    "WWW-Authenticate",
                    """Bearer realm="$authUrl",service="registry.example.com",scope="repository:service-auth:pull""""
                )
        )
        server.enqueue(MockResponse().setBody(fixture("docker-auth-token.json")))
        server.enqueue(MockResponse().setBody(fixture("docker-tags-list.json")))

        val result = client.listTags("service-auth")

        assertTrue(result.isSuccess)
        val tags = (result as ApiResult.Success).data
        assertEquals(5, tags.size)

        assertEquals(3, server.requestCount)
        val retryRequest = server.takeRequest() // first 401
        server.takeRequest() // token request
        val finalRequest = server.takeRequest() // retry with token
        assertTrue(finalRequest.getHeader("Authorization")!!.startsWith("Bearer "))
    }

    @Test
    fun `getLatestReleaseTag returns highest semver tag`() = runTest {
        server.enqueue(MockResponse().setBody(fixture("docker-tags-list.json")))

        val result = client.getLatestReleaseTag("service-auth")

        assertTrue(result.isSuccess)
        assertEquals("2.4.0", (result as ApiResult.Success).data)
    }

    @Test
    fun `getLatestReleaseTag returns null when no release tags exist`() = runTest {
        server.enqueue(
            MockResponse().setBody("""{"name":"service-auth","tags":["feature-abc","develop-xyz"]}""")
        )

        val result = client.getLatestReleaseTag("service-auth")

        assertTrue(result.isSuccess)
        assertNull((result as ApiResult.Success).data)
    }

    @Test
    fun `tagExists handles auth handshake transparently`() = runTest {
        val authUrl = server.url("/token").toString()
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setHeader(
                    "WWW-Authenticate",
                    """Bearer realm="$authUrl",service="registry.example.com",scope="repository:service-auth:pull""""
                )
        )
        server.enqueue(MockResponse().setBody(fixture("docker-auth-token.json")))
        server.enqueue(MockResponse().setResponseCode(200))

        val result = client.tagExists("service-auth", "2.4.0")

        assertTrue(result.isSuccess)
        assertTrue((result as ApiResult.Success).data)
    }

    @Test
    fun `listTags follows pagination Link header`() = runTest {
        server.enqueue(
            MockResponse()
                .setBody("""{"name":"service-auth","tags":["1.0.0","1.1.0"]}""")
                .setHeader("Link", """</v2/service-auth/tags/list?n=2&last=1.1.0>; rel="next"""")
        )
        server.enqueue(
            MockResponse()
                .setBody("""{"name":"service-auth","tags":["2.0.0"]}""")
        )

        val result = client.listTags("service-auth")

        assertTrue(result.isSuccess)
        val tags = (result as ApiResult.Success).data
        assertEquals(3, tags.size)
        assertTrue(tags.containsAll(listOf("1.0.0", "1.1.0", "2.0.0")))
    }

    @Test
    fun `handles network error gracefully`() = runTest {
        server.shutdown()

        val result = client.tagExists("service-auth", "2.4.0")

        assertTrue(result is ApiResult.Error)
    }
}
