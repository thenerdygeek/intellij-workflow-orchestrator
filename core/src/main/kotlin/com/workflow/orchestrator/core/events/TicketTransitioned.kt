package com.workflow.orchestrator.core.events

import com.workflow.orchestrator.core.model.jira.StatusRef

/** Emitted by :jira when a ticket is transitioned from one status to another. */
data class TicketTransitioned(
    val key: String,
    val fromStatus: StatusRef,
    val toStatus: StatusRef,
    val transitionId: String
) : WorkflowEvent()
