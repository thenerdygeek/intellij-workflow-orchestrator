package com.workflow.orchestrator.jira.api

import com.workflow.orchestrator.core.model.ApiResult
import com.workflow.orchestrator.jira.api.dto.DevStatusBranch
import com.workflow.orchestrator.jira.api.dto.DevStatusBuild
import com.workflow.orchestrator.jira.api.dto.DevStatusCommit
import com.workflow.orchestrator.jira.api.dto.DevStatusDeployment
import com.workflow.orchestrator.jira.api.dto.DevStatusPullRequest
import com.workflow.orchestrator.jira.api.dto.DevStatusReview

/**
 * Narrow interface covering the six dev-status fetch operations used by
 * [com.workflow.orchestrator.jira.service.JiraServiceImpl.getFullDevStatus].
 *
 * Extracted so tests can supply a hand-rolled fake without touching OkHttp.
 * [JiraApiClient] implements this interface directly.
 */
interface DevStatusFetcher {
    suspend fun getDevStatusBranches(issueId: String): ApiResult<List<DevStatusBranch>>
    suspend fun getDevStatusPullRequests(issueId: String): ApiResult<List<DevStatusPullRequest>>
    suspend fun getDevStatusCommits(issueId: String): ApiResult<List<DevStatusCommit>>
    suspend fun getDevStatusBuilds(issueId: String): ApiResult<List<DevStatusBuild>>
    suspend fun getDevStatusDeployments(issueId: String): ApiResult<List<DevStatusDeployment>>
    suspend fun getDevStatusReviews(issueId: String): ApiResult<List<DevStatusReview>>
}
