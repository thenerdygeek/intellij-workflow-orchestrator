package com.workflow.orchestrator.core.model.jira

data class DevStatusCommitData(
    val displayId: String,
    val message: String,
    val url: String,
    val authorName: String?,
    val authorTimestamp: String?,
    val merge: Boolean
)
