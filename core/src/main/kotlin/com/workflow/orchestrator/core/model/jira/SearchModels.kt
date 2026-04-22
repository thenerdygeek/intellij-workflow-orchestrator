package com.workflow.orchestrator.core.model.jira

data class UserSuggestion(
    val name: String,
    val displayName: String,
    val email: String?,
    val avatarUrl: String?,
    val active: Boolean
)

data class LabelSuggestion(val label: String)

data class VersionSuggestion(
    val id: String,
    val name: String,
    val released: Boolean,
    val archived: Boolean
)

data class ComponentSuggestion(
    val id: String,
    val name: String,
    val description: String?
)

data class GroupSuggestion(val name: String)
