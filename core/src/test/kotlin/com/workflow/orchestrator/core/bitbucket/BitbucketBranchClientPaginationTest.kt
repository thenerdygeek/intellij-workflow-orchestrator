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

class BitbucketBranchClientPaginationTest {
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
    fun `getPullRequestChanges aggregates across pages until isLastPage`() = runTest {
        // Page 1: 2 items, not last
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"values":[
              {"path":{"toString":"a.kt"},"type":"MODIFY"},
              {"path":{"toString":"b.kt"},"type":"ADD"}
            ],"isLastPage":false,"nextPageStart":2}""".trimIndent()
        ))
        // Page 2: 1 item, last
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"values":[
              {"path":{"toString":"c.kt"},"type":"DELETE"}
            ],"isLastPage":true}""".trimIndent()
        ))

        val result = client.getPullRequestChanges("P", "R", 1)
        assertTrue(result is ApiResult.Success, "expected Success, got $result")
        val changes = (result as ApiResult.Success).data
        assertEquals(3, changes.values.size)
        assertEquals(listOf("a.kt", "b.kt", "c.kt"), changes.values.map { it.path.toString })

        val req1 = server.takeRequest()
        assertTrue(!req1.path!!.contains("start=") || req1.path!!.contains("start=0"),
            "page 1 req was ${req1.path}")
        val req2 = server.takeRequest()
        assertTrue(req2.path!!.contains("start=2"), "page 2 req was ${req2.path}")
    }

    @Test
    fun `getPullRequestChanges single page happy path`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"values":[{"path":{"toString":"a.kt"},"type":"MODIFY"}],"isLastPage":true}"""
        ))
        val result = client.getPullRequestChanges("P", "R", 1)
        assertTrue(result is ApiResult.Success)
        assertEquals(1, (result as ApiResult.Success).data.values.size)
    }

    @Test
    fun `getPullRequestChanges preserves srcPath for renames`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"values":[
              {"path":{"toString":"new/Foo.kt"},"srcPath":{"toString":"old/Foo.kt"},"type":"RENAME"}
            ],"isLastPage":true}""".trimIndent()
        ))
        val result = client.getPullRequestChanges("P", "R", 1)
        assertTrue(result is ApiResult.Success)
        val values = (result as ApiResult.Success).data.values
        assertEquals(1, values.size)
        assertEquals("old/Foo.kt", values[0].srcPath?.toString)
    }

    @Test
    fun `getPullRequestActivities aggregates across pages`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"values":[
              {"id":1,"action":"COMMENTED","user":{"name":"alice"}},
              {"id":2,"action":"APPROVED","user":{"name":"bob"}}
            ],"isLastPage":false,"nextPageStart":2}""".trimIndent()
        ))
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"values":[{"id":3,"action":"MERGED","user":{"name":"charlie"}}],"isLastPage":true}"""
        ))
        val result = client.getPullRequestActivities("P", "R", 1)
        assertTrue(result is ApiResult.Success, "expected Success, got $result")
        val values = (result as ApiResult.Success).data.values
        assertEquals(3, values.size)

        val req1 = server.takeRequest()
        assertTrue(!req1.path!!.contains("start=") || req1.path!!.contains("start=0"))
        val req2 = server.takeRequest()
        assertTrue(req2.path!!.contains("start=2"), "page 2 req was ${req2.path}")
    }

    @Test
    fun `getPullRequestActivities single page happy path`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(
            """{"values":[{"id":1,"action":"COMMENTED","user":{"name":"alice"}}],"isLastPage":true}"""
        ))
        val result = client.getPullRequestActivities("P", "R", 1)
        assertTrue(result is ApiResult.Success)
        assertEquals(1, (result as ApiResult.Success).data.values.size)
    }
}
