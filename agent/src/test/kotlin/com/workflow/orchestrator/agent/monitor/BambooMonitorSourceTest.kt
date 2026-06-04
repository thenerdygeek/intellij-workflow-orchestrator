package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.core.model.bamboo.BuildResultData
import com.workflow.orchestrator.core.model.bamboo.BuildStageData
import com.workflow.orchestrator.core.model.bamboo.PlanBranchData
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BambooMonitorSourceTest {

    private fun okBuild(state: String = "Successful", lifeCycle: String = "Finished") = BuildResultData(
        planKey = "PROJ-PLAN",
        buildNumber = 1,
        state = state,
        durationSeconds = 10L,
        lifeCycleState = lifeCycle,
    )

    private fun okResult(build: BuildResultData) =
        ToolResult(data = build, summary = "ok", isError = false)

    private fun errResult() =
        ToolResult<BuildResultData>(data = null, summary = "error", isError = true)

    private fun okBranches(vararg pairs: Pair<String, String>) =
        ToolResult(
            data = pairs.map { (key, name) -> PlanBranchData(key = key, name = name, shortName = name) },
            summary = "ok",
            isError = false,
        )

    private fun source(
        bamboo: BambooService,
        planKey: String = "PROJ-PLAN",
        branch: String? = null,
        level: BambooDiff.Level = BambooDiff.Level.BUILD,
        scope: TestScope,
    ) = BambooMonitorSource(
        monitorId = "test-m",
        description = "test",
        bamboo = bamboo,
        planKey = planKey,
        branch = branch,
        level = level,
        stageName = null,
        jobName = null,
        cs = scope,
    )

    @Test
    fun `branch null uses planKey as chainKey directly`() = runTest {
        val bamboo = mockk<BambooService>()
        coEvery { bamboo.getLatestBuild("PROJ-PLAN") } returns okResult(okBuild())
        val src = source(bamboo, planKey = "PROJ-PLAN", branch = null, scope = this)
        val events = mutableListOf<MonitorEvent>()
        src.pollOnce { events.add(it) }
        coVerify(exactly = 1) { bamboo.getLatestBuild("PROJ-PLAN") }
        coVerify(exactly = 0) { bamboo.getPlanBranches(any(), any()) }
    }

    @Test
    fun `branch set resolves chainKey via getPlanBranches then polls getLatestBuild resolved key`() = runTest {
        val bamboo = mockk<BambooService>()
        coEvery { bamboo.getPlanBranches("PROJ-PLAN") } returns okBranches("PROJ-PLAN-123" to "feature/foo")
        coEvery { bamboo.getLatestBuild("PROJ-PLAN-123") } returns okResult(okBuild("Failed", "Finished"))
        val src = source(bamboo, planKey = "PROJ-PLAN", branch = "feature/foo", scope = this)
        val events = mutableListOf<MonitorEvent>()
        src.pollOnce { events.add(it) }
        coVerify { bamboo.getPlanBranches("PROJ-PLAN") }
        coVerify { bamboo.getLatestBuild("PROJ-PLAN-123") }
        // First poll terminal state → should emit
        assertEquals(1, events.size)
        assertEquals(Severity.ALERT, events[0].severity)
    }

    @Test
    fun `getPlanBranches error results in fetch returning null and pollOnce false, no events`() = runTest {
        val bamboo = mockk<BambooService>()
        coEvery { bamboo.getPlanBranches("PROJ-PLAN") } returns ToolResult(data = null, summary = "err", isError = true)
        val src = source(bamboo, planKey = "PROJ-PLAN", branch = "mybranch", scope = this)
        val events = mutableListOf<MonitorEvent>()
        val changed = src.pollOnce { events.add(it) }
        assertFalse(changed)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `getLatestBuild error results in fetch null and no events`() = runTest {
        val bamboo = mockk<BambooService>()
        coEvery { bamboo.getLatestBuild("PROJ-PLAN") } returns errResult()
        val src = source(bamboo, planKey = "PROJ-PLAN", branch = null, scope = this)
        val events = mutableListOf<MonitorEvent>()
        val changed = src.pollOnce { events.add(it) }
        assertFalse(changed)
        assertTrue(events.isEmpty())
    }

    @Test
    fun `state transition across two pollOnce calls emits BambooDiff ALERT event`() = runTest {
        val bamboo = mockk<BambooService>()
        // First poll: InProgress (non-terminal → no event)
        coEvery { bamboo.getLatestBuild("PROJ-PLAN") } returnsMany listOf(
            okResult(okBuild("Unknown", "InProgress")),
            okResult(okBuild("Failed", "Finished")),
        )
        val src = source(bamboo, planKey = "PROJ-PLAN", branch = null, scope = this)
        val events = mutableListOf<MonitorEvent>()
        val changed1 = src.pollOnce { events.add(it) }
        assertFalse(changed1)
        assertTrue(events.isEmpty())
        // Second poll: Failed → ALERT
        val changed2 = src.pollOnce { events.add(it) }
        assertTrue(changed2)
        assertEquals(1, events.size)
        assertEquals(Severity.ALERT, events[0].severity)
    }
}
