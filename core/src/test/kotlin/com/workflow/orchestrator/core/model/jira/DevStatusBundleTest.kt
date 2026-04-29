package com.workflow.orchestrator.core.model.jira

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DevStatusBundleTest {

    private fun emptyBundle() = DevStatusBundle(
        branches = emptyList(),
        pullRequests = emptyList(),
        commits = emptyList(),
        builds = emptyList(),
        deployments = emptyList(),
        reviews = emptyList(),
        fetchedAt = 0L
    )

    private fun branch() = DevStatusBranchData(name = "feature/foo", url = "")
    private fun pr() = DevStatusPrData(name = "PR-1", url = "", status = "OPEN", lastUpdate = null)
    private fun commit() = DevStatusCommitData(displayId = "abc1234", message = "fix thing", url = "", authorName = null, authorTimestamp = null, merge = false)
    private fun build() = DevStatusBuildData(name = "Build #1", url = "", state = "SUCCESSFUL", lastUpdated = null, description = null)
    private fun deployment() = DevStatusDeploymentData(displayName = "deploy", url = "", state = "SUCCESS", environmentName = "prod", environmentType = "production", lastUpdated = null)
    private fun review() = DevStatusReviewData(name = "Review 1", url = "", state = "APPROVED", reviewerNames = emptyList(), lastUpdated = null)

    @Test
    fun `isEmpty is true when all lists are empty`() {
        assertTrue(emptyBundle().isEmpty)
    }

    @Test
    fun `isEmpty is false when any list is non-empty`() {
        assertFalse(emptyBundle().copy(branches = listOf(branch())).isEmpty)
        assertFalse(emptyBundle().copy(pullRequests = listOf(pr())).isEmpty)
        assertFalse(emptyBundle().copy(commits = listOf(commit())).isEmpty)
        assertFalse(emptyBundle().copy(builds = listOf(build())).isEmpty)
        assertFalse(emptyBundle().copy(deployments = listOf(deployment())).isEmpty)
        assertFalse(emptyBundle().copy(reviews = listOf(review())).isEmpty)
    }

    @Test
    fun `summaryLine returns no linked development activity when empty`() {
        assertEquals("no linked development activity", emptyBundle().summaryLine())
    }

    @Test
    fun `summaryLine returns singular labels for single items`() {
        val bundle = emptyBundle().copy(branches = listOf(branch()))
        assertEquals("1 branch", bundle.summaryLine())
    }

    @Test
    fun `summaryLine returns plural labels for multiple items`() {
        val bundle = emptyBundle().copy(branches = listOf(branch(), branch()))
        assertEquals("2 branches", bundle.summaryLine())
    }

    @Test
    fun `summaryLine omits empty categories`() {
        val bundle = emptyBundle().copy(
            pullRequests = listOf(pr()),
            builds = listOf(build(), build())
        )
        assertEquals("1 PR, 2 builds", bundle.summaryLine())
    }

    @Test
    fun `summaryLine includes all non-empty categories`() {
        val bundle = DevStatusBundle(
            branches = listOf(branch(), branch()),
            pullRequests = listOf(pr()),
            commits = listOf(commit(), commit(), commit()),
            builds = listOf(build()),
            deployments = emptyList(),
            reviews = emptyList(),
            fetchedAt = 0L
        )
        assertEquals("2 branches, 1 PR, 3 commits, 1 build", bundle.summaryLine())
    }

    @Test
    fun `summaryLine handles all categories populated`() {
        val bundle = DevStatusBundle(
            branches = listOf(branch()),
            pullRequests = listOf(pr()),
            commits = listOf(commit()),
            builds = listOf(build()),
            deployments = listOf(deployment()),
            reviews = listOf(review()),
            fetchedAt = 0L
        )
        assertEquals("1 branch, 1 PR, 1 commit, 1 build, 1 deployment, 1 review", bundle.summaryLine())
    }
}
