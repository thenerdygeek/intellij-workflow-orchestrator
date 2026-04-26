package com.workflow.orchestrator.bamboo.workflow

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooResultDto
import com.workflow.orchestrator.bamboo.service.BambooServiceImpl
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Phase 5 Task 4 — verifies [LatestBuildLookupImpl] correctly bridges
 * [BambooApiClient.getLatestResult] into the cross-module [com.workflow.orchestrator.core.model.workflow.BuildRef]
 * DTO consumed by `WorkflowContextService.focusPr` cascade. Errors collapse to null so the
 * cascade can degrade gracefully.
 */
class LatestBuildLookupImplTest {

    @Test
    fun `success maps dto buildNumber into BuildRef preserving caller-supplied planKey and branch`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val bambooService = mockk<BambooServiceImpl>()
        val client = mockk<BambooApiClient>()

        every { project.getService(BambooServiceImpl::class.java) } returns bambooService
        every { bambooService.client } returns client
        coEvery { client.getLatestResult("PLAN-ALPHA", "develop") } returns ApiResult.Success(
            BambooResultDto(buildNumber = 123),
        )

        val ref = LatestBuildLookupImpl().fetchLatestBuild(project, "PLAN-ALPHA", "develop")

        assertEquals(
            com.workflow.orchestrator.core.model.workflow.BuildRef(
                planKey = "PLAN-ALPHA",
                buildNumber = 123,
                branch = "develop",
                selectedJobKey = null,
            ),
            ref,
        )
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

        val ref = LatestBuildLookupImpl().fetchLatestBuild(project, "PLAN-MISSING", "feature/x")

        assertNull(ref)
    }

    @Test
    fun `BambooServiceImpl unavailable returns null without invoking client`() = runTest {
        val project = mockk<Project>(relaxed = true)
        every { project.getService(BambooServiceImpl::class.java) } returns null

        val ref = LatestBuildLookupImpl().fetchLatestBuild(project, "PLAN-ALPHA", "develop")

        assertNull(ref)
    }

    @Test
    fun `client unavailable - bamboo not configured - returns null`() = runTest {
        val project = mockk<Project>(relaxed = true)
        val bambooService = mockk<BambooServiceImpl>()

        every { project.getService(BambooServiceImpl::class.java) } returns bambooService
        every { bambooService.client } returns null

        val ref = LatestBuildLookupImpl().fetchLatestBuild(project, "PLAN-ALPHA", "develop")

        assertNull(ref)
    }
}
