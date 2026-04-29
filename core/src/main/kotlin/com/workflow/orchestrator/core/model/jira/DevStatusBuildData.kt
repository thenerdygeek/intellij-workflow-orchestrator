package com.workflow.orchestrator.core.model.jira

data class DevStatusBuildData(
    val name: String,
    val url: String,
    val state: String,
    val lastUpdated: String?,
    val description: String?
)
