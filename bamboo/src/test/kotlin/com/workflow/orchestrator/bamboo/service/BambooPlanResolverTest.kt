package com.workflow.orchestrator.bamboo.service

import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooPlanBranch
import com.workflow.orchestrator.bamboo.model.BambooPlanRef
import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BambooPlanResolverTest {
    private val api = mockk<BambooApiClient>()
    private val resolver = BambooPlanResolver(api)

    private fun branches(vararg pairs: Pair<String, String>) =
        ApiResult.Success(pairs.map { (key, sn) -> BambooPlanBranch(key = key, shortName = sn) })

    @Test fun `child branch plan resolves to BranchPlan with API key`() = runTest {
        coEvery { api.getPlanBranches("PROJ-PLAN") } returns branches("PROJ-PLAN138" to "feature/x")
        val ref = resolver.resolve("PROJ-PLAN", "feature/x", repoDefaultBranch = "develop")
        assertEquals(BambooPlanRef.BranchPlan("PROJ-PLAN138", "PROJ-PLAN", "feature/x"), ref)
    }

    @Test fun `master-tracked default branch resolves to MasterTrackedBranch`() = runTest {
        coEvery { api.getPlanBranches("PROJ-PLAN") } returns branches("PROJ-PLAN138" to "feature/x")
        val ref = resolver.resolve("PROJ-PLAN", "develop", repoDefaultBranch = "develop")
        assertEquals(BambooPlanRef.MasterTrackedBranch("PROJ-PLAN", "develop"), ref)
    }

    @Test fun `no branch plan and not default branch resolves to null`() = runTest {
        coEvery { api.getPlanBranches("PROJ-PLAN") } returns branches("PROJ-PLAN138" to "feature/x")
        assertNull(resolver.resolve("PROJ-PLAN", "feature/other", repoDefaultBranch = "develop"))
    }

    @Test fun `null default branch never produces MasterTrackedBranch`() = runTest {
        coEvery { api.getPlanBranches("PROJ-PLAN") } returns branches("PROJ-PLAN138" to "feature/x")
        assertNull(resolver.resolve("PROJ-PLAN", "develop", repoDefaultBranch = null))
    }

    @Test fun `blank branch resolves to Master`() = runTest {
        assertEquals(BambooPlanRef.Master("PROJ-PLAN"), resolver.resolve("PROJ-PLAN", "", repoDefaultBranch = null))
    }

    @Test fun `getPlanBranches error resolves to null`() = runTest {
        coEvery { api.getPlanBranches("PROJ-PLAN") } returns ApiResult.Error(ErrorType.NETWORK_ERROR, "down")
        assertNull(resolver.resolve("PROJ-PLAN", "feature/x", repoDefaultBranch = "develop"))
    }
}
