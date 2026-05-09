package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooResultDto
import com.workflow.orchestrator.bamboo.api.dto.BambooBranchDto
import com.workflow.orchestrator.bamboo.api.dto.BambooBranchListResponse
import com.workflow.orchestrator.bamboo.api.dto.BambooBranchCollection
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * §8.2 — branch-fallback for getLatestBuild when the requested branch IS the
 * master plan's tracked branch (Bamboo returns 404 for /branch/{name}/latest in that case).
 *
 * Spec: docs/research/2026-05-07-bamboo-audit-recommendations.md §4.1 + §8.2
 */
class BambooServiceImplBranchFallbackTest {

    private val mockProject = mockk<Project>(relaxed = true)
    private val mockClient = mockk<BambooApiClient>()
    private val service = BambooServiceImpl(mockProject).also {
        it.testClientOverride = mockClient
    }

    private val sampleResult = BambooResultDto(
        key = "PROJ-PLAN-42",
        buildResultKey = "PROJ-PLAN-42",
        buildNumber = 42,
        state = "Successful",
        lifeCycleState = "Finished",
        buildDurationInSeconds = 120
    )

    /**
     * When getBranches returns branches but none match the requested branch name,
     * AND /branch/{name}/latest returns 404 (because 'develop' IS the master's tracked branch),
     * the service should fall back to /result/{planKey}/latest and return success.
     */
    @Test
    fun `getLatestBuild falls back to unbranched latest when branch URL returns 404`() = runTest {
        // Simulate: branch 'develop' not in branch list (it IS the master's branch, so no child plan)
        coEvery { mockClient.getBranches("PROJ-PLAN") } returns ApiResult.Success(emptyList())
        // Branch-specific URL returns 404
        coEvery {
            mockClient.getLatestResult("PROJ-PLAN", "develop")
        } returns ApiResult.Error(ErrorType.NOT_FOUND, "Bamboo resource not found")
        // Master /latest returns the build (because master tracks 'develop')
        coEvery { mockClient.getLatestResult("PROJ-PLAN") } returns ApiResult.Success(sampleResult)

        val result = service.getLatestBuild("PROJ-PLAN", branch = "develop")

        assertFalse(result.isError, "Expected success after fallback, got: ${result.summary}")
        assertEquals(42, result.data!!.buildNumber)
        assertEquals("Successful", result.data!!.state)

        // Verify the unbranched fallback was called
        coVerify { mockClient.getLatestResult("PROJ-PLAN") }
    }

    /**
     * When the branch-specific URL 404s AND the unbranched /latest also fails,
     * the service should return the error from the unbranched call.
     */
    @Test
    fun `getLatestBuild propagates error when both branch and unbranched calls fail`() = runTest {
        coEvery { mockClient.getBranches("PROJ-PLAN") } returns ApiResult.Success(emptyList())
        coEvery {
            mockClient.getLatestResult("PROJ-PLAN", "develop")
        } returns ApiResult.Error(ErrorType.NOT_FOUND, "Bamboo resource not found")
        coEvery {
            mockClient.getLatestResult("PROJ-PLAN")
        } returns ApiResult.Error(ErrorType.SERVER_ERROR, "Bamboo returned 500")

        val result = service.getLatestBuild("PROJ-PLAN", branch = "develop")

        assert(result.isError) { "Expected error but got: ${result.summary}" }
    }

    /**
     * When the branch-specific URL returns a non-404 error (e.g. 403 Forbidden),
     * the service must NOT attempt the unbranched fallback — 403 is a different problem.
     */
    @Test
    fun `getLatestBuild does not fall back to unbranched on non-404 errors`() = runTest {
        coEvery { mockClient.getBranches("PROJ-PLAN") } returns ApiResult.Success(emptyList())
        coEvery {
            mockClient.getLatestResult("PROJ-PLAN", "develop")
        } returns ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient Bamboo permissions")

        val result = service.getLatestBuild("PROJ-PLAN", branch = "develop")

        assert(result.isError) { "Expected error but got: ${result.summary}" }
        // Verify the unbranched endpoint was NOT called
        coVerify(exactly = 0) { mockClient.getLatestResult("PROJ-PLAN") }
    }

    /**
     * Happy path: when branch resolution succeeds (branch IS in the branches list),
     * no fallback is triggered at all.
     */
    @Test
    fun `getLatestBuild uses resolved branch key when branch is in list`() = runTest {
        val branchDto = BambooBranchDto(key = "PROJ-PLAN-7", name = "feature/foo")
        coEvery { mockClient.getBranches("PROJ-PLAN") } returns ApiResult.Success(listOf(branchDto))
        coEvery { mockClient.getLatestResult("PROJ-PLAN-7") } returns ApiResult.Success(sampleResult)

        val result = service.getLatestBuild("PROJ-PLAN", branch = "feature/foo")

        assertFalse(result.isError, "Expected success, got: ${result.summary}")
        coVerify { mockClient.getLatestResult("PROJ-PLAN-7") }
        // No branch-URL fallback needed
        coVerify(exactly = 0) { mockClient.getLatestResult("PROJ-PLAN", "feature/foo") }
        coVerify(exactly = 0) { mockClient.getLatestResult("PROJ-PLAN") }
    }
}
