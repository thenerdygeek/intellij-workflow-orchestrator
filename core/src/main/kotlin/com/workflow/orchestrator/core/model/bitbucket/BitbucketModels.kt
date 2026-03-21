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
