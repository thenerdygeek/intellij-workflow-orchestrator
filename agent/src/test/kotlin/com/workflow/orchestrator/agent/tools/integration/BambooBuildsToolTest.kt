package com.workflow.orchestrator.agent.tools.integration

import com.workflow.orchestrator.core.model.bamboo.BuildResultData
import com.workflow.orchestrator.core.model.bamboo.BuildChangeData
import com.workflow.orchestrator.core.services.BambooService
import com.workflow.orchestrator.core.services.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for [BambooBuildsTool] `get_build` include_commits flag:
 *  - include_commits=true appends a commits block
 *  - include_commits omitted does NOT fetch changes
 *  - include_commits=true with empty change list shows "(none)"
 *
 * Exercises [BambooBuildsTool.executeGetBuildForTest] so tests do not require
 * IntelliJ service infrastructure (mirrors JiraToolGetTicketTest pattern).
 *
 * Run with: ./gradlew :agent:test --tests "*BambooBuildsToolTest*"
 */
class BambooBuildsToolTest {

    private val tool = BambooBuildsTool()

    private fun buildResult() = ToolResult(
        data = BuildResultData(
            planKey = "PLAN-X",
            buildNumber = 42,
            state = "Successful",
            durationSeconds = 120L,
            buildResultKey = "PLAN-X-42"
        ),
        summary = "PLAN-X #42: Successful",
        isError = false
    )

    private fun changeData() = BuildChangeData(
        userName = "jdoe",
        fullName = "John Doe",
        comment = "Fix the critical login bug\n\nAdditional details here.",
        changesetId = "abc123def456",
        commitUrl = "https://bitbucket.example.com/projects/PROJ/repos/app/commits/abc123def456",
        date = "2026-05-07T10:00:00.000+0000"
    )

    // ── include_commits=true appends commits block ────────────────────────────

    @Test
    fun `get_build with include_commits=true appends commits block`() = runTest {
        val service = mockk<BambooService>()
        coEvery { service.getBuild("PLAN-X-42") } returns buildResult()
        coEvery { service.getBuildChanges("PLAN-X-42") } returns ToolResult(
            data = listOf(changeData()),
            summary = "1 commit(s) for PLAN-X-42",
            isError = false
        )

        val result = tool.executeGetBuildForTest(
            buildKey = "PLAN-X-42",
            includeCommits = true,
            service = service
        )

        assertFalse(result.isError)
        assertTrue(result.content.contains("Commits"), "content must contain a 'Commits' header")
        assertTrue(result.content.contains("abc123de"), "content must reference the SHA prefix of the stub commit")
        assertTrue(result.content.contains("Fix the critical login bug"), "content must reference the commit message first line")
        coVerify(exactly = 1) { service.getBuildChanges("PLAN-X-42") }
    }

    // ── include_commits omitted → no fetch ───────────────────────────────────

    @Test
    fun `get_build with include_commits omitted does NOT fetch changes`() = runTest {
        val service = mockk<BambooService>()
        coEvery { service.getBuild("PLAN-X-42") } returns buildResult()

        tool.executeGetBuildForTest(
            buildKey = "PLAN-X-42",
            includeCommits = false,
            service = service
        )

        coVerify(exactly = 0) { service.getBuildChanges(any()) }
    }

    // ── fullName blank → falls back to userName ───────────────────────────────

    @Test
    fun `get_build with include_commits=true falls back to userName when fullName is blank`() = runTest {
        val service = mockk<BambooService>()
        coEvery { service.getBuild("PLAN-X-42") } returns buildResult()
        coEvery { service.getBuildChanges("PLAN-X-42") } returns ToolResult(
            data = listOf(
                BuildChangeData(
                    userName = "jdoe",
                    fullName = "",
                    comment = "Refactor login service",
                    changesetId = "deadbeef1234",
                    commitUrl = "https://bitbucket.example.com/commits/deadbeef1234",
                    date = "2026-05-07T11:00:00.000+0000"
                )
            ),
            summary = "1 commit(s) for PLAN-X-42",
            isError = false
        )

        val result = tool.executeGetBuildForTest(
            buildKey = "PLAN-X-42",
            includeCommits = true,
            service = service
        )

        assertFalse(result.isError)
        assertTrue(result.content.contains("jdoe"), "content must contain userName when fullName is blank")
        assertFalse(
            result.content.contains("jdoe").not() && result.content.contains("—"),
            "em-dash sentinel must NOT appear when userName fallback fires"
        )
    }

    // ── include_commits=true with empty change list shows "(none)" ────────────

    @Test
    fun `get_build with include_commits=true on empty change list says (none)`() = runTest {
        val service = mockk<BambooService>()
        coEvery { service.getBuild("PLAN-X-42") } returns buildResult()
        coEvery { service.getBuildChanges("PLAN-X-42") } returns ToolResult(
            data = emptyList(),
            summary = "0 commit(s) for PLAN-X-42",
            isError = false
        )

        val result = tool.executeGetBuildForTest(
            buildKey = "PLAN-X-42",
            includeCommits = true,
            service = service
        )

        assertFalse(result.isError)
        assertTrue(result.content.contains("(none)"), "content must reference '(none)' when the commit list is empty")
    }

    // ── build_status surfaces in-progress/queued builds ───────────────────────

    @Test
    fun `build_status surfaces a running build the latest endpoint cannot see`() = runTest {
        val service = mockk<BambooService>()
        // /latest only returns the most recent FINISHED build (here #2, failed).
        coEvery { service.getLatestBuild("PLAN-X") } returns ToolResult(
            data = BuildResultData(
                planKey = "PLAN-X",
                buildNumber = 2,
                state = "Failed",
                durationSeconds = 90L,
                buildResultKey = "PLAN-X-2",
                lifeCycleState = "Finished"
            ),
            summary = "PLAN-X #2: Failed",
            isError = false
        )
        // ...while #3 is actually building right now.
        coEvery { service.getRunningBuilds("PLAN-X", repoName = null) } returns ToolResult(
            data = listOf(
                BuildResultData(
                    planKey = "PLAN-X",
                    buildNumber = 3,
                    state = "Unknown",
                    durationSeconds = 0L,
                    buildResultKey = "PLAN-X-3",
                    lifeCycleState = "InProgress"
                )
            ),
            summary = "Found 1 running/queued build(s) for PLAN-X",
            isError = false
        )

        val result = tool.executeBuildStatusForTest("PLAN-X", service)

        assertFalse(result.isError)
        assertTrue(result.content.contains("#3"), "must surface the in-progress build #3, got: ${result.content}")
        assertTrue(result.content.contains("InProgress"), "must show the live lifecycle state")
        assertTrue(result.content.contains("#2") && result.content.contains("Failed"),
            "must still show the latest finished build #2")
    }

    @Test
    fun `build_status falls back to latest finished build when nothing is running`() = runTest {
        val service = mockk<BambooService>()
        coEvery { service.getLatestBuild("PLAN-X") } returns buildResult()
        coEvery { service.getRunningBuilds("PLAN-X", repoName = null) } returns ToolResult(
            data = emptyList(),
            summary = "Found 0 running/queued build(s) for PLAN-X",
            isError = false
        )

        val result = tool.executeBuildStatusForTest("PLAN-X", service)

        assertFalse(result.isError)
        assertFalse(result.content.contains("IN PROGRESS"), "no live-build banner when nothing is running")
        assertTrue(result.content.contains("#42"), "shows the latest finished build")
    }
}
