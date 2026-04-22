package com.workflow.orchestrator.core.bitbucket

import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.test.runTest
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

    @Test
    fun `listPrComments aggregates across pages`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"values":[{"id":1,"version":0,"text":"a"},{"id":2,"version":0,"text":"b"}],"isLastPage":false,"nextPageStart":2}"""
        ))
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"values":[{"id":3,"version":0,"text":"c"}],"isLastPage":true}"""
        ))
        val result = client.listPrComments("P", "R", 1)
        assertTrue(result is ApiResult.Success)
        val values = (result as ApiResult.Success).data.values
        assertEquals(3, values.size)
        assertEquals(listOf(1L, 2L, 3L), values.map { it.id })

        val req1 = server.takeRequest()
        assertTrue(!req1.path!!.contains("start=") || req1.path!!.contains("start=0"))
        val req2 = server.takeRequest()
        assertTrue(req2.path!!.contains("start=2"))
    }

    @Test
    fun `listPrComments single page happy path`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"values":[{"id":1,"version":0,"text":"hi"}],"isLastPage":true}"""
        ))
        val result = client.listPrComments("P", "R", 1)
        assertTrue(result is ApiResult.Success)
        assertEquals(1, (result as ApiResult.Success).data.values.size)
    }

    @Test
    fun `listPrComments uses GET and Accept application_json and correct path`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"values":[],"isLastPage":true}"""))
        client.listPrComments("P", "R", 1)
        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertTrue(req.getHeader("Accept")!!.contains("application/json"))
        assertTrue(req.path!!.contains("/pull-requests/1/comments"))
    }
}
