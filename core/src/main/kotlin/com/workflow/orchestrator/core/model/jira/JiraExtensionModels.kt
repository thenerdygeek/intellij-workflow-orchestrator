package com.workflow.orchestrator.core.model.jira

import kotlinx.serialization.Serializable

/**
 * `GET /rest/api/2/mypermissions` — global or project-scoped permission flags.
 * Deprecated keys are filtered out by the impl so consumers see modern keys
 * (e.g. `EDIT_ISSUES`, not `EDIT_ISSUE`).
 */
@Serializable
data class MyPermissionsData(
    val permissions: Map<String, PermissionFlag>
)

@Serializable
data class PermissionFlag(
    val key: String,
    val name: String,
    val havePermission: Boolean,
    val deprecated: Boolean = false
)

/**
 * `GET /rest/api/2/field` — field metadata. The Jira response is a flat array;
 * we keep id/name/custom + the schema's type when present.
 */
@Serializable
data class JiraFieldData(
    val id: String,
    val name: String,
    val isCustom: Boolean,
    val schemaType: String? = null
)

/**
 * `GET /rest/api/2/issue/{key}/remotelink` — confluence pages, web links, etc.
 * `applicationType` and `applicationName` come from the `application` sub-object;
 * `url` and `title` from the `object` sub-object.
 */
@Serializable
data class RemoteLinkData(
    val id: Long,
    val applicationType: String? = null,
    val applicationName: String? = null,
    val relationship: String? = null,
    val url: String,
    val title: String? = null
)

/**
 * `GET /rest/api/2/issue/{key}/watchers` — watcher list with self/watching state.
 */
@Serializable
data class WatchersData(
    val watchCount: Int,
    val isWatching: Boolean,
    val watchers: List<WatcherUser> = emptyList()
)

@Serializable
data class WatcherUser(
    val name: String,
    val displayName: String,
    val emailAddress: String? = null
)

/**
 * `GET /rest/api/2/myself?expand=groups,applicationRoles` — current user
 * with group memberships and application roles flattened to name lists.
 */
@Serializable
data class MyselfData(
    val name: String,
    val displayName: String,
    val emailAddress: String? = null,
    val groups: List<String> = emptyList(),
    val applicationRoles: List<String> = emptyList()
)

/**
 * `GET /rest/api/2/issue/picker?…` — flattened across all returned sections.
 * `summaryText` is the plain rendering and is preferred over `summary` (which
 * may contain HTML highlighting) when present.
 */
@Serializable
data class IssueSuggestion(
    val key: String,
    val summary: String,
    val summaryText: String? = null,
    val iconUrl: String? = null
)

/**
 * `GET /rest/api/2/filter/favourite` and `GET /rest/api/2/filter/{id}` — saved
 * JQL filter. `jql` is always present on the detail endpoint, sometimes on
 * favourites.
 */
@Serializable
data class FilterData(
    val id: Long,
    val name: String,
    val description: String? = null,
    val jql: String? = null,
    val viewUrl: String? = null,
    val owner: String? = null
)

/**
 * One row of a Jira issue's changelog, flattened from `(history, item)` pairs
 * so the UI can render per-field rows directly.
 */
@Serializable
data class TicketHistoryEntry(
    val actorDisplayName: String,
    val createdAt: String,
    val field: String,
    val oldValue: String? = null,
    val newValue: String? = null
)
