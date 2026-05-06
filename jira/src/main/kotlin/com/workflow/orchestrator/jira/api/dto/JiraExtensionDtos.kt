package com.workflow.orchestrator.jira.api.dto

import kotlinx.serialization.Serializable

// --- /rest/api/2/mypermissions ---

@Serializable
data class JiraPermissions(
    val permissions: Map<String, JiraPermission> = emptyMap()
)

@Serializable
data class JiraPermission(
    val key: String = "",
    val name: String = "",
    val type: String? = null,
    val havePermission: Boolean = false,
    val deprecatedKey: Boolean = false
)

// --- /rest/api/2/field (flat array; no wrapper) ---

@Serializable
data class JiraField(
    val id: String,
    val name: String,
    val custom: Boolean = false,
    val schema: JiraFieldSchemaInfo? = null
)

@Serializable
data class JiraFieldSchemaInfo(
    val type: String? = null,
    val items: String? = null,
    val custom: String? = null
)

// --- /rest/api/2/issue/{key}/remotelink ---

@Serializable
data class JiraRemoteLink(
    val id: Long,
    val relationship: String? = null,
    val application: JiraRemoteLinkApplication? = null,
    val `object`: JiraRemoteLinkObject? = null
)

@Serializable
data class JiraRemoteLinkApplication(
    val type: String? = null,
    val name: String? = null
)

@Serializable
data class JiraRemoteLinkObject(
    val url: String = "",
    val title: String? = null
)

// --- /rest/api/2/issue/{key}/watchers ---

@Serializable
data class JiraWatchers(
    val watchCount: Int = 0,
    val isWatching: Boolean = false,
    val watchers: List<JiraWatcherUser> = emptyList()
)

@Serializable
data class JiraWatcherUser(
    val name: String = "",
    val displayName: String = "",
    val emailAddress: String? = null
)

// --- /rest/api/2/myself?expand=groups,applicationRoles ---

@Serializable
data class JiraMyself(
    val name: String = "",
    val displayName: String = "",
    val emailAddress: String? = null,
    val groups: JiraNamedItemPage? = null,
    val applicationRoles: JiraNamedItemPage? = null
)

/** Shared shape for Jira's `{items:[{name:…}]}` response sections. */
@Serializable
data class JiraNamedItemPage(
    val size: Int = 0,
    val items: List<JiraNamedItem> = emptyList()
)

@Serializable
data class JiraNamedItem(
    val name: String = ""
)

// --- /rest/api/2/issue/picker?… ---

@Serializable
data class JiraIssuePickerResult(
    val sections: List<JiraIssuePickerSection> = emptyList()
)

@Serializable
data class JiraIssuePickerSection(
    val id: String = "",
    val label: String = "",
    val sub: String? = null,
    val issues: List<JiraIssuePickerEntry> = emptyList()
)

@Serializable
data class JiraIssuePickerEntry(
    val key: String,
    val keyHtml: String? = null,
    val img: String? = null,
    val summary: String = "",
    val summaryText: String? = null
)

// --- /rest/api/2/filter/favourite and /rest/api/2/filter/{id} ---

@Serializable
data class JiraFilter(
    val id: String,
    val name: String,
    val description: String? = null,
    val jql: String? = null,
    val viewUrl: String? = null,
    val owner: JiraFilterOwner? = null
)

@Serializable
data class JiraFilterOwner(
    val name: String = "",
    val displayName: String = ""
)

// --- /rest/api/2/issue/{key}?expand=renderedFields,changelog ---

/**
 * Wrapper response shape for the combined rich-fields-plus-changelog fetch.
 * The detail panel and history view both read from the same response.
 */
@Serializable
data class JiraIssueWithChangelog(
    val id: String,
    val key: String,
    val self: String = "",
    val fields: JiraIssueContextFields,
    val renderedFields: JiraRenderedFields? = null,
    val changelog: IssueChangelog? = null
)

@Serializable
data class IssueChangelog(
    val histories: List<ChangelogHistory> = emptyList()
)

@Serializable
data class ChangelogHistory(
    val id: String = "",
    val author: ChangelogAuthor? = null,
    val created: String = "",
    val items: List<ChangelogItem> = emptyList()
)

@Serializable
data class ChangelogAuthor(
    val name: String = "",
    val displayName: String = ""
)

@Serializable
data class ChangelogItem(
    val field: String = "",
    val fieldtype: String? = null,
    val fromString: String? = null,
    val toString: String? = null
)
