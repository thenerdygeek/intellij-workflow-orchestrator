package com.workflow.orchestrator.core.model.jira

import kotlinx.serialization.Serializable

@Serializable
data class DevStatusBundle(
    val branches: List<DevStatusBranchData>,
    val pullRequests: List<DevStatusPrData>,
    val commits: List<DevStatusCommitData>,
    val builds: List<DevStatusBuildData>,
    val deployments: List<DevStatusDeploymentData>,
    val reviews: List<DevStatusReviewData>,
    val fetchErrors: Int = 0,
    val fetchedAt: Long
) {
    val isEmpty: Boolean
        get() = branches.isEmpty() && pullRequests.isEmpty() && commits.isEmpty() &&
            builds.isEmpty() && deployments.isEmpty() && reviews.isEmpty()

    fun summaryLine(): String {
        val errorSuffix = if (fetchErrors > 0) " ($fetchErrors of 6 feeds errored)" else ""
        if (isEmpty) return "no linked development activity$errorSuffix"
        val base = buildList {
            if (branches.isNotEmpty()) add("${branches.size} ${if (branches.size == 1) "branch" else "branches"}")
            if (pullRequests.isNotEmpty()) add("${pullRequests.size} ${if (pullRequests.size == 1) "PR" else "PRs"}")
            if (commits.isNotEmpty()) add("${commits.size} ${if (commits.size == 1) "commit" else "commits"}")
            if (builds.isNotEmpty()) add("${builds.size} ${if (builds.size == 1) "build" else "builds"}")
            if (deployments.isNotEmpty()) add("${deployments.size} ${if (deployments.size == 1) "deployment" else "deployments"}")
            if (reviews.isNotEmpty()) add("${reviews.size} ${if (reviews.size == 1) "review" else "reviews"}")
        }.joinToString(", ")
        return base + errorSuffix
    }
}
