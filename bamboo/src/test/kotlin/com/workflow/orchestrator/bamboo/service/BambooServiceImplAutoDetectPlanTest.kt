package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooPlanDto
import com.workflow.orchestrator.core.model.ApiResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BambooServiceImplAutoDetectPlanTest {

    private val mockProject = mockk<Project>(relaxed = true)
    private val mockClient = mockk<BambooApiClient>()
    private val service = BambooServiceImpl(mockProject).also {
        it.testClientOverride = mockClient
    }

    @Test
    fun `blank URL returns error ToolResult with empty data`() = runTest {
        val result = service.autoDetectPlan("")

        assertTrue(result.isError)
        assertEquals("", result.data)
    }

    @Test
    fun `single matching plan returns success ToolResult with plan key`() = runTest {
        val plans = listOf(
            BambooPlanDto(key = "PROJ-BUILD", name = "Build")
        )
        coEvery { mockClient.getPlans() } returns ApiResult.Success(plans)
        coEvery { mockClient.getPlanSpecs("PROJ-BUILD") } returns ApiResult.Success(
            """
            repositories:
              - my-repo:
                  type: git
                  url: ssh://git@bitbucket.org:mycompany/myrepo.git
            """.trimIndent()
        )

        val result = service.autoDetectPlan("https://bitbucket.org/mycompany/myrepo.git")

        assertFalse(result.isError)
        assertEquals("PROJ-BUILD", result.data)
        assertTrue(result.summary.contains("PROJ-BUILD"))
    }

    @Test
    fun `no matching plan returns error ToolResult with empty data`() = runTest {
        coEvery { mockClient.getPlans() } returns ApiResult.Success(emptyList())

        val result = service.autoDetectPlan("https://bitbucket.org/mycompany/myrepo.git")

        assertTrue(result.isError)
        assertEquals("", result.data)
    }
}
