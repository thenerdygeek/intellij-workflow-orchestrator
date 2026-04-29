package com.workflow.orchestrator.core.model.jira

import kotlinx.serialization.Serializable

@Serializable
data class DevStatusCommitData(
    val sha: String,
    val displayId: String,
    val message: String,
    val url: String,
    val authorName: String?,
    val authorTimestamp: String?,
    val merge: Boolean
)
