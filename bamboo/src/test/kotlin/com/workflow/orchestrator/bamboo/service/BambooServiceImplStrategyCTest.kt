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
 * Regression test for audit finding bamboo:F-6 — Strategy C variable fallback.
 *
 * When variableContext fails and Strategy C falls back to last build's variables,
 * the returned PlanVariableData must have isPassword=true (fail-safe default) because
 * the build-level variable endpoint carries no classification metadata.
 */
class BambooServiceImplStrategyCTest {

    private val mockProject = mockk<Project>(relaxed = true)
    private val mockClient = mockk<BambooApiClient>()
    private val service = BambooServiceImpl(mockProject).also {
        it.testClientOverride = mockClient
    }

    @Test
    fun `Strategy C returns variables with isPassword=true fail-safe default`() = runTest {
        // variableContext fails — forces Strategy C
        coEvery { mockClient.getPlanVariableContext("PROJ-PLAN") } returns
            ApiResult.Error(ErrorType.NOT_FOUND, "variableContext not available")

        // Strategy C: getRecentResults succeeds with one build (buildNumber=42)
        coEvery { mockClient.getRecentResults("PROJ-PLAN", maxResults = 1) } returns
            ApiResult.Success(listOf(BambooResultDto(buildNumber = 42)))

        // getBuildVariables is called with "PROJ-PLAN-42" (planKey + "-" + buildNumber)
        coEvery { mockClient.getBuildVariables("PROJ-PLAN-42") } returns
            ApiResult.Success(mapOf(
                "deploy.token" to "s3cr3t",
                "build.mode" to "release"
            ))

        val result = service.getPlanVariables("PROJ-PLAN")
        assertFalse(result.isError, "Strategy C should succeed, got: ${result.summary}")
        val vars = result.data!!
        assertEquals(2, vars.size)
        // All Strategy C variables must default to isPassword=true (fail-safe)
        for (v in vars) {
            assertTrue(v.isPassword, "Variable '${v.name}' must default to isPassword=true in Strategy C path")
        }
    }
}
