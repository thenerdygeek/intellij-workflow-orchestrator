package com.workflow.orchestrator.core.model

data class LinkResolution(
    val kind: Kind,
    val raw: String,
    val displayLabel: String,
    val targetDescription: String,
) {
    enum class Kind { FILE, CLASS, JIRA, WEB }
}
