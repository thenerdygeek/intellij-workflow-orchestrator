package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.model.bitbucket.*

/**
 * Bitbucket operations used by both UI panels and AI agent.
 * Implementations registered as project-level services by :pullrequest module.
 */
interface BitbucketService {
    /** Create a pull request. */
    suspend fun createPullRequest(
        title: String,
        description: String,
        fromBranch: String,
        toBranch: String
    ): ToolResult<PullRequestData>

    /** Get commits for a pull request. */
    suspend fun getPullRequestCommits(prId: Int): ToolResult<List<CommitData>>

    /** Add an inline comment to a file/line in a pull request. */
    suspend fun addInlineComment(prId: Int, filePath: String, line: Int, lineType: String, text: String): ToolResult<Unit>

    /** Reply to an existing comment on a pull request. */
    suspend fun replyToComment(prId: Int, parentCommentId: Int, text: String): ToolResult<Unit>

    /** Set a reviewer's status on a pull request (APPROVED, NEEDS_WORK, UNAPPROVED). */
    suspend fun setReviewerStatus(prId: Int, username: String, status: String): ToolResult<Unit>

    /** Get raw file content from repository at a specific ref. */
    suspend fun getFileContent(filePath: String, atRef: String): ToolResult<String>

    /** Add a reviewer to a pull request. */
    suspend fun addReviewer(prId: Int, username: String): ToolResult<Unit>

    /** Update the title of a pull request. */
    suspend fun updatePrTitle(prId: Int, newTitle: String): ToolResult<Unit>

    /** Test the Bitbucket connection. */
    suspend fun testConnection(): ToolResult<Unit>

    // --- Branch operations ---

    /** List branches in the repository, optionally filtered by name. */
    suspend fun getBranches(filter: String? = null): ToolResult<List<BranchData>>

    /** Create a new branch from a start point (commit hash or branch name). */
    suspend fun createBranch(name: String, startPoint: String): ToolResult<BranchData>

    // --- Users ---

    /** Search Bitbucket users by filter text (for reviewer autocomplete). */
    suspend fun searchUsers(filter: String): ToolResult<List<BitbucketUserData>>

    // --- PR listing ---

    /** Get open pull requests for a specific branch. */
    suspend fun getPullRequestsForBranch(branchName: String): ToolResult<List<PullRequestData>>

    /** Get pull requests authored by the current user. */
    suspend fun getMyPullRequests(state: String = "OPEN"): ToolResult<List<PullRequestData>>

    /** Get pull requests where the current user is a reviewer. */
    suspend fun getReviewingPullRequests(state: String = "OPEN"): ToolResult<List<PullRequestData>>

    // --- PR detail ---

    /** Get full details of a specific pull request including reviewers. */
    suspend fun getPullRequestDetail(prId: Int): ToolResult<PullRequestDetailData>

    /** Get activity feed (comments, approvals, merges) for a pull request. */
    suspend fun getPullRequestActivities(prId: Int): ToolResult<List<PrActivityData>>

    /** Get the list of changed files for a pull request. */
    suspend fun getPullRequestChanges(prId: Int): ToolResult<List<PrChangeData>>

    /** Get the raw diff for a pull request. */
    suspend fun getPullRequestDiff(prId: Int): ToolResult<String>

    // --- Build status ---

    /** Get build statuses for a commit. */
    suspend fun getBuildStatuses(commitId: String): ToolResult<List<BuildStatusData>>

    // --- PR actions ---

    /** Approve a pull request. */
    suspend fun approvePullRequest(prId: Int): ToolResult<Unit>

    /** Remove approval from a pull request. */
    suspend fun unapprovePullRequest(prId: Int): ToolResult<Unit>

    /** Merge a pull request with optional strategy, delete-source-branch, and commit message. */
    suspend fun mergePullRequest(
        prId: Int,
        strategy: String? = null,
        deleteSourceBranch: Boolean = false,
        commitMessage: String? = null
    ): ToolResult<Unit>

    /** Decline a pull request. */
    suspend fun declinePullRequest(prId: Int): ToolResult<Unit>

    /** Update the description of a pull request. */
    suspend fun updatePrDescription(prId: Int, description: String): ToolResult<Unit>

    /** Add a general comment to a pull request. */
    suspend fun addPrComment(prId: Int, text: String): ToolResult<Unit>

    /** Check merge preconditions for a pull request. */
    suspend fun checkMergeStatus(prId: Int): ToolResult<MergeStatusData>

    /** Remove a reviewer from a pull request. */
    suspend fun removeReviewer(prId: Int, username: String): ToolResult<Unit>
}
