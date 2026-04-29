package com.workflow.orchestrator.jira.ui

import com.workflow.orchestrator.core.model.jira.DevStatusBranchData
import com.workflow.orchestrator.core.model.jira.DevStatusBuildData
import com.workflow.orchestrator.core.model.jira.DevStatusBundle
import com.workflow.orchestrator.core.model.jira.DevStatusCommitData
import com.workflow.orchestrator.core.model.jira.DevStatusDeploymentData
import com.workflow.orchestrator.core.model.jira.DevStatusPrData
import com.workflow.orchestrator.core.model.jira.DevStatusReviewData
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Headless tests for DevStatusBundle rendering decisions used by DevStatusSection.
 * Validates the logic that governs which sections auto-expand and empty-state text.
 */
class DevStatusSectionTest {

    private fun emptyBundle() = DevStatusBundle(
        branches = emptyList(),
        pullRequests = emptyList(),
        commits = emptyList(),
        builds = emptyList(),
        deployments = emptyList(),
        reviews = emptyList(),
        fetchedAt = 0L
    )

    @Test
    fun `empty bundle isEmpty is true`() {
        assertTrue(emptyBundle().isEmpty)
    }

    @Test
    fun `bundle with only PRs is not empty`() {
        val bundle = emptyBundle().copy(
            pullRequests = listOf(DevStatusPrData("PR-1", "http://example.com", "OPEN", null))
        )
        assertFalse(bundle.isEmpty)
    }

    @Test
    fun `failed build auto-expand flag is derived correctly`() {
        val bundle = emptyBundle().copy(
            builds = listOf(DevStatusBuildData("Build #1", "", "FAILED", null, null))
        )
        val hasFailedBuild = bundle.builds.any { it.state.uppercase() in setOf("FAILED", "FAILURE") }
        assertTrue(hasFailedBuild)
    }

    @Test
    fun `successful build does not trigger auto-expand via failed-build rule`() {
        val bundle = emptyBundle().copy(
            builds = listOf(DevStatusBuildData("Build #1", "", "SUCCESSFUL", null, null))
        )
        val hasFailedBuild = bundle.builds.any { it.state.uppercase() in setOf("FAILED", "FAILURE") }
        assertFalse(hasFailedBuild)
    }

    @Test
    fun `declined PR auto-expand flag is derived correctly`() {
        val bundle = emptyBundle().copy(
            pullRequests = listOf(DevStatusPrData("PR-1", "", "DECLINED", null))
        )
        val hasDeclined = bundle.pullRequests.any { it.status.uppercase() == "DECLINED" }
        assertTrue(hasDeclined)
    }

    @Test
    fun `summaryLine from bundle with only PRs lists only PR count`() {
        val bundle = emptyBundle().copy(
            pullRequests = listOf(
                DevStatusPrData("PR-1", "", "OPEN", null),
                DevStatusPrData("PR-2", "", "MERGED", null)
            )
        )
        assertEquals("2 PRs", bundle.summaryLine())
    }

    @Test
    fun `bundle with commits and builds produces expected summary`() {
        val bundle = emptyBundle().copy(
            commits = listOf(
                DevStatusCommitData("abc1234", "fix thing", "", null, null, false),
                DevStatusCommitData("def5678", "add feature", "", null, null, false)
            ),
            builds = listOf(DevStatusBuildData("Build #5", "", "SUCCESSFUL", null, null))
        )
        assertEquals("2 commits, 1 build", bundle.summaryLine())
    }

    @Test
    fun `bundle with all six categories produces full summary`() {
        val bundle = DevStatusBundle(
            branches = listOf(DevStatusBranchData("feature/foo", "")),
            pullRequests = listOf(DevStatusPrData("PR-1", "", "OPEN", null)),
            commits = listOf(DevStatusCommitData("abc1234", "msg", "", null, null, false)),
            builds = listOf(DevStatusBuildData("Build", "", "SUCCESSFUL", null, null)),
            deployments = listOf(DevStatusDeploymentData("deploy", "", "SUCCESS", "prod", "production", null)),
            reviews = listOf(DevStatusReviewData("Review 1", "", "APPROVED", emptyList(), null)),
            fetchedAt = 0L
        )
        assertEquals("1 branch, 1 PR, 1 commit, 1 build, 1 deployment, 1 review", bundle.summaryLine())
    }
}
