package com.workflow.orchestrator.jira.api.dto

import kotlinx.serialization.Serializable

// --- Issue DTOs ---

@Serializable
data class JiraIssue(
    val id: String,
    val key: String,
    val self: String = "",
    val fields: JiraIssueFields
)

@Serializable
data class JiraIssueFields(
    val summary: String,
    val status: JiraStatus,
    val issuetype: JiraIssueType? = null,
    val priority: JiraPriority? = null,
    val assignee: JiraUser? = null,
    val reporter: JiraUser? = null,
    val description: String? = null,
    val created: String? = null,
    val updated: String? = null,
    val issuelinks: List<JiraIssueLink> = emptyList(),
    val sprint: JiraSprintRef? = null,
    val labels: List<String> = emptyList(),
    val components: List<JiraComponent> = emptyList(),
    val subtasks: List<JiraSubtask> = emptyList(),
    val attachment: List<JiraAttachment> = emptyList()
)

@Serializable
data class JiraStatus(
    val id: String? = null,
    val name: String,
    val statusCategory: JiraStatusCategory? = null
)

@Serializable
data class JiraStatusCategory(
    val id: Int? = null,
    val key: String,
    val name: String
)

@Serializable
data class JiraIssueType(
    val id: String? = null,
    val name: String,
    val subtask: Boolean = false
)

@Serializable
data class JiraPriority(
    val id: String? = null,
    val name: String
)

@Serializable
data class JiraUser(
    val displayName: String,
    val emailAddress: String? = null,
    val name: String? = null
)

// --- Issue Links ---

@Serializable
data class JiraIssueLink(
    val id: String? = null,
    val type: JiraIssueLinkType,
    val inwardIssue: JiraLinkedIssue? = null,
    val outwardIssue: JiraLinkedIssue? = null
)

@Serializable
data class JiraIssueLinkType(
    val id: String? = null,
    val name: String,
    val inward: String,
    val outward: String
)

@Serializable
data class JiraLinkedIssue(
    val key: String,
    val fields: JiraLinkedIssueFields
)

@Serializable
data class JiraLinkedIssueFields(
    val summary: String,
    val status: JiraStatus
)

@Serializable
data class JiraSprintRef(
    val id: Int,
    val name: String,
    val state: String
)

// --- Board & Sprint DTOs ---

@Serializable
data class JiraBoard(
    val id: Int,
    val name: String,
    val type: String,
    val location: JiraBoardLocation? = null
)

@Serializable
data class JiraBoardLocation(
    val projectId: Int? = null,
    val projectName: String? = null,
    val projectKey: String? = null
)

@Serializable
data class JiraSprint(
    val id: Int,
    val name: String,
    val state: String,
    val startDate: String? = null,
    val endDate: String? = null,
    val originBoardId: Int? = null
)

// --- Transition DTOs ---

@Serializable
data class JiraTransition(
    val id: String,
    val name: String,
    val to: JiraStatus,
    val hasScreen: Boolean = false,
    val isGlobal: Boolean = false,
    val isConditional: Boolean = false,
    val fields: Map<String, JiraTransitionFieldMeta>? = null
)

@Serializable
data class JiraTransitionFieldMeta(
    val required: Boolean = false,
    val name: String = "",
    val schema: JiraFieldSchema? = null,
    val allowedValues: List<JiraFieldAllowedValue>? = null,
    val hasDefaultValue: Boolean = false,
    val autoCompleteUrl: String? = null
)

@Serializable
data class JiraFieldSchema(
    val type: String,
    val system: String? = null,
    val custom: String? = null,
    val items: String? = null
)

@Serializable
data class JiraFieldAllowedValue(
    val id: String,
    val name: String,
    val description: String? = null
)

// --- API Response Wrappers ---

@Serializable
data class JiraBoardSearchResult(
    val maxResults: Int = 50,
    val startAt: Int = 0,
    val total: Int = 0,
    val values: List<JiraBoard> = emptyList()
)

@Serializable
data class JiraSprintSearchResult(
    val maxResults: Int = 50,
    val startAt: Int = 0,
    val values: List<JiraSprint> = emptyList()
)

@Serializable
data class JiraIssueSearchResult(
    val maxResults: Int = 50,
    val startAt: Int = 0,
    val total: Int = 0,
    val issues: List<JiraIssue> = emptyList()
)

@Serializable
data class JiraTransitionList(
    val transitions: List<JiraTransition> = emptyList()
)

// --- Dev-Status DTOs (Development Panel branch/PR info) ---

@Serializable
data class DevStatusResponse(
    val detail: List<DevStatusDetail> = emptyList()
)

@Serializable
data class DevStatusDetail(
    val branches: List<DevStatusBranch> = emptyList(),
    val pullRequests: List<DevStatusPullRequest> = emptyList()
)

@Serializable
data class DevStatusBranch(
    val name: String,
    val url: String = ""
)

@Serializable
data class DevStatusPullRequest(
    val name: String = "",
    val url: String = "",
    val status: String = "",
    val lastUpdate: String? = null
)

// --- Components, Subtasks, Attachments, Comments ---

@Serializable
data class JiraComponent(
    val id: String? = null,
    val name: String,
    val description: String? = null
)

@Serializable
data class JiraSubtask(
    val id: String,
    val key: String,
    val fields: JiraSubtaskFields
)

@Serializable
data class JiraSubtaskFields(
    val summary: String,
    val status: JiraStatus,
    val issuetype: JiraIssueType? = null
)

@Serializable
data class JiraAttachment(
    val id: String,
    val filename: String,
    val author: JiraUser? = null,
    val mimeType: String? = null,
    val size: Long = 0,
    val created: String? = null,
    val content: String = ""
)

@Serializable
data class JiraComment(
    val id: String,
    val author: JiraUser? = null,
    val body: String = "",
    val created: String = "",
    val updated: String? = null
)

@Serializable
data class JiraCommentSearchResult(
    val startAt: Int = 0,
    val maxResults: Int = 50,
    val total: Int = 0,
    val comments: List<JiraComment> = emptyList()
)

// --- Worklog DTOs ---

@Serializable
data class JiraWorklogResponse(
    val worklogs: List<JiraWorklog> = emptyList(),
    val total: Int = 0
)

@Serializable
data class JiraWorklog(
    val author: JiraUser? = null,
    val timeSpent: String = "",
    val timeSpentSeconds: Long = 0,
    val comment: String? = null,
    val started: String = "",
    val created: String = ""
)

// --- Ticket Key Validation ---

data class TicketKeyInfo(
    val key: String,
    val summary: String,
    val status: String
)
