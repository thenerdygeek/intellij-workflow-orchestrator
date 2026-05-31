package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooResultDto
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

/**
 * Tests for the all-strategies-fail path in [BambooServiceImpl.getPlanVariables].
 *
 * Coverage gaps addressed (BAMBOO-COV-9):
 * 1. variableContext returns an API error AND Strategy C also returns an error → isError=true.
 * 2. variableContext returns ApiResult.Success(emptyList()) AND getRecentResults returns an
 *    empty list (no builds to fall back to) → isError=true with "variableContext returned empty"
 *    in the summary.
 *
 * The existing [BambooServiceImplStrategyCTest] covers the A-fails→C-succeeds branch. These
 * tests cover the remaining path where both strategies fail.
 */
class BambooServiceImplPlanVariablesAllFailTest {

    private val mockProject = mockk<Project>(relaxed = true)
    private val mockClient = mockk<BambooApiClient>()
    private val service = BambooServiceImpl(mockProject).also {
        it.testClientOverride = mockClient
    }

    @Test
    fun `getPlanVariables returns isError when variableContext errors and Strategy C also errors`() = runTest {
        // Strategy A fails with a network error.
        coEvery { mockClient.getPlanVariableContext("PROJ-PLAN") } returns
            ApiResult.Error(ErrorType.NETWORK_ERROR, "Connection refused")

        // Strategy C: getRecentResults also fails.
        coEvery { mockClient.getRecentResults("PROJ-PLAN", maxResults = 1) } returns
            ApiResult.Error(ErrorType.NETWORK_ERROR, "Connection refused")

        val result = service.getPlanVariables("PROJ-PLAN")

        assertTrue(result.isError,
            "getPlanVariables should return isError=true when all strategies fail")
        assertNotNull(result.hint,
            "A hint should be provided when all strategies fail")
    }

    @Test
    fun `getPlanVariables returns isError when variableContext succeeds with empty list and no recent builds`() = runTest {
        // Strategy A succeeds but returns empty list → falls through to Strategy C.
        coEvery { mockClient.getPlanVariableContext("PROJ-PLAN") } returns
            ApiResult.Success(emptyList())

        // Strategy C: getRecentResults returns empty list (no builds at all).
        coEvery { mockClient.getRecentResults("PROJ-PLAN", maxResults = 1) } returns
            ApiResult.Success(emptyList())

        val result = service.getPlanVariables("PROJ-PLAN")

        assertTrue(result.isError,
            "getPlanVariables should return isError=true when variableContext is empty and no recent builds exist")
        // The error message documents that variableContext returned empty.
        assertTrue(
            result.summary.contains("variableContext returned empty", ignoreCase = true) ||
                result.summary.contains("Error fetching", ignoreCase = true),
            "Summary should indicate the failure mode, got: '${result.summary}'"
        )
    }

    @Test
    fun `getPlanVariables returns isError when variableContext empty and Strategy C getBuildVariables fails`() = runTest {
        // Strategy A empty → Strategy C tries, finds a build, but getBuildVariables fails.
        coEvery { mockClient.getPlanVariableContext("PROJ-PLAN") } returns
            ApiResult.Success(emptyList())

        coEvery { mockClient.getRecentResults("PROJ-PLAN", maxResults = 1) } returns
            ApiResult.Success(listOf(BambooResultDto(buildNumber = 99)))

        coEvery { mockClient.getBuildVariables("PROJ-PLAN-99") } returns
            ApiResult.Error(ErrorType.NOT_FOUND, "Build variables not available")

        val result = service.getPlanVariables("PROJ-PLAN")

        assertTrue(result.isError,
            "getPlanVariables should return isError=true when getBuildVariables also fails")
    }
}
