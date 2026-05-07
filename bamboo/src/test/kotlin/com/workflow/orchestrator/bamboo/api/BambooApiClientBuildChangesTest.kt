package com.workflow.orchestrator.bamboo.api

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for getBuildChanges (§8.8 / R-ADD-1 of the 2026-05-07 Bamboo audit).
 * Response shape validated against bundle-repo.unpacked/raw/result_changes.json.
 */
class BambooApiClientBuildChangesTest {

    private lateinit var server: MockWebServer
    private lateinit var client: BambooApiClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = BambooApiClient(
            baseUrl = server.url("/").toString().trimEnd('/'),
            tokenProvider = { "test-token" }
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `getBuildChanges parses changes change array with all fields`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{
                  "buildResultKey": "PROJ-PLAN-42",
                  "changes": {
                    "size": 2,
                    "change": [
                      {
                        "userName": "jsmith",
                        "fullName": "John Smith",
                        "comment": "fix: correct off-by-one in loop",
                        "changesetId": "deadbeef1234567890abcdef",
                        "commitUrl": "https://bitbucket.example.com/commits/deadbeef",
                        "date": "2026-05-06T18:12:42.000+02:00"
                      },
                      {
                        "userName": "alee",
                        "fullName": "Alice Lee",
                        "comment": "chore: bump dependency versions",
                        "changesetId": "cafebabe9876543210fedcba",
                        "commitUrl": "https://bitbucket.example.com/commits/cafebabe",
                        "date": "2026-05-06T17:55:10.000+02:00"
                      }
                    ]
                  }
                }""".trimIndent()
            )
        )

        val result = client.getBuildChanges("PROJ-PLAN-42")

        assertTrue(result.isSuccess)
        val changes = (result as ApiResult.Success).data
        assertEquals(2, changes.size)

        assertEquals("jsmith", changes[0].userName)
        assertEquals("John Smith", changes[0].fullName)
        assertEquals("fix: correct off-by-one in loop", changes[0].comment)
        assertEquals("deadbeef1234567890abcdef", changes[0].changesetId)
        assertEquals("https://bitbucket.example.com/commits/deadbeef", changes[0].commitUrl)
        assertEquals("2026-05-06T18:12:42.000+02:00", changes[0].date)

        assertEquals("alee", changes[1].userName)
        assertEquals("cafebabe9876543210fedcba", changes[1].changesetId)

        val recorded = server.takeRequest()
        assertTrue(recorded.path!!.contains("/rest/api/latest/result/PROJ-PLAN-42"))
        assertTrue(recorded.path!!.contains("expand=changes.change"))
        assertEquals("Bearer test-token", recorded.getHeader("Authorization"))
    }

    @Test
    fun `getBuildChanges returns empty list when no changes`() = runTest {
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"changes":{"size":0,"change":[]}}"""
            )
        )

        val result = client.getBuildChanges("PROJ-PLAN-99")

        assertTrue(result.isSuccess)
        assertTrue((result as ApiResult.Success).data.isEmpty())
    }

    @Test
    fun `getBuildChanges handles password-field variables gracefully (no value field)`() = runTest {
        // Edge case: a change entry with only required fields (no optional commitUrl/date)
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{
                  "changes": {
                    "size": 1,
                    "change": [
                      {
                        "userName": "nts46083",
                        "fullName": "Rohit Pokala",
                        "comment": "refactor: extract service layer",
                        "changesetId": "96ff8ff585c8da9ade43a7f2860235415962070c"
                      }
                    ]
                  }
                }""".trimIndent()
            )
        )

        val result = client.getBuildChanges("PROJ-PLAN-42")

        assertTrue(result.isSuccess)
        val changes = (result as ApiResult.Success).data
        assertEquals(1, changes.size)
        assertEquals("nts46083", changes[0].userName)
        assertEquals("96ff8ff585c8da9ade43a7f2860235415962070c", changes[0].changesetId)
        // Optional fields default to empty string
        assertEquals("", changes[0].commitUrl)
        assertEquals("", changes[0].date)
    }

    @Test
    fun `getBuildChanges returns Error on 404`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"not found"}"""))

        val result = client.getBuildChanges("PROJ-PLAN-NOPE")

        assertTrue(result is ApiResult.Error)
        assertEquals(ErrorType.NOT_FOUND, (result as ApiResult.Error).type)
    }

    @Test
    fun `getBuildChanges returns Error on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = client.getBuildChanges("PROJ-PLAN-42")

        assertTrue(result is ApiResult.Error)
        assertEquals(ErrorType.AUTH_FAILED, (result as ApiResult.Error).type)
    }
}
