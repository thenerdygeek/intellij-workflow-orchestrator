package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.model.bitbucket.CommitData
import com.workflow.orchestrator.core.model.bitbucket.PullRequestData

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
}
