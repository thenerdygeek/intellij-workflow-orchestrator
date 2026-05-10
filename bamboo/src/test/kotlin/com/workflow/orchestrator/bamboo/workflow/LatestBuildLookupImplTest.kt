package com.workflow.orchestrator.bamboo.workflow

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooResultDto
import com.workflow.orchestrator.bamboo.service.BambooServiceImpl
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Phase A — verifies [LatestBuildLookupImpl] correctly bridges
 * [BambooApiClient.getLatestResult] (the unbranched `/result/{chainKey}/latest` form) into
 * the cross-module [com.workflow.orchestrator.core.model.workflow.BuildRef] DTO consumed by
 * `WorkflowContextService.focusPr` cascade. Errors collapse to null so the cascade can
 * degrade gracefully.
 */
class LatestBuildLookupImplTest {

    @Test
    fun `success maps dto buildNumber into BuildRef preserving caller-supplied chainKey`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val bambooService = mockk<BambooServiceImpl>()
        val client = mockk<BambooApiClient>()

        every { project.getService(BambooServiceImpl::class.java) } returns bambooService
        every { bambooService.client } returns client
        // Unbranched form — branch arg defaults to null so the URL is /result/{chainKey}/latest.
        coEvery { client.getLatestResult("PROJ-PLAN523", null) } returns ApiResult.Success(
            BambooResultDto(buildNumber = 123),
        )

        val ref = LatestBuildLookupImpl().fetchLatestBuild(project, "PROJ-PLAN523")

        assertNotNull(ref)
        assertEquals("PROJ-PLAN523", ref!!.planKey)
        assertEquals("PROJ-PLAN523", ref.chainKey)
        assertEquals(123, ref.buildNumber)
        // EP impl leaves branch label blank; the cascade overwrites with PR.fromBranch.
        assertEquals("", ref.branch)
        assertNull(ref.selectedJobKey)
    }

    @Test
    fun `ApiResult Error returns null so focus cascade can degrade gracefully`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val bambooService = mockk<BambooServiceImpl>()
        val client = mockk<BambooApiClient>()

        every { project.getService(BambooServiceImpl::class.java) } returns bambooService
        every { bambooService.client } returns client
        coEvery { client.getLatestResult(any(), any()) } returns ApiResult.Error(
            ErrorType.NOT_FOUND,
            "Bamboo resource not found",
        )

        val ref = LatestBuildLookupImpl().fetchLatestBuild(project, "PROJ-MISSING-7")

        assertNull(ref)
    }

    @Test
    fun `BambooServiceImpl unavailable returns null without invoking client`() = runTest {
        val project = mockk<Project>(relaxed = true)
        every { project.getService(BambooServiceImpl::class.java) } returns null

        val ref = LatestBuildLookupImpl().fetchLatestBuild(project, "PROJ-PLAN523")

        assertNull(ref)
    }

    @Test
    fun `client unavailable - bamboo not configured - returns null`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val bambooService = mockk<BambooServiceImpl>()

        every { project.getService(BambooServiceImpl::class.java) } returns bambooService
        every { bambooService.client } returns null

        val ref = LatestBuildLookupImpl().fetchLatestBuild(project, "PROJ-PLAN523")

        assertNull(ref)
    }

    @Test
    fun `blank chainKey short-circuits to null without invoking client`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val bambooService = mockk<BambooServiceImpl>()
        val client = mockk<BambooApiClient>()

        every { project.getService(BambooServiceImpl::class.java) } returns bambooService
        every { bambooService.client } returns client

        val ref = LatestBuildLookupImpl().fetchLatestBuild(project, "")

        assertNull(ref)
        coVerify(exactly = 0) { client.getLatestResult(any(), any()) }
    }

    /**
     * URL-shape check via [MockWebServer]: confirms the EP impl hits the unbranched
     * `/result/{chainKey}/latest` endpoint, never `/branch/{name}/latest`. Pins the
     * Phase A invariant — once [com.workflow.orchestrator.core.workflow.ChainKeyResolver]
     * resolves a chain key, the build lookup must address it directly.
     */
    @Test
    fun `URL is unbranched - calls result chainKey latest with no branch path`() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setBody("""{"buildNumber":555,"key":"PROJ-PLAN523-555"}"""))

            val realClient = BambooApiClient(
                baseUrl = server.url("/").toString().trimEnd('/'),
                tokenProvider = { "test-token" },
            )
            val project = mockk<Project>(relaxed = true)
            val bambooService = mockk<BambooServiceImpl>()
            every { project.getService(BambooServiceImpl::class.java) } returns bambooService
            every { bambooService.client } returns realClient

            val ref = LatestBuildLookupImpl().fetchLatestBuild(project, "PROJ-PLAN523")

            assertNotNull(ref)
            assertEquals(555, ref!!.buildNumber)

            val recorded = server.takeRequest()
            assertTrue(
                recorded.path!!.contains("/rest/api/latest/result/PROJ-PLAN523/latest"),
                "Expected unbranched /result/{chainKey}/latest URL, got: ${recorded.path}",
            )
            assertTrue(
                !recorded.path!!.contains("/branch/"),
                "Phase A: URL must NOT contain `/branch/...` segment, got: ${recorded.path}",
            )
        } finally {
            server.shutdown()
        }
    }
}
