package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooResultDto
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
 * Phase D — chain-key-only shape for getLatestBuild.
 *
 * The old branch-resolution fallback has been deleted. [BambooServiceImpl.getLatestBuild]
 * now takes a single resolved chain key and makes exactly one API call.
 * No fallback to master, no branch-URL retries.
 *
 * Complemented by [BambooServiceShapeInvariantTest] which locks the API shape
 * at the Kotlin reflection level.
 */
class BambooServiceImplBranchFallbackTest {

    private val mockProject = mockk<Project>(relaxed = true)
    private val mockClient = mockk<BambooApiClient>()
    private val service = BambooServiceImpl(mockProject).also {
        it.testClientOverride = mockClient
    }

    private val sampleResult = BambooResultDto(
        key = "PROJ-PLAN523-42",
        buildResultKey = "PROJ-PLAN523-42",
        buildNumber = 42,
        state = "Successful",
        lifeCycleState = "Finished",
        buildDurationInSeconds = 120
    )

    /**
     * getLatestBuild(chainKey) makes exactly one API call to the chain key and
     * returns the build on success.
     */
    @Test
    fun `getLatestBuild with chain key returns build on success`() = runTest {
        coEvery { mockClient.getLatestResult("PROJ-PLAN523") } returns ApiResult.Success(sampleResult)

        val result = service.getLatestBuild("PROJ-PLAN523")

        assertFalse(result.isError, "Expected success, got: ${result.summary}")
        assertEquals(42, result.data!!.buildNumber)
        assertEquals("Successful", result.data!!.state)
        coVerify(exactly = 1) { mockClient.getLatestResult("PROJ-PLAN523") }
    }

    /**
     * getLatestBuild(chainKey) returns an error when the API call fails.
     * No second-chance retry, no fallback to a different key.
     */
    @Test
    fun `getLatestBuild propagates API error with no fallback`() = runTest {
        coEvery {
            mockClient.getLatestResult("PROJ-PLAN523")
        } returns ApiResult.Error(ErrorType.NOT_FOUND, "Chain not found")

        val result = service.getLatestBuild("PROJ-PLAN523")

        assert(result.isError) { "Expected error but got: ${result.summary}" }
        // Exactly one call — no retry with a different key
        coVerify(exactly = 1) { mockClient.getLatestResult("PROJ-PLAN523") }
    }

    /**
     * getLatestBuild(chainKey) with a non-404 error also returns immediately with no fallback.
     */
    @Test
    fun `getLatestBuild returns error on non-404 without any fallback attempt`() = runTest {
        coEvery {
            mockClient.getLatestResult("PROJ-PLAN523")
        } returns ApiResult.Error(ErrorType.FORBIDDEN, "Insufficient permissions")

        val result = service.getLatestBuild("PROJ-PLAN523")

        assert(result.isError) { "Expected error but got: ${result.summary}" }
        coVerify(exactly = 1) { mockClient.getLatestResult("PROJ-PLAN523") }
    }
}
