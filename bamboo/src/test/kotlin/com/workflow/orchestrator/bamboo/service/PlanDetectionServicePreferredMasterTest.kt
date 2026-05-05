package com.workflow.orchestrator.bamboo.service

import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.core.bitbucket.BitbucketBranchClient
import com.workflow.orchestrator.core.bitbucket.BitbucketBuildStatus
import com.workflow.orchestrator.core.model.ApiResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Verifies the multi-module disambiguation logic in PlanDetectionService.commitWalkPlanKey
 * (the Tier 1 path inside the 5-tier waterfall). When [PlanDetectionService.autoDetect] is
 * called with a non-null `preferredMaster`, T1 picks the Bitbucket build status whose
 * extracted plan key starts with it — instead of the unconditional `statuses.first()`.
 *
 * Behaviour added in commit `15bfd60d` ("autoDetectPlan accepts preferredMaster for
 * multi-module Tier 1 disambiguation"). Required by the BuildDashboardPanel rewrite to
 * deterministically resolve the correct branch plan in monorepos where one git remote
 * feeds multiple Bamboo plans.
 */
class PlanDetectionServicePreferredMasterTest {

    private val mockApi = mockk<BambooApiClient>(relaxed = true)
    private val mockBb = mockk<BitbucketBranchClient>()
    private val service = PlanDetectionService(mockApi).also {
        it.bbClientFactory = { mockBb }
        it.revListRunner = { _ -> listOf("abc1234567890") }
    }

    @Test
    fun `T1 picks status whose extracted plan key starts with preferredMaster`() = runTest {
        // Three statuses on one commit — multi-module monorepo scenario.
        val statuses = listOf(
            buildStatus("PROJ-WEB138-7"),
            buildStatus("PROJ-API138-4"),     // this should win when preferredMaster="PROJ-API"
            buildStatus("PROJ-WORKER138-2"),
        )
        coEvery { mockBb.getBuildStatuses("abc1234567890") } returns ApiResult.Success(statuses)
        coEvery { mockApi.validatePlan(any()) } returns ApiResult.Success(true)
        coEvery { mockApi.getPlanBranches(any()) } returns ApiResult.Success(emptyList())

        val result = service.autoDetect(
            repoRoot = Path.of("/tmp/dummy"),
            gitRemoteUrl = "https://example.invalid/repo.git",
            branchName = null,
            preferredMaster = "PROJ-API",
        )

        assertEquals(ApiResult.Success("PROJ-API138"), result,
            "Expected the API138 plan key to be picked because it matches preferredMaster")
    }

    @Test
    fun `T1 falls back to first status when preferredMaster matches none`() = runTest {
        val statuses = listOf(
            buildStatus("PROJ-WEB138-7"),
            buildStatus("PROJ-WORKER138-2"),
        )
        coEvery { mockBb.getBuildStatuses("abc1234567890") } returns ApiResult.Success(statuses)
        coEvery { mockApi.validatePlan(any()) } returns ApiResult.Success(true)
        coEvery { mockApi.getPlanBranches(any()) } returns ApiResult.Success(emptyList())

        val result = service.autoDetect(
            repoRoot = Path.of("/tmp/dummy"),
            gitRemoteUrl = "https://example.invalid/repo.git",
            branchName = null,
            preferredMaster = "PROJ-API",  // matches no status
        )

        assertEquals(ApiResult.Success("PROJ-WEB138"), result,
            "Expected fallback to first status (legacy behaviour preserved)")
    }

    @Test
    fun `T1 picks first status when preferredMaster is null (legacy behaviour)`() = runTest {
        val statuses = listOf(
            buildStatus("PROJ-WEB138-7"),
            buildStatus("PROJ-API138-4"),
        )
        coEvery { mockBb.getBuildStatuses("abc1234567890") } returns ApiResult.Success(statuses)
        coEvery { mockApi.validatePlan(any()) } returns ApiResult.Success(true)
        coEvery { mockApi.getPlanBranches(any()) } returns ApiResult.Success(emptyList())

        val result = service.autoDetect(
            repoRoot = Path.of("/tmp/dummy"),
            gitRemoteUrl = "https://example.invalid/repo.git",
            branchName = null,
            // no preferredMaster
        )

        assertEquals(ApiResult.Success("PROJ-WEB138"), result)
    }

    /**
     * Build a Bitbucket status whose key (e.g. `PROJ-API138-4`) is what
     * [BitbucketBranchClient.extractPlanKey] strips down to a digit-suffixed
     * branch plan key (e.g. `PROJ-API138`).
     */
    private fun buildStatus(key: String): BitbucketBuildStatus = BitbucketBuildStatus(
        state = "SUCCESSFUL",
        key = key,
        url = "https://bamboo.example.invalid/browse/$key",
    )
}
