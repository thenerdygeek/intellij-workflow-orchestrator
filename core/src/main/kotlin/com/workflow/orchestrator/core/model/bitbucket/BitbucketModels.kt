package com.workflow.orchestrator.core.model.bitbucket

import kotlinx.serialization.Serializable

/**
 * Simplified Bitbucket pull request domain model shared between UI panels and AI agent.
 */
@Serializable
data class PullRequestData(
    val id: Int,
    val title: String,
    val state: String,
    val fromBranch: String,
    val toBranch: String,
    val link: String,
    val authorName: String?
)

/**
 * Simplified commit data shared between UI panels and AI agent.
 */
@Serializable
data class CommitData(
    val id: String,
    val displayId: String,
    val message: String,
    val author: String?,
    val timestamp: Long
)

/**
 * Simplified branch data shared between UI panels and AI agent.
 */
@Serializable
data class BranchData(
    val id: String,
    val displayId: String,
    val latestCommit: String?,
    val isDefault: Boolean = false
)

/**
 * Simplified Bitbucket user data shared between UI panels and AI agent.
 */
@Serializable
data class BitbucketUserData(
    val name: String,
    val displayName: String,
    val emailAddress: String?
)

/**
 * Full pull request detail with reviewers and version info.
 */
@Serializable
data class PullRequestDetailData(
    val id: Int,
    val title: String,
    val description: String?,
    val state: String,
    val fromBranch: String,
    val toBranch: String,
    val authorName: String?,
    val reviewers: List<ReviewerData>,
    val createdDate: Long,
    val updatedDate: Long,
    val version: Int
)

/**
 * Reviewer data with approval status.
 */
@Serializable
data class ReviewerData(
    val username: String,
    val displayName: String,
    val approved: Boolean,
    val status: String
)

/**
 * Pull request activity (comment, approval, merge, etc.).
 */
@Serializable
data class PrActivityData(
    val id: Long,
    val action: String,
    val userName: String?,
    val timestamp: Long,
    val commentText: String?,
    val commentId: Long?,
    val filePath: String?,
    val lineNumber: Int?
)

/**
 * A changed file in a pull request.
 */
@Serializable
data class PrChangeData(
    val path: String,
    val changeType: String
)

/**
 * Build status for a commit.
 */
@Serializable
data class BuildStatusData(
    val state: String,
    val name: String,
    val url: String,
    val key: String
)

/**
 * Merge precondition status for a pull request.
 */
@Serializable
data class MergeStatusData(
    val canMerge: Boolean,
    val conflicted: Boolean,
    val vetoes: List<String>
)
