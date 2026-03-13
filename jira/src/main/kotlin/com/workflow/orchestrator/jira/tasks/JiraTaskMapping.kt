package com.workflow.orchestrator.jira.tasks

/** Maps Jira issue fields to IntelliJ Task system types (as strings for testability). */
object JiraTaskMapping {
    fun mapIssueType(jiraTypeName: String): String = when (jiraTypeName.lowercase()) {
        "bug" -> "BUG"
        "story", "task", "improvement", "new feature" -> "FEATURE"
        else -> "OTHER"
    }

    fun mapStatusCategory(categoryKey: String): String = when (categoryKey.lowercase()) {
        "new", "undefined" -> "OPEN"
        "indeterminate" -> "IN_PROGRESS"
        "done" -> "RESOLVED"
        else -> "OPEN"
    }
}
