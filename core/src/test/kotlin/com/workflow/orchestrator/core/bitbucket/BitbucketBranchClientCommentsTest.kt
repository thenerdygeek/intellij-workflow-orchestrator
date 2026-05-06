package com.workflow.orchestrator.core.bitbucket

import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue

class BitbucketBranchClientCommentsTest {
    private lateinit var server: MockWebServer
    private lateinit var client: BitbucketBranchClient

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        client = BitbucketBranchClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" },
        )
    }

    @AfterEach
    fun tearDown() { server.shutdown() }

    /*
     * `listPrComments` was rewritten by the 2026-05-07 Bitbucket audit (R-1.1) to
     * derive comments from the activities timeline because DC 9.4 returns 400 from
     * the direct `/comments` listing without `path` or `count=true`. The behaviour
     * tests are now in `BitbucketBranchClientAuditAdditionsTest`. The pagination
     * test below kept its name but was retargeted at the activities endpoint to
     * preserve the multi-page coverage on the new code path.
     */
    @Test
    fun `listPrComments aggregates COMMENTED activities across pages`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"values":[
                    {"id":100,"action":"COMMENTED","user":{"name":"u","displayName":"U"},"comment":{"id":1,"text":"a","author":{"name":"u","displayName":"U"}}},
                    {"id":101,"action":"OPENED","user":{"name":"u","displayName":"U"}},
                    {"id":102,"action":"COMMENTED","user":{"name":"u","displayName":"U"},"comment":{"id":2,"text":"b","author":{"name":"u","displayName":"U"}}}
                  ],"isLastPage":false,"nextPageStart":2}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"values":[
                    {"id":200,"action":"COMMENTED","user":{"name":"u","displayName":"U"},"comment":{"id":3,"text":"c","author":{"name":"u","displayName":"U"}}}
                  ],"isLastPage":true}"""
            )
        )
        val result = client.listPrComments("P", "R", 1)
        assertTrue(result is ApiResult.Success)
        val values = (result as ApiResult.Success).data.values
        assertEquals(3, values.size)
        assertEquals(listOf(1L, 2L, 3L), values.map { it.id })
    }

    @Test
    fun `listPrComments single page happy path`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"values":[{"id":1,"action":"COMMENTED","user":{"name":"u","displayName":"U"},"comment":{"id":1,"text":"hi","author":{"name":"u","displayName":"U"}}}],"isLastPage":true}"""
            )
        )
        val result = client.listPrComments("P", "R", 1)
        assertTrue(result is ApiResult.Success)
        assertEquals(1, (result as ApiResult.Success).data.values.size)
    }

    @Test
    fun `listPrComments uses GET and Accept application_json and activities path`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"values":[],"isLastPage":true}"""))
        client.listPrComments("P", "R", 1)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertTrue(req.getHeader("Accept")!!.contains("application/json"))
        assertTrue(req.path!!.contains("/pull-requests/1/activities"))
    }

    @Test
    fun `getPrComment returns single comment`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"id":42,"version":3,"text":"hello","author":{"name":"u","displayName":"U"},"state":"OPEN","severity":"NORMAL"}"""
        ))
        val result = client.getPrComment("P", "R", 1, 42L)
        assertTrue(result is ApiResult.Success)
        val c = (result as ApiResult.Success).data
        assertEquals(42L, c.id)
        assertEquals(3, c.version)
    }

    @Test
    fun `getPrComment returns 404 as Error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody(
            """{"errors":[{"message":"not found"}]}"""
        ))
        val result = client.getPrComment("P", "R", 1, 999L)
        assertTrue(result is ApiResult.Error)
    }

    @Test
    fun `editPrComment sends PUT with text and version`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"id":42,"version":4,"text":"updated","author":{"name":"u","displayName":"U"},"state":"OPEN","severity":"NORMAL"}"""
        ))
        val result = client.editPrComment("P", "R", 1, 42, text = "updated", expectedVersion = 3)
        assertTrue(result is ApiResult.Success)

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("updated", body["text"]!!.jsonPrimitive.content)
        assertEquals(3, body["version"]!!.jsonPrimitive.int)
    }

    @Test
    fun `editPrComment surfaces 409 as STALE_VERSION error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409).setBody(
            """{"errors":[{"message":"Comment modified since last read"}]}"""
        ))
        val result = client.editPrComment("P", "R", 1, 42, text = "updated", expectedVersion = 3)
        assertTrue(result is ApiResult.Error)
        val errorResult = result as ApiResult.Error
        val msg = errorResult.message
        assertTrue(msg.contains("STALE_VERSION"), "error message was: $msg")
    }

    @Test
    fun `deletePrComment sends DELETE with version query param`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))
        val result = client.deletePrComment("P", "R", 1, 42, expectedVersion = 3)
        assertTrue(result is ApiResult.Success)
        val req = server.takeRequest()
        assertEquals("DELETE", req.method)
        assertTrue(req.path!!.contains("version=3"))
    }

    @Test
    fun `deletePrComment 409 yields STALE_VERSION error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(409))
        val result = client.deletePrComment("P", "R", 1, 42, expectedVersion = 3)
        assertTrue(result is ApiResult.Error)
        val msg = (result as ApiResult.Error).message
        assertTrue(msg.contains("STALE_VERSION"))
    }

    @Test
    fun `resolvePrComment sends PUT with state RESOLVED`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"id":42,"version":3,"text":"c","author":{"name":"u","displayName":"U"},"state":"RESOLVED","severity":"NORMAL"}"""
        ))
        val result = client.resolvePrComment("P", "R", 1, 42)
        assertTrue(result is ApiResult.Success)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("RESOLVED", body["state"]!!.jsonPrimitive.content)
    }

    @Test
    fun `reopenPrComment sends PUT with state OPEN`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"id":42,"version":3,"text":"c","author":{"name":"u","displayName":"U"},"state":"OPEN","severity":"NORMAL"}"""
        ))
        val result = client.reopenPrComment("P", "R", 1, 42)
        assertTrue(result is ApiResult.Success)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("OPEN", body["state"]!!.jsonPrimitive.content)
    }
}
