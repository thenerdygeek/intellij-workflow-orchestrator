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

class BitbucketBranchClientInlineAnchorTest {
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
    fun `REMOVED line anchor sends fileType FROM`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":1,"version":0,"text":"c","author":{"name":"u","displayName":"U"},"createdDate":0,"updatedDate":0}"""))
        client.addInlineComment(
            projectKey = "P",
            repoSlug = "R",
            prId = 1,
            filePath = "src/Foo.kt",
            lineNumber = 42,
            lineType = "REMOVED",
            text = "comment",
        )
        val recorded = server.takeRequest()
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        val anchor = body["anchor"]!!.jsonObject
        assertEquals("FROM", anchor["fileType"]!!.jsonPrimitive.content)
        assertEquals("REMOVED", anchor["lineType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `ADDED line anchor sends fileType TO`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":1,"version":0,"text":"c","author":{"name":"u","displayName":"U"},"createdDate":0,"updatedDate":0}"""))
        client.addInlineComment(
            projectKey = "P",
            repoSlug = "R",
            prId = 1,
            filePath = "src/Foo.kt",
            lineNumber = 42,
            lineType = "ADDED",
            text = "comment",
        )
        val recorded = server.takeRequest()
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        val anchor = body["anchor"]!!.jsonObject
        assertEquals("TO", anchor["fileType"]!!.jsonPrimitive.content)
    }

    @Test
    fun `CONTEXT line anchor sends fileType TO`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{"id":1,"version":0,"text":"c","author":{"name":"u","displayName":"U"},"createdDate":0,"updatedDate":0}"""))
        client.addInlineComment(
            projectKey = "P",
            repoSlug = "R",
            prId = 1,
            filePath = "src/Foo.kt",
            lineNumber = 42,
            lineType = "CONTEXT",
            text = "comment",
        )
        val recorded = server.takeRequest()
        val body = Json.parseToJsonElement(recorded.body.readUtf8()).jsonObject
        val anchor = body["anchor"]!!.jsonObject
        assertEquals("TO", anchor["fileType"]!!.jsonPrimitive.content)
    }
}
