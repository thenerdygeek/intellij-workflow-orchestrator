package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.api.InternalApi
import com.workflow.orchestrator.core.model.PrComment
import com.workflow.orchestrator.core.model.bitbucket.BitbucketUserData
import com.workflow.orchestrator.core.model.bitbucket.BranchData
import com.workflow.orchestrator.core.model.bitbucket.BuildStatsData
import com.workflow.orchestrator.core.model.bitbucket.BuildStatusData
import com.workflow.orchestrator.core.model.bitbucket.CommitData
import com.workflow.orchestrator.core.model.bitbucket.MergeStatusData
import com.workflow.orchestrator.core.model.bitbucket.ParticipantData
import com.workflow.orchestrator.core.model.bitbucket.PrActivityData
import com.workflow.orchestrator.core.model.bitbucket.PrChangeData
import com.workflow.orchestrator.core.model.bitbucket.PullRequestData
import com.workflow.orchestrator.core.model.bitbucket.PullRequestDetailData
import com.workflow.orchestrator.core.model.bitbucket.RepoInfo

/** Neutral name for a VCS-host user; the underlying DTO fields (name/displayName/emailAddress) are already vendor-agnostic. */
typealias VcsUserData = BitbucketUserData

/**
 * Neutral VCS-host seam layered ABOVE the vendor-specific [BitbucketService] (Phase 0b-2 of the
 * plugin split). Captures the host-agnostic branch / PR / review / file operations so a future
 * GitHub / GitLab connector can implement [VcsHostClient] without inheriting Bitbucket vocabulary.
 *
 * SHAPE RESERVATION ONLY in 0b-2: the sole implementation is [BitbucketServiceImpl] (which also
 * implements [BitbucketService]); no consumer resolves [VcsHostClient] yet and there is no new
 * service/EP registration (sibling to how `NativeProtocol` was shaped before Phase 4).
 * `public` + [InternalApi] = unfrozen-by-policy.
 *
 * Scope notes:
 *  - `getLinkedJiraIssues` / `getRequiredBuilds` are intentionally NOT here — they are vendor-coupled
 *    (Bitbucket↔Jira link plugin; required-builds conditions keyed by Bamboo plan keys) and remain
 *    on [BitbucketService].
 *  - Default-branch resolution is now on this seam: `getDefaultBranch(repoName)` and
 *    `getDefaultReviewersForBranch(sourceBranch, targetBranch, repoName)` were added in Phase 1c as
 *    shape-reservations (no consumer resolves `VcsHostClient` yet). `BitbucketBranchClient` /
 *    `DefaultBranchResolver` remain the underlying implementations.
 *  - PR-state vocabulary: a GitHub/GitLab adapter maps its `closed` to Bitbucket's `DECLINED`.
 *  - [getBuildStatuses] / [getCommitBuildStats] read the VCS HOST's commit build-status store
 *    (e.g. Bitbucket's `/rest/build-status/...` endpoints), NOT the CI server — they answer "what
 *    build results has the VCS host recorded against this commit." This is deliberately distinct from
 *    [CiService]'s build queries (which ask the CI server directly); an adapter MAY back both with the
 *    same system, but the two seams own different questions. Do not relocate these to [CiService].
 *  - NO DEFAULT VALUES anywhere below: every parameter is required (no ` = ...`). `BitbucketService`
 *    already declares the defaults; declaring them here too triggers Kotlin
 *    MULTIPLE_DEFAULTS_INHERITED_FROM_SUPERTYPES on `BitbucketServiceImpl`. `VcsHostClient` has no
 *    consumers, so required params here are behavior-neutral. (The signatures shown below omit defaults.)
 */
@InternalApi
interface VcsHostClient {
    suspend fun listRepos(): ToolResult<List<RepoInfo>>

    suspend fun createPullRequest(
        title: String,
        description: String,
        fromBranch: String,
        toBranch: String,
        repoName: String?,
    ): ToolResult<PullRequestData>

    suspend fun getPullRequestCommits(prId: Int, repoName: String?): ToolResult<List<CommitData>>

    suspend fun addInlineComment(
        prId: Int,
        filePath: String,
        line: Int,
        lineType: String,
        text: String,
        repoName: String?,
        diffType: String?,
        fromHash: String?,
        toHash: String?,
    ): ToolResult<Unit>

    suspend fun replyToComment(prId: Int, parentCommentId: Int, text: String, repoName: String?): ToolResult<Unit>

    suspend fun setReviewerStatus(prId: Int, username: String, status: String, repoName: String?): ToolResult<Unit>

    suspend fun getFileContent(filePath: String, atRef: String, repoName: String?): ToolResult<String>

    suspend fun addReviewer(prId: Int, username: String, repoName: String?): ToolResult<Unit>

    suspend fun updatePrTitle(prId: Int, newTitle: String, repoName: String?): ToolResult<Unit>

    suspend fun testConnection(): ToolResult<Unit>

    suspend fun getBranches(filter: String?, repoName: String?): ToolResult<List<BranchData>>

    suspend fun createBranch(name: String, startPoint: String, repoName: String?): ToolResult<BranchData>

    /**
     * The repository's configured default branch. Neutral over VCS host.
     * Shape-reservation (Phase 1c): no consumer resolves VcsHostClient yet.
     * NO default param value — see the MULTIPLE_DEFAULTS note at the top of this file.
     */
    suspend fun getDefaultBranch(repoName: String?): ToolResult<BranchData>

    /**
     * Default reviewers that apply to a sourceBranch -> targetBranch pair.
     * Neutral over VCS host. Shape-reservation (Phase 1c).
     */
    suspend fun getDefaultReviewersForBranch(
        sourceBranch: String,
        targetBranch: String,
        repoName: String?
    ): ToolResult<List<VcsUserData>>

    suspend fun searchUsers(filter: String, repoName: String?): ToolResult<List<VcsUserData>>

    suspend fun getPullRequestsForBranch(branchName: String, repoName: String?): ToolResult<List<PullRequestData>>

    suspend fun getMyPullRequests(state: String, repoName: String?): ToolResult<List<PullRequestData>>

    suspend fun getReviewingPullRequests(state: String, repoName: String?): ToolResult<List<PullRequestData>>

    suspend fun getPullRequestDetail(prId: Int, repoName: String?): ToolResult<PullRequestDetailData>

    suspend fun getPullRequestActivities(prId: Int, repoName: String?): ToolResult<List<PrActivityData>>

    suspend fun getPullRequestChanges(prId: Int, repoName: String?): ToolResult<List<PrChangeData>>

    suspend fun getPullRequestDiff(prId: Int, repoName: String?): ToolResult<String>

    suspend fun getBuildStatuses(commitId: String, repoName: String?): ToolResult<List<BuildStatusData>>

    suspend fun approvePullRequest(prId: Int, repoName: String?): ToolResult<Unit>

    suspend fun unapprovePullRequest(prId: Int, repoName: String?): ToolResult<Unit>

    suspend fun mergePullRequest(
        prId: Int,
        strategy: String?,
        deleteSourceBranch: Boolean,
        commitMessage: String?,
        repoName: String?,
    ): ToolResult<Unit>

    suspend fun declinePullRequest(prId: Int, repoName: String?): ToolResult<Unit>

    suspend fun updatePrDescription(prId: Int, description: String, repoName: String?): ToolResult<Unit>

    suspend fun addPrComment(prId: Int, text: String, repoName: String?): ToolResult<Unit>

    suspend fun checkMergeStatus(prId: Int, repoName: String?): ToolResult<MergeStatusData>

    suspend fun removeReviewer(prId: Int, username: String, repoName: String?): ToolResult<Unit>

    suspend fun listPrComments(
        repoOwner: String,
        repoName: String,
        prId: Int,
        onlyOpen: Boolean,
        onlyInline: Boolean,
    ): ToolResult<List<PrComment>>

    suspend fun getPrComment(
        repoOwner: String,
        repoName: String,
        prId: Int,
        commentId: Long,
    ): ToolResult<PrComment>

    suspend fun editPrComment(
        repoOwner: String,
        repoName: String,
        prId: Int,
        commentId: Long,
        text: String,
        expectedVersion: Int,
    ): ToolResult<PrComment>

    suspend fun deletePrComment(
        repoOwner: String,
        repoName: String,
        prId: Int,
        commentId: Long,
        expectedVersion: Int,
    ): ToolResult<Unit>

    suspend fun resolvePrComment(
        repoOwner: String,
        repoName: String,
        prId: Int,
        commentId: Long,
    ): ToolResult<PrComment>

    suspend fun reopenPrComment(
        repoOwner: String,
        repoName: String,
        prId: Int,
        commentId: Long,
    ): ToolResult<PrComment>

    suspend fun getBlockerCommentsCount(prId: Int, repoName: String?): ToolResult<Int>

    suspend fun getPullRequestParticipants(prId: Int, repoName: String?): ToolResult<List<ParticipantData>>

    suspend fun getPullRequestsForCommit(sha: String, repoName: String?): ToolResult<List<PullRequestData>>

    suspend fun getCommitBuildStats(sha: String): ToolResult<BuildStatsData>
}
