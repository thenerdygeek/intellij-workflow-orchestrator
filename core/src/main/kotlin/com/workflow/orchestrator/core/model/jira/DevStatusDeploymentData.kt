package com.workflow.orchestrator.core.model.jira

import kotlinx.serialization.Serializable

@Serializable
data class DevStatusDeploymentData(
    val displayName: String,
    val url: String,
    val state: String,
    val environmentName: String?,
    val environmentType: String?,
    val lastUpdated: String?
)
