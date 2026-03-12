package com.workflow.orchestrator.mockserver.jira

import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap

@Serializable
data class JiraStatus(
    val id: String,
    val name: String,
    val statusCategory: JiraStatusCategory,
)

@Serializable
data class JiraStatusCategory(
    val id: Int,
    val key: String,
    val name: String,
)

@Serializable
data class JiraTransition(
    val id: String,
    val name: String,
    val to: JiraStatus,
    val fields: Map<String, TransitionField> = emptyMap(),
)

@Serializable
data class TransitionField(
    val required: Boolean,
    val name: String = "",
)

@Serializable
data class JiraIssue(
    val key: String,
    val summary: String,
    val status: JiraStatus,
    val issueType: String,
    val assignee: String?,
    val priority: String,
    val issueLinks: List<JiraIssueLink> = emptyList(),
    val comments: MutableList<JiraComment> = mutableListOf(),
    val worklogs: MutableList<JiraWorklog> = mutableListOf(),
)

@Serializable
data class JiraIssueLink(
    val type: String,
    val outwardIssue: String?,
    val inwardIssue: String?,
)

@Serializable
data class JiraComment(
    val id: String,
    val body: String,
    val author: String,
    val created: String,
)

@Serializable
data class JiraWorklog(
    val id: String,
    val timeSpentSeconds: Long,
    val comment: String,
    val author: String,
    val started: String,
)

@Serializable
data class JiraSprint(
    val id: Int,
    val name: String,
    val state: String,
    val boardId: Int,
)

@Serializable
data class JiraBoard(
    val id: Int,
    val name: String,
    val type: String,
)

class JiraState {
    var currentUser = "mock.user"
    var boards: MutableList<JiraBoard> = mutableListOf()
    var sprints: MutableList<JiraSprint> = mutableListOf()
    var issues: ConcurrentHashMap<String, JiraIssue> = ConcurrentHashMap()
    var statuses: List<JiraStatus> = emptyList()
    var transitionMap: Map<String, List<JiraTransition>> = emptyMap()

    fun getTransitionsForIssue(issueKey: String): List<JiraTransition> {
        val issue = issues[issueKey] ?: return emptyList()
        return transitionMap[issue.status.id] ?: emptyList()
    }

    fun applyTransition(issueKey: String, transitionId: String): Boolean {
        val issue = issues[issueKey] ?: return false
        val transitions = getTransitionsForIssue(issueKey)
        val transition = transitions.find { it.id == transitionId } ?: return false
        issues[issueKey] = issue.copy(status = transition.to)
        return true
    }

    fun getRequiredFieldsForTransition(issueKey: String, transitionId: String): Map<String, TransitionField> {
        val transitions = getTransitionsForIssue(issueKey)
        val transition = transitions.find { it.id == transitionId } ?: return emptyMap()
        return transition.fields.filter { it.value.required }
    }
}
