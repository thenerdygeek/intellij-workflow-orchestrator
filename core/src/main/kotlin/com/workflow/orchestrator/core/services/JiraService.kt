package com.workflow.orchestrator.core.services

import com.workflow.orchestrator.core.model.jira.JiraTicketData
import com.workflow.orchestrator.core.model.jira.JiraTransitionData

/**
 * Jira operations used by both UI panels and AI agent.
 * Implementations registered as project-level services by :jira module.
 */
interface JiraService {
    /** Get ticket details. */
    suspend fun getTicket(key: String): ToolResult<JiraTicketData>

    /** Get available transitions for a ticket. */
    suspend fun getTransitions(key: String): ToolResult<List<JiraTransitionData>>

    /** Transition a ticket to a new status. */
    suspend fun transition(key: String, transitionId: String): ToolResult<Unit>

    /** Add a comment to a ticket. */
    suspend fun addComment(key: String, body: String): ToolResult<Unit>

    /** Log work on a ticket. */
    suspend fun logWork(key: String, timeSpent: String, comment: String?): ToolResult<Unit>

    /** Test the Jira connection. */
    suspend fun testConnection(): ToolResult<Unit>
}
