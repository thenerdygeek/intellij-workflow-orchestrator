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
}
