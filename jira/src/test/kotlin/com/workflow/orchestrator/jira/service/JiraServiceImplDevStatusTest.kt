package com.workflow.orchestrator.jira.service

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.core.model.ErrorType
import com.workflow.orchestrator.jira.api.DevStatusFetcher
import com.workflow.orchestrator.jira.api.dto.DevStatusBranch
import com.workflow.orchestrator.jira.api.dto.DevStatusBuild
import com.workflow.orchestrator.jira.api.dto.DevStatusCommit
import com.workflow.orchestrator.jira.api.dto.DevStatusDeployment
import com.workflow.orchestrator.jira.api.dto.DevStatusPullRequest
import com.workflow.orchestrator.jira.api.dto.DevStatusReview
import io.mockk.mockk
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.system.measureTimeMillis

/**
 * Tests for [JiraServiceImpl.fetchFullDevStatus] using a hand-rolled [DevStatusFetcher] fake.
 *
 * No mocking libraries are used for the fetcher — all fakes are hand-rolled.
 * [JiraServiceImpl] is constructed with a mockk [com.intellij.openapi.project.Project]
 * (needed by the @Service constructor) but the real fetcher path is exercised
 * via the internal [JiraServiceImpl.fetchFullDevStatus] entry point.
 */
class JiraServiceImplDevStatusTest {

    private val project = mockk<com.intellij.openapi.project.Project>(relaxed = true)

    private fun service() = JiraServiceImpl(project)

    // ---- Fake helpers -------------------------------------------------------

    private fun successFetcher(delayMs: Long = 0L): DevStatusFetcher = object : DevStatusFetcher {
        private suspend fun <T> ok(value: T): ApiResult<T> {
            if (delayMs > 0) delay(delayMs)
            return ApiResult.Success(value)
        }

        override suspend fun getDevStatusBranches(issueId: String) =
            ok(listOf(DevStatusBranch(name = "feature/test", url = "http://example.com/branch")))

        override suspend fun getDevStatusPullRequests(issueId: String) =
            ok(listOf(DevStatusPullRequest(name = "PR-1", url = "", status = "OPEN")))

        override suspend fun getDevStatusCommits(issueId: String) =
            ok(listOf(DevStatusCommit(id = "abc123", displayId = "abc123", message = "feat: add")))

        override suspend fun getDevStatusBuilds(issueId: String) =
            ok(listOf(DevStatusBuild(name = "Build #1", state = "SUCCESSFUL")))

        override suspend fun getDevStatusDeployments(issueId: String) =
            ok(listOf(DevStatusDeployment(displayName = "deploy", state = "SUCCESSFUL")))

        override suspend fun getDevStatusReviews(issueId: String) =
            ok(listOf(DevStatusReview(name = "Review", state = "APPROVED")))
    }

    private fun errorFetcher(): DevStatusFetcher = object : DevStatusFetcher {
        private fun <T> err(): ApiResult<T> =
            ApiResult.Error(ErrorType.SERVER_ERROR, "simulated error")

        override suspend fun getDevStatusBranches(issueId: String) = err<List<DevStatusBranch>>()
        override suspend fun getDevStatusPullRequests(issueId: String) = err<List<DevStatusPullRequest>>()
        override suspend fun getDevStatusCommits(issueId: String) = err<List<DevStatusCommit>>()
        override suspend fun getDevStatusBuilds(issueId: String) = err<List<DevStatusBuild>>()
        override suspend fun getDevStatusDeployments(issueId: String) = err<List<DevStatusDeployment>>()
        override suspend fun getDevStatusReviews(issueId: String) = err<List<DevStatusReview>>()
    }

    private fun buildsErrorFetcher(): DevStatusFetcher = object : DevStatusFetcher {
        private fun <T> err(): ApiResult<T> =
            ApiResult.Error(ErrorType.SERVER_ERROR, "builds unavailable")

        override suspend fun getDevStatusBranches(issueId: String) =
            ApiResult.Success(listOf(DevStatusBranch(name = "main", url = "")))

        override suspend fun getDevStatusPullRequests(issueId: String) =
            ApiResult.Success(listOf(DevStatusPullRequest(name = "PR-1", status = "OPEN")))

        override suspend fun getDevStatusCommits(issueId: String) =
            ApiResult.Success(listOf(DevStatusCommit(id = "abc", displayId = "abc", message = "fix")))

        override suspend fun getDevStatusBuilds(issueId: String) = err<List<DevStatusBuild>>()

        override suspend fun getDevStatusDeployments(issueId: String) =
            ApiResult.Success(listOf(DevStatusDeployment(displayName = "d", state = "SUCCESSFUL")))

        override suspend fun getDevStatusReviews(issueId: String) =
            ApiResult.Success(listOf(DevStatusReview(name = "r", state = "APPROVED")))
    }

    // ---- Tests ---------------------------------------------------------------

    @Test
    fun `getFullDevStatus runs six API calls in parallel`() = runTest {
        val delayPerCall = 100L
        val fetcher = successFetcher(delayMs = delayPerCall)
        val elapsed = measureTimeMillis {
            service().fetchFullDevStatus("TEST-1", fetcher)
        }
        // Six serial calls at 100ms each = 600ms. Parallel should be well under 500ms.
        assertTrue(elapsed < 500L, "Expected parallel execution but took ${elapsed}ms (serial would be ~600ms)")
    }

    @Test
    fun `getFullDevStatus degrades single-fetch failure to empty list`() = runTest {
        val result = service().fetchFullDevStatus("TEST-1", buildsErrorFetcher())
        assertFalse(result.isError, "Expected isError=false when only one feed fails")
        val bundle = result.data!!
        assertTrue(bundle.builds.isEmpty(), "Builds should be empty when builds-fetch errored")
        assertFalse(bundle.branches.isEmpty(), "Branches should be populated")
        assertFalse(bundle.pullRequests.isEmpty(), "PRs should be populated")
        assertFalse(bundle.commits.isEmpty(), "Commits should be populated")
        assertFalse(bundle.deployments.isEmpty(), "Deployments should be populated")
        assertFalse(bundle.reviews.isEmpty(), "Reviews should be populated")
    }

    @Test
    fun `getFullDevStatus aggregates total failure as empty bundle`() = runTest {
        val result = service().fetchFullDevStatus("TEST-1", errorFetcher())
        assertFalse(result.isError, "Overall result should be success even when all feeds fail")
        val bundle = result.data!!
        assertTrue(bundle.isEmpty, "Bundle should be empty when all feeds error")
        assertEquals(6, bundle.fetchErrors, "All 6 feeds errored so fetchErrors must be 6")
    }

    @Test
    fun `getFullDevStatus reports zero fetch errors on full success`() = runTest {
        val result = service().fetchFullDevStatus("TEST-1", successFetcher())
        assertFalse(result.isError)
        assertEquals(0, result.data!!.fetchErrors, "No errors expected on full success")
    }
}
