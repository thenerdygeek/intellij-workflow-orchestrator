package com.workflow.orchestrator.core.services

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

    /** Test the Bitbucket connection. */
    suspend fun testConnection(): ToolResult<Unit>
}
