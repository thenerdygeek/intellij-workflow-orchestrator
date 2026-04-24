package com.workflow.orchestrator.core.model.jira

import kotlinx.serialization.Serializable

/**
 * Lightweight board summary returned by [com.workflow.orchestrator.core.services.JiraService.searchBoards].
 * Contains the minimum fields needed to identify a board and route further calls.
 */
@Serializable
data class JiraBoardSummary(
    /** Jira board ID. */
    val id: Long,
    /** Human-readable board name. */
    val name: String,
    /** Board type — typically "scrum" or "kanban". */
    val type: String
)
