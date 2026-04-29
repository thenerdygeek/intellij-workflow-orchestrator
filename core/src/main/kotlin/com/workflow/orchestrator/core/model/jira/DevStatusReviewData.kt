package com.workflow.orchestrator.core.model.jira

import kotlinx.serialization.Serializable

@Serializable
data class DevStatusReviewData(
    val name: String,
    val url: String,
    val state: String,
    val reviewerNames: List<String>,
    val lastUpdated: String?
)
