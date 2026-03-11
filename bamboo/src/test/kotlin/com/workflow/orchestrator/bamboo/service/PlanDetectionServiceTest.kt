package com.workflow.orchestrator.bamboo.service

import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooPlanDto
import com.workflow.orchestrator.bamboo.api.dto.BambooSearchEntity
import com.workflow.orchestrator.core.model.ApiResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class PlanDetectionServiceTest {

    private val apiClient = mockk<BambooApiClient>()
    private val service = PlanDetectionService(apiClient)

    @Test
    fun `normalizeUrl strips git suffix and protocol`() {
        assertEquals(
            "bitbucket.org/mycompany/myrepo",
            PlanDetectionService.normalizeRepoUrl("ssh://git@bitbucket.org:mycompany/myrepo.git")
        )
        assertEquals(
            "bitbucket.org/mycompany/myrepo",
            PlanDetectionService.normalizeRepoUrl("https://bitbucket.org/mycompany/myrepo.git")
        )
        assertEquals(
            "bitbucket.org/mycompany/myrepo",
            PlanDetectionService.normalizeRepoUrl("git@bitbucket.org:mycompany/myrepo")
        )
    }

    @Test
    fun `autoDetect returns plan key when single match found`() = runTest {
        val plans = listOf(
            BambooPlanDto(key = "PROJ-BUILD", name = "Build"),
            BambooPlanDto(key = "PROJ-DEPLOY", name = "Deploy")
        )
        coEvery { apiClient.getPlans() } returns ApiResult.Success(plans)
        coEvery { apiClient.getPlanSpecs("PROJ-BUILD") } returns ApiResult.Success(
            """
            repositories:
              - my-repo:
                  type: git
                  url: ssh://git@bitbucket.org:mycompany/myrepo.git
            """.trimIndent()
        )
        coEvery { apiClient.getPlanSpecs("PROJ-DEPLOY") } returns ApiResult.Success(
            """
            repositories:
              - other-repo:
                  type: git
                  url: ssh://git@bitbucket.org:mycompany/other-repo.git
            """.trimIndent()
        )

        val result = service.autoDetect("https://bitbucket.org/mycompany/myrepo.git")

        assertTrue(result.isSuccess)
        assertEquals("PROJ-BUILD", (result as ApiResult.Success).data)
    }

    @Test
    fun `autoDetect returns NOT_FOUND when no match`() = runTest {
        coEvery { apiClient.getPlans() } returns ApiResult.Success(emptyList())

        val result = service.autoDetect("https://bitbucket.org/mycompany/myrepo.git")

        assertTrue(result.isError)
    }

    @Test
    fun `search delegates to api client`() = runTest {
        val entities = listOf(
            BambooSearchEntity(key = "PROJ-BUILD", planName = "Build", projectName = "My Project")
        )
        coEvery { apiClient.searchPlans("Build") } returns ApiResult.Success(entities)

        val result = service.search("Build")

        assertTrue(result.isSuccess)
        assertEquals(1, (result as ApiResult.Success).data.size)
    }
}
