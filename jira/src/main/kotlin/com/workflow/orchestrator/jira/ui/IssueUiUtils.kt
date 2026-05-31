package com.workflow.orchestrator.jira.ui

import com.workflow.orchestrator.jira.api.dto.JiraIssue

/**
 * Shared UI utilities for [JiraIssue] display.
 *
 * Centralises sentinel conventions so that [SprintDashboardPanel] and
 * [TicketListCellRenderer] stay in sync on the section-header prefix without
 * duplicating the predicate.
 */

/** Returns true when this [JiraIssue] is a synthetic section-header row (not a real ticket). */
fun JiraIssue.isSectionHeader(): Boolean = id.startsWith("header-")
