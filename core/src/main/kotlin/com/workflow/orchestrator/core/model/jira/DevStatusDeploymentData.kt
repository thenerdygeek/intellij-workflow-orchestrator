package com.workflow.orchestrator.core.model.jira

data class DevStatusDeploymentData(
    val displayName: String,
    val url: String,
    val state: String,
    val environmentName: String?,
    val environmentType: String?,
    val lastUpdated: String?
)
