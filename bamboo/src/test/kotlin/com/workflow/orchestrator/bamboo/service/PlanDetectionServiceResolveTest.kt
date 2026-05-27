package com.workflow.orchestrator.bamboo.service

import com.workflow.orchestrator.bamboo.api.BambooApiClient
import com.workflow.orchestrator.bamboo.api.dto.BambooPlanBranch
import com.workflow.orchestrator.core.model.ApiResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class PlanDetectionServiceResolveTest {
    private val api = mockk<BambooApiClient>()
    private val svc = PlanDetectionService(api, null)

    @Test
    fun `resolveBranchKeyOrNull returns branch plan key from API`() = runTest {
        coEvery { api.getPlanBranches("PROJ-PLAN") } returns
            ApiResult.Success(listOf(BambooPlanBranch(key = "PROJ-PLAN138", shortName = "feature/x")))
        assertEquals("PROJ-PLAN138", svc.resolveBranchKeyOrNull("PROJ-PLAN", "feature/x"))
    }

    @Test
    fun `resolveBranchKeyOrNull returns null when no branch plan and not default`() = runTest {
        coEvery { api.getPlanBranches("PROJ-PLAN") } returns
            ApiResult.Success(listOf(BambooPlanBranch(key = "PROJ-PLAN138", shortName = "feature/x")))
        assertNull(svc.resolveBranchKeyOrNull("PROJ-PLAN", "feature/other"))
    }
}
