package com.workflow.orchestrator.core.services.jira

import com.workflow.orchestrator.core.services.ToolResult
import com.workflow.orchestrator.core.model.jira.TransitionInput
import com.workflow.orchestrator.core.model.jira.TransitionMeta
import com.workflow.orchestrator.core.model.jira.TransitionOutcome

interface TicketTransitionService {
    suspend fun getAvailableTransitions(ticketKey: String): ToolResult<List<TransitionMeta>>
    suspend fun prepareTransition(ticketKey: String, transitionId: String): ToolResult<TransitionMeta>
    suspend fun executeTransition(ticketKey: String, input: TransitionInput): ToolResult<TransitionOutcome>
    suspend fun tryAutoTransition(
        ticketKey: String,
        transitionId: String,
        comment: String? = null
    ): ToolResult<TransitionOutcome>
}
