package com.workflow.orchestrator.core.model.workflow

data class TicketRef(val key: String, val summary: String)
data class RepoRef(
    val name: String,
    val projectKey: String,
    val repoSlug: String,
    val localVcsRootPath: String,
)
data class PrRef(
    val prId: Int,
    val fromBranch: String,
    val toBranch: String,
    val repoName: String,
    val bambooPlanKey: String?,
    val sonarProjectKey: String?,
)
data class BuildRef(
    val planKey: String,
    val buildNumber: Int,
    val branch: String,
    val selectedJobKey: String?,
)
data class QualityScope(
    val sonarProjectKey: String,
    val branchName: String?,
    val moduleKey: String?,
)
data class ModuleRef(val name: String, val rootPath: String)
