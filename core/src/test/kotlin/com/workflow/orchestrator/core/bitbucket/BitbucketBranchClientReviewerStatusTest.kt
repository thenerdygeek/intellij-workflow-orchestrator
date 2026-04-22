package com.workflow.orchestrator.core.bitbucket

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals

class BitbucketBranchClientReviewerStatusTest {
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

    @Test
    fun `setReviewerStatus APPROVED sends approved=true`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        client.setReviewerStatus("P", "R", 1, "alice", "APPROVED")
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("APPROVED", body["status"]!!.jsonPrimitive.content)
        assertEquals(true, body["approved"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `setReviewerStatus NEEDS_WORK sends approved=false`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        client.setReviewerStatus("P", "R", 1, "alice", "NEEDS_WORK")
        val req = server.takeRequest()
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("NEEDS_WORK", body["status"]!!.jsonPrimitive.content)
        assertEquals(false, body["approved"]!!.jsonPrimitive.content.toBoolean())
    }

    @Test
    fun `setReviewerStatus UNAPPROVED sends approved=false`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("{}"))
        client.setReviewerStatus("P", "R", 1, "alice", "UNAPPROVED")
        val req = server.takeRequest()
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("UNAPPROVED", body["status"]!!.jsonPrimitive.content)
        assertEquals(false, body["approved"]!!.jsonPrimitive.content.toBoolean())
    }
}
