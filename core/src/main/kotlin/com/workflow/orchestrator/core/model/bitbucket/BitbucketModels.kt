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
