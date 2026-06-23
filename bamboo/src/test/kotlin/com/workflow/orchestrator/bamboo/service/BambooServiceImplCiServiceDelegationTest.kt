package com.workflow.orchestrator.bamboo.service

import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.model.bamboo.PlanData
import com.workflow.orchestrator.core.model.bamboo.ProjectData
import com.workflow.orchestrator.core.services.CiService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.spyk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

/**
 * Phase 0b-2: verifies [BambooServiceImpl] satisfies the neutral [CiService] seam and that each
 * renamed/reshaped CiService method delegates to the correct [BambooService] method. Delegation is
 * tested at the BambooService boundary via spyk (NOT at the API-client level) so this stays
 * independent of BambooApiClient internals; the PlanData->PipelineData field mapping is covered by
 * CiModelsTest.
 */
class BambooServiceImplCiServiceDelegationTest {

    private val service = spyk(BambooServiceImpl(mockk<Project>(relaxed = true)))
    private val ci: CiService = service

    @Test fun `BambooServiceImpl is a CiService`() {
        // Compile-time IS-A is the primary guarantee; this asserts the runtime upcast holds.
        assertEquals(true, ci is BambooServiceImpl)
    }

    @Test fun `getPipelines delegates to getPlans and maps to PipelineData`() = runTest {
        coEvery { service.getPlans() } returns ToolResult.success(
            listOf(PlanData("G-AUTO", "Auto", "Auto", "G", "Group", enabled = true)),
            "ok",
        )

        val result = ci.getPipelines()

        assertFalse(result.isError)
        val p = result.data!!.single()
        assertEquals("G-AUTO", p.key)
        assertEquals("G", p.groupKey) // projectKey -> groupKey
        assertEquals("Group", p.groupName) // projectName -> groupName
        coVerify(exactly = 1) { service.getPlans() }
    }

    @Test fun `getPipelinesForGroup delegates to getProjectPlans`() = runTest {
        coEvery { service.getProjectPlans("G") } returns ToolResult.success(
            listOf(PlanData("G-AUTO", "Auto", "Auto", "G", "Group", enabled = true)),
            "ok",
        )

        val result = ci.getPipelinesForGroup("G")

        assertFalse(result.isError)
        assertEquals("G", result.data!!.single().groupKey)
        coVerify(exactly = 1) { service.getProjectPlans("G") }
    }

    @Test fun `searchPipelines delegates to searchPlans`() = runTest {
        coEvery { service.searchPlans("auto") } returns ToolResult.success(
            listOf(PlanData("G-AUTO", "Auto", "Auto", "G", "Group", enabled = true)),
            "ok",
        )

        val result = ci.searchPipelines("auto")

        assertFalse(result.isError)
        assertEquals("G-AUTO", result.data!!.single().key)
        coVerify(exactly = 1) { service.searchPlans("auto") }
    }

    @Test fun `getGroups delegates to getProjects and maps to CiGroupData`() = runTest {
        coEvery { service.getProjects() } returns ToolResult.success(
            listOf(ProjectData("G", "Group", "desc")),
            "ok",
        )

        val result = ci.getGroups()

        assertFalse(result.isError)
        val g = result.data!!.single()
        assertEquals("G", g.key)
        assertEquals("Group", g.name)
        assertEquals("desc", g.description)
        coVerify(exactly = 1) { service.getProjects() }
    }

    @Test fun `triggerBuild delegates with explicit null stages (no recursion)`() = runTest {
        coEvery { service.triggerBuild("pid", emptyMap(), null) } returns ToolResult.error("stubbed")

        ci.triggerBuild("pid")

        coVerify(exactly = 1) { service.triggerBuild("pid", emptyMap(), null) }
    }

    @Test fun `retryFailedJobs delegates to rerunFailedJobs`() = runTest {
        coEvery { service.rerunFailedJobs("pid", 42) } returns ToolResult.error("stubbed")

        ci.retryFailedJobs("pid", 42)

        coVerify(exactly = 1) { service.rerunFailedJobs("pid", 42) }
    }

    @Test fun `getRunningBuilds delegates with explicit null repoName (no recursion)`() = runTest {
        coEvery { service.getRunningBuilds("pid", null) } returns ToolResult.error("stubbed")

        ci.getRunningBuilds("pid")

        coVerify(exactly = 1) { service.getRunningBuilds("pid", null) }
    }
}
