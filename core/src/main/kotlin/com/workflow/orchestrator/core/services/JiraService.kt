package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.model.jira.DevStatusPrData
import com.workflow.orchestrator.core.model.jira.JiraCommentData
import com.workflow.orchestrator.core.model.jira.JiraTicketData
import com.workflow.orchestrator.core.model.jira.JiraTransitionData
import com.workflow.orchestrator.core.model.jira.SprintData
import com.workflow.orchestrator.core.model.jira.WorklogData

/**
 * Jira operations used by both UI panels and AI agent.
 * Implementations registered as project-level services by :jira module.
 */
interface JiraService {
    /** Get ticket details. */
    suspend fun getTicket(key: String): ToolResult<JiraTicketData>

    /** Get available transitions for a ticket. */
    suspend fun getTransitions(key: String): ToolResult<List<JiraTransitionData>>

    /** Transition a ticket to a new status, with optional fields and comment. */
    suspend fun transition(
        key: String,
        transitionId: String,
        fields: Map<String, Any>? = null,
        comment: String? = null
    ): ToolResult<Unit>

    /** Add a comment to a ticket. */
    suspend fun addComment(key: String, body: String): ToolResult<Unit>

    /** Log work on a ticket. */
    suspend fun logWork(key: String, timeSpent: String, comment: String?): ToolResult<Unit>

    /** Get comments for a ticket. */
    suspend fun getComments(key: String): ToolResult<List<JiraCommentData>>

    /** Get worklogs for a ticket. */
    suspend fun getWorklogs(issueKey: String): ToolResult<List<WorklogData>>

    /** Get available sprints (closed + active) for a board. */
    suspend fun getAvailableSprints(boardId: Int): ToolResult<List<SprintData>>

    /** Get pull requests linked to a ticket via dev-status. */
    suspend fun getLinkedPullRequests(issueId: String): ToolResult<List<DevStatusPrData>>

    /** Test the Jira connection. */
    suspend fun testConnection(): ToolResult<Unit>
}
