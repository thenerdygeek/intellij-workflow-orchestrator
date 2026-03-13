package com.workflow.orchestrator.jira.tasks

import com.intellij.icons.AllIcons
import com.intellij.tasks.Comment
import com.intellij.tasks.TaskState
import com.intellij.tasks.TaskType
import com.workflow.orchestrator.jira.api.dto.JiraIssue
import java.text.SimpleDateFormat
import java.util.Date
import javax.swing.Icon

/**
 * Wraps a [JiraIssue] DTO as an IntelliJ [com.intellij.tasks.Task].
 *
 * Used by [JiraTaskRepository] to surface Jira tickets in the native
 * Tools > Tasks > Open Task dialog.
 */
class JiraTask(
    private val issue: JiraIssue,
    private val baseUrl: String
) : com.intellij.tasks.Task() {

    override fun getId(): String = issue.key

    override fun getSummary(): String = issue.fields.summary

    override fun getDescription(): String? = issue.fields.description

    override fun getComments(): Array<Comment> = Comment.EMPTY_ARRAY

    override fun getIcon(): Icon = AllIcons.Nodes.Tag

    override fun getType(): TaskType {
        val typeName = issue.fields.issuetype?.name ?: return TaskType.OTHER
        return TaskType.valueOf(JiraTaskMapping.mapIssueType(typeName))
    }

    override fun getUpdated(): Date? = parseDate(issue.fields.updated)

    override fun getCreated(): Date? = parseDate(issue.fields.created)

    override fun isClosed(): Boolean {
        val categoryKey = issue.fields.status.statusCategory?.key ?: return false
        return categoryKey.lowercase() == "done"
    }

    override fun isIssue(): Boolean = true

    override fun getIssueUrl(): String {
        val trimmed = baseUrl.trimEnd('/')
        return "$trimmed/browse/${issue.key}"
    }

    override fun getState(): TaskState? {
        val categoryKey = issue.fields.status.statusCategory?.key ?: return TaskState.OPEN
        return TaskState.valueOf(JiraTaskMapping.mapStatusCategory(categoryKey))
    }

    private fun parseDate(dateString: String?): Date? {
        if (dateString == null) return null
        return try {
            // Jira REST API returns ISO 8601: 2026-03-13T10:30:00.000+0000
            val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ")
            format.parse(dateString)
        } catch (_: Exception) {
            null
        }
    }
}
