package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.core.model.jira.JiraTicketData
import com.workflow.orchestrator.core.services.JiraService
import kotlinx.coroutines.CoroutineScope

/**
 * Polls Jira for a single ticket's state and emits [MonitorEvent]s when
 * [status] or [assignee] transitions are observed.
 *
 * All Jira events are [Severity.NOTABLE] — Jira has no inherent failure semantic so
 * ALERT is never used here. The first poll establishes a silent baseline (no events);
 * subsequent polls diff against the previous snapshot.
 */
class JiraTicketMonitorSource(
    monitorId: String,
    description: String,
    cs: CoroutineScope,
    private val jira: JiraService,
    private val ticketKey: String,
) : PollingSource<JiraTicketData>(monitorId, description, cs) {

    override suspend fun fetch(): JiraTicketData? {
        val r = jira.getTicket(ticketKey)
        return if (r.isError) null else r.data
    }

    override fun diff(previous: JiraTicketData?, current: JiraTicketData): List<MonitorEvent> =
        JiraTicketDiff.diff(monitorId, previous, current)
}

/** Pure state-transition logic for the Jira ticket monitor. Unit-testable with zero IDE. */
object JiraTicketDiff {

    /**
     * Compares [previous] to [current] and returns events for changed fields.
     *
     * First-poll contract: [previous] == null → return emptyList (silent baseline).
     * Severity is always [Severity.NOTABLE] — Jira has no failure semantic.
     */
    fun diff(
        monitorId: String,
        previous: JiraTicketData?,
        current: JiraTicketData,
    ): List<MonitorEvent> {
        if (previous == null) return emptyList()   // first poll = baseline only

        val out = mutableListOf<MonitorEvent>()

        // Status change (case-insensitive)
        if (!previous.status.equals(current.status, ignoreCase = true)) {
            out += MonitorEvent(
                monitorId = monitorId,
                severity = Severity.NOTABLE,
                line = "${current.key}: status ${previous.status} → ${current.status}",
            )
        }

        // Assignee change (includes null ↔ name transitions)
        if (previous.assignee != current.assignee) {
            out += MonitorEvent(
                monitorId = monitorId,
                severity = Severity.NOTABLE,
                line = "${current.key}: assignee ${previous.assignee ?: "none"} → ${current.assignee ?: "none"}",
            )
        }

        return out
    }
}
