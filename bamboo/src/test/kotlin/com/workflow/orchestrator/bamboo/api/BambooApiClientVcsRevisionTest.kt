package com.workflow.orchestrator.bamboo.api

import com.workflow.orchestrator.core.model.ApiResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for the Bamboo→Bitbucket bridge helper added by the 2026-05-07 audit
 * (R-ADD-5). `getResultVcsRevision` extracts the first VCS revision (commit
 * SHA) recorded against a build result so the bridge listener can map a failed
 * build back to the PRs containing that commit.
 */
class BambooApiClientVcsRevisionTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BambooApiClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = BambooApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" },
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getResultVcsRevision returns first vcsRevisionKey when populated`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{
                  "vcsRevisions":{
                    "size":1,
                    "vcsRevision":[
                      {"repositoryId":1,"repositoryName":"my-repo","vcsRevisionKey":"deadbeefcafebabe1234"}
                    ]
                  }
                }""".trimIndent()
            )
        )
        val result = client.getResultVcsRevision("PROJ-PLAN-42")
        assertTrue(result is ApiResult.Success)
        assertEquals("deadbeefcafebabe1234", (result as ApiResult.Success).data)

        val req = server.takeRequest()
        assertTrue(req.path!!.contains("/result/PROJ-PLAN-42"))
        assertTrue(req.path!!.contains("expand=vcsRevisions"))
    }

    @Test
    fun `getResultVcsRevision returns null when no vcsRevisions are recorded`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"vcsRevisions":{"size":0,"vcsRevision":[]}}"""
            )
        )
        val result = client.getResultVcsRevision("PROJ-PLAN-99")
        assertTrue(result is ApiResult.Success)
        assertNull((result as ApiResult.Success).data)
    }

    @Test
    fun `getResultVcsRevision surfaces non-200 as Error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"no result"}"""))
        val result = client.getResultVcsRevision("PROJ-PLAN-NOPE")
        assertTrue(result is ApiResult.Error)
    }
}
