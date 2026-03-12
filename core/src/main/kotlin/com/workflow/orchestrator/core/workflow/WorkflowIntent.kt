package com.workflow.orchestrator.core.workflow

enum class WorkflowIntent(
    val displayName: String,
    val defaultNames: List<String>,
    val targetCategory: String?
) {
    START_WORK(
        displayName = "Start Work",
        defaultNames = listOf(
            "In Progress", "Start Progress", "Begin Work",
            "Start Development", "Begin Development"
        ),
        targetCategory = "indeterminate"
    ),
    SUBMIT_FOR_REVIEW(
        displayName = "Submit for Review",
        defaultNames = listOf(
            "In Review", "Submit for Review", "Ready for Review",
            "Code Review", "Peer Review"
        ),
        targetCategory = "indeterminate"
    ),
    CLOSE(
        displayName = "Close",
        defaultNames = listOf("Done", "Closed", "Resolved", "Complete", "Finished"),
        targetCategory = "done"
    ),
    REOPEN(
        displayName = "Reopen",
        defaultNames = listOf("Reopen", "Re-open", "Back to Open", "Backlog"),
        targetCategory = "new"
    ),
    BLOCK(
        displayName = "Block",
        defaultNames = listOf("Blocked", "On Hold", "Impediment"),
        targetCategory = null
    )
}
