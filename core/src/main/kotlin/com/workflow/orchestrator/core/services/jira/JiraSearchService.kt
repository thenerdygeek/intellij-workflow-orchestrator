package com.workflow.orchestrator.core.services.jira

import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.model.jira.ComponentSuggestion
import com.workflow.orchestrator.core.model.jira.FieldOption
import com.workflow.orchestrator.core.model.jira.GroupSuggestion
import com.workflow.orchestrator.core.model.jira.LabelSuggestion
import com.workflow.orchestrator.core.model.jira.UserSuggestion
import com.workflow.orchestrator.core.model.jira.VersionSuggestion

interface JiraSearchService {
    suspend fun searchAssignableUsers(ticketKey: String, query: String, limit: Int = 20): ToolResult<List<UserSuggestion>>
    suspend fun searchUsers(query: String, limit: Int = 20): ToolResult<List<UserSuggestion>>
    suspend fun suggestLabels(query: String, limit: Int = 20): ToolResult<List<LabelSuggestion>>
    suspend fun searchGroups(query: String, limit: Int = 20): ToolResult<List<GroupSuggestion>>
    suspend fun listVersions(projectKey: String): ToolResult<List<VersionSuggestion>>
    suspend fun listComponents(projectKey: String): ToolResult<List<ComponentSuggestion>>
    suspend fun followAutoCompleteUrl(url: String, query: String): ToolResult<List<FieldOption>>
}
