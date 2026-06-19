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

    private fun okBuild(
        state: String = "Successful",
        lifeCycle: String = "Finished",
        buildNumber: Int = 1,
        planKey: String = "PROJ-PLAN",
    ) = BuildResultData(
        planKey = planKey,
        buildNumber = buildNumber,
        state = state,
        durationSeconds = 10L,
        lifeCycleState = lifeCycle,
    )

    private fun okResult(build: BuildResultData) =
        ToolResult(data = build, summary = "ok", isError = false)

    private fun errResult() =
        ToolResult<BuildResultData>(data = null, summary = "error", isError = true)

    private fun okRunningResult(builds: List<BuildResultData>) =
        ToolResult(data = builds, summary = "ok", isError = false)

    private fun errRunningResult() =
        ToolResult<List<BuildResultData>>(data = null, summary = "error", isError = true)

    private fun emptyRunningResult() =
        ToolResult(data = emptyList<BuildResultData>(), summary = "ok", isError = false)

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

    // -------------------------------------------------------------------------
    // Original tests (unchanged)
    // -------------------------------------------------------------------------

    @Test
    fun `branch null uses planKey as chainKey directly`() = runTest {
        val bamboo = mockk<BambooService>()
        coEvery { bamboo.getRunningBuilds("PROJ-PLAN") } returns emptyRunningResult()
        coEvery { bamboo.getLatestBuild("PROJ-PLAN") } returns okResult(okBuild())
        val src = source(bamboo, planKey = "PROJ-PLAN", branch = null, scope = this)
        val events = mutableListOf<MonitorEvent>()
        src.pollOnce { events.add(it) }
        coVerify(exactly = 0) { bamboo.getPlanBranches(any(), any()) }
        // No running builds → getLatestBuild is consulted exactly once on the planKey.
        coVerify(exactly = 1) { bamboo.getLatestBuild("PROJ-PLAN") }
    }

    @Test
    fun `branch set resolves chainKey via getPlanBranches then polls getLatestBuild resolved key`() = runTest {
        val bamboo = mockk<BambooService>()
        coEvery { bamboo.getPlanBranches("PROJ-PLAN") } returns okBranches("PROJ-PLAN-123" to "feature/foo")
        coEvery { bamboo.getRunningBuilds("PROJ-PLAN-123") } returns emptyRunningResult()
        coEvery { bamboo.getRunningBuilds("PROJ-PLAN") } returns emptyRunningResult()
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
        coEvery { bamboo.getRunningBuilds("PROJ-PLAN") } returns emptyRunningResult()
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
        // First poll: InProgress running build (buildNumber 1)
        // Second poll: no running builds → latest finished Failed
        coEvery { bamboo.getRunningBuilds("PROJ-PLAN") } returnsMany listOf(
            okRunningResult(listOf(okBuild("Unknown", "InProgress", buildNumber = 1))),
            emptyRunningResult(),
        )
        coEvery { bamboo.getLatestBuild("PROJ-PLAN") } returns okResult(okBuild("Failed", "Finished", buildNumber = 1))
        val src = source(bamboo, planKey = "PROJ-PLAN", branch = null, scope = this)
        val events = mutableListOf<MonitorEvent>()
        // First poll: running InProgress (non-terminal → no event)
        val changed1 = src.pollOnce { events.add(it) }
        assertFalse(changed1)
        assertTrue(events.isEmpty())
        // Second poll: no running → getLatestBuild returns Failed → ALERT.
        // P1-8: the observed InProgress→Failed terminal transition ALSO appends the
        // final NOTABLE "monitor auto-stopped" event after the diff event.
        val changed2 = src.pollOnce { events.add(it) }
        assertTrue(changed2)
        assertEquals(2, events.size)
        assertEquals(Severity.ALERT, events[0].severity)
        assertTrue(events[1].line.contains("monitor auto-stopped"))
    }

    // -------------------------------------------------------------------------
    // New composite-poll tests
    // -------------------------------------------------------------------------

    @Test
    fun `fetch prefers running build over finished - getLatestBuild not consulted when running build exists`() = runTest {
        val bamboo = mockk<BambooService>()
        val runningBuild = okBuild("Unknown", "InProgress", buildNumber = 7)
        coEvery { bamboo.getRunningBuilds("PROJ-PLAN") } returns okRunningResult(listOf(runningBuild))
        // getLatestBuild should NOT be called when a running build exists
        val src = source(bamboo, planKey = "PROJ-PLAN", branch = null, scope = this)
        val events = mutableListOf<MonitorEvent>()
        src.pollOnce { events.add(it) }
        coVerify(exactly = 0) { bamboo.getLatestBuild(any()) }
    }

    @Test
    fun `fetch on a branch queries only the branch chainKey - master plan key never queried`() = runTest {
        val bamboo = mockk<BambooService>()
        // Branch resolves to the branch chain key PROJ-PLAN-42 — branch builds live there, not under master.
        coEvery { bamboo.getPlanBranches("PROJ-PLAN") } returns okBranches("PROJ-PLAN-42" to "feature/bar")
        val running = okBuild("Unknown", "InProgress", buildNumber = 7, planKey = "PROJ-PLAN-42")
        coEvery { bamboo.getRunningBuilds("PROJ-PLAN-42") } returns okRunningResult(listOf(running))
        val src = source(bamboo, planKey = "PROJ-PLAN", branch = "feature/bar", scope = this)
        val events = mutableListOf<MonitorEvent>()
        src.pollOnce { events.add(it) }
        coVerify(exactly = 1) { bamboo.getRunningBuilds("PROJ-PLAN-42") }
        // The master plan key must NEVER be queried — no fallback to master for a branch's builds.
        coVerify(exactly = 0) { bamboo.getRunningBuilds("PROJ-PLAN") }
        coVerify(exactly = 0) { bamboo.getLatestBuild(any()) }
    }

    @Test
    fun `fetch falls back to getLatestBuild when no running builds found`() = runTest {
        val bamboo = mockk<BambooService>()
        val finishedBuild = okBuild("Successful", "Finished", buildNumber = 6)
        coEvery { bamboo.getRunningBuilds("PROJ-PLAN") } returns emptyRunningResult()
        coEvery { bamboo.getLatestBuild("PROJ-PLAN") } returns okResult(finishedBuild)
        val src = source(bamboo, planKey = "PROJ-PLAN", branch = null, scope = this)
        val events = mutableListOf<MonitorEvent>()
        src.pollOnce { events.add(it) }
        coVerify(exactly = 1) { bamboo.getLatestBuild("PROJ-PLAN") }
    }

    @Test
    fun `getRunningBuilds error on the branch chainKey falls back to getLatestBuild gracefully`() = runTest {
        val bamboo = mockk<BambooService>()
        coEvery { bamboo.getPlanBranches("PROJ-PLAN") } returns okBranches("PROJ-PLAN-99" to "bugfix/x")
        coEvery { bamboo.getRunningBuilds("PROJ-PLAN-99") } returns errRunningResult()
        val finishedBuild = okBuild("Successful", "Finished", buildNumber = 3)
        coEvery { bamboo.getLatestBuild("PROJ-PLAN-99") } returns okResult(finishedBuild)
        val src = source(bamboo, planKey = "PROJ-PLAN", branch = "bugfix/x", scope = this)
        val events = mutableListOf<MonitorEvent>()
        src.pollOnce { events.add(it) }
        // A running-check error must NOT prevent fetching the latest finished build for the SAME chain key.
        coVerify(exactly = 1) { bamboo.getLatestBuild("PROJ-PLAN-99") }
        // Master plan key must NEVER be queried — no fallback to master.
        coVerify(exactly = 0) { bamboo.getRunningBuilds("PROJ-PLAN") }
    }

    @Test
    fun `fetch picks highest buildNumber when multiple running builds returned`() = runTest {
        val bamboo = mockk<BambooService>()
        val build5 = okBuild("Unknown", "InProgress", buildNumber = 5)
        val build8 = okBuild("Unknown", "InProgress", buildNumber = 8)
        val build3 = okBuild("Unknown", "Queued", buildNumber = 3)
        coEvery { bamboo.getRunningBuilds("PROJ-PLAN") } returns okRunningResult(listOf(build5, build8, build3))
        val src = source(bamboo, planKey = "PROJ-PLAN", branch = null, scope = this)
        // Capture the snapshot by watching what diff sees — poll will see build8 as prev=null
        // InProgress is non-terminal so first-poll emits nothing; but we confirm getLatestBuild not called
        val events = mutableListOf<MonitorEvent>()
        src.pollOnce { events.add(it) }
        coVerify(exactly = 0) { bamboo.getLatestBuild(any()) }
        // Second poll: still running build8 — no change → no event
        coEvery { bamboo.getRunningBuilds("PROJ-PLAN") } returns okRunningResult(listOf(build8))
        val events2 = mutableListOf<MonitorEvent>()
        src.pollOnce { events2.add(it) }
        assertTrue(events2.isEmpty()) // same buildNumber + state + lifeCycle → no event
    }

    // -------------------------------------------------------------------------
    // P1-8: terminal-state auto-stop (W5-C1)
    // -------------------------------------------------------------------------

    @Test
    fun `terminal build state after a running snapshot auto-stops the source`() = runTest {
        val bamboo = mockk<BambooService>()
        // Poll 1: live in-progress build #7. Poll 2: no live builds, latest finished #7 Successful.
        coEvery { bamboo.getRunningBuilds("PROJ-PLAN") } returnsMany listOf(
            okRunningResult(listOf(okBuild(state = "Unknown", lifeCycle = "InProgress", buildNumber = 7))),
            emptyRunningResult(),
        )
        coEvery { bamboo.getLatestBuild("PROJ-PLAN") } returns
            okResult(okBuild(state = "Successful", lifeCycle = "Finished", buildNumber = 7))
        val src = source(bamboo, scope = this)
        val events = mutableListOf<MonitorEvent>()
        src.pollOnce { events.add(it) } // baseline: running (non-terminal)
        src.pollOnce { events.add(it) } // running -> Successful = observed terminal transition
        assertTrue(
            events.any { it.line.contains("monitor auto-stopped") },
            "BambooMonitorSource must auto-stop after an observed transition to a terminal build state",
        )
    }

    @Test
    fun `already-finished build on first poll does not auto-stop`() = runTest {
        val bamboo = mockk<BambooService>()
        coEvery { bamboo.getRunningBuilds("PROJ-PLAN") } returns emptyRunningResult()
        coEvery { bamboo.getLatestBuild("PROJ-PLAN") } returns
            okResult(okBuild(state = "Successful", lifeCycle = "Finished", buildNumber = 7))
        val src = source(bamboo, scope = this)
        val events = mutableListOf<MonitorEvent>()
        src.pollOnce { events.add(it) } // first poll, already terminal
        src.pollOnce { events.add(it) } // still the same finished build
        assertFalse(
            events.any { it.line.contains("monitor auto-stopped") },
            "a monitor on a plan whose last build already finished must keep waiting for the next build",
        )
    }
}
