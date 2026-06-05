package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.core.model.jira.JiraTicketData
import com.workflow.orchestrator.core.services.JiraService
import kotlinx.coroutines.CoroutineScope

/**
 * Polls Jira for a sprint's issue list and emits [MonitorEvent]s when issues are
 * added, removed, or change status within the sprint.
 *
 * ## Sprint resolution
 *
 * Either a [sprintId] or a [boardId] must be provided:
 * - **[sprintId] given** — used directly; [boardId] is ignored; no sprint-lookup call.
 * - **[boardId] given, [sprintId] null** — [resolveActiveSprint] is called on the first
 *   fetch and the result is cached in [resolvedSprintId]. Subsequent fetches reuse the
 *   cached id without calling [JiraService.getAvailableSprints] again.
 *
 * ## Caching rationale
 *
 * Once a board's active sprint is resolved it is kept for the lifetime of this source.
 * If a sprint rollover happens (the active sprint ends and a new one starts), this source
 * will continue to poll the **old** sprint.  That is an acceptable v1 trade-off: it is
 * far calmer than dumping an entire "added" batch for every ticket in the new sprint on
 * the first cycle after a rollover. Users who need to follow a new sprint should start a
 * fresh `monitor start source=jira_sprint board_id=…` command.
 *
 * ## Severity
 *
 * All sprint membership and status changes are [Severity.NOTABLE]. Jira has no inherent
 * failure semantic so ALERT is never used here. The first poll establishes a silent
 * baseline ([JiraSprintDiff.diff] returns empty when [previous] is null).
 */
class JiraSprintMonitorSource(
    monitorId: String,
    description: String,
    cs: CoroutineScope,
    private val jira: JiraService,
    private val boardId: Int?,
    private val sprintId: Int?,
) : PollingSource<List<JiraTicketData>>(monitorId, description, cs) {

    /**
     * Cached resolved sprint id.  Initialised from [sprintId] (may be null when only
     * [boardId] was provided).  Written exactly once per source lifetime when the active
     * sprint is first discovered via [resolveActiveSprint].
     */
    @Volatile private var resolvedSprintId: Int? = sprintId

    override suspend fun fetch(): List<JiraTicketData>? {
        val sid = resolvedSprintId
            ?: resolveActiveSprint()?.also { resolvedSprintId = it }
            ?: return null
        val r = jira.getSprintIssues(sid)
        return if (r.isError) null else r.data
    }

    /**
     * Resolves the currently active sprint for [boardId].
     * Returns null if [boardId] is null, if the [JiraService] call fails, or if no
     * sprint has state `"active"` (case-insensitive).
     */
    private suspend fun resolveActiveSprint(): Int? {
        val bid = boardId ?: return null
        val r = jira.getAvailableSprints(bid)
        if (r.isError) return null
        return r.data?.firstOrNull { it.state.equals("active", ignoreCase = true) }?.id
    }

    override fun diff(previous: List<JiraTicketData>?, current: List<JiraTicketData>): List<MonitorEvent> =
        JiraSprintDiff.diff(monitorId, previous, current)
}

/** Pure state-transition logic for the Jira sprint monitor.  Unit-testable with zero IDE deps. */
object JiraSprintDiff {

    /**
     * Compares the [previous] issue list to [current] and returns events for sprint
     * membership and status transitions.
     *
     * First-poll contract: [previous] == null → return emptyList (silent baseline).
     * Severity is always [Severity.NOTABLE] — Jira has no failure semantic.
     *
     * Event types:
     * - Issue in [current] but not [previous] → "sprint: {key} added ({status})"
     * - Issue in [previous] but not [current] → "sprint: {key} removed"
     * - Issue in both with different status (case-insensitive) → "sprint: {key} status {old} → {new}"
     */
    fun diff(
        monitorId: String,
        previous: List<JiraTicketData>?,
        current: List<JiraTicketData>,
    ): List<MonitorEvent> {
        if (previous == null) return emptyList()   // first poll = baseline only

        val prevByKey = previous.associateBy { it.key }
        val curByKey  = current.associateBy { it.key }

        val out = mutableListOf<MonitorEvent>()

        // Issues added to the sprint
        for (key in curByKey.keys - prevByKey.keys) {
            out += MonitorEvent(
                monitorId = monitorId,
                severity  = Severity.NOTABLE,
                line      = "sprint: $key added (${curByKey[key]!!.status})",
            )
        }

        // Issues removed from the sprint
        for (key in prevByKey.keys - curByKey.keys) {
            out += MonitorEvent(
                monitorId = monitorId,
                severity  = Severity.NOTABLE,
                line      = "sprint: $key removed",
            )
        }

        // Status changes on retained issues
        for (key in curByKey.keys intersect prevByKey.keys) {
            val p = prevByKey[key]!!
            val c = curByKey[key]!!
            if (!p.status.equals(c.status, ignoreCase = true)) {
                out += MonitorEvent(
                    monitorId = monitorId,
                    severity  = Severity.NOTABLE,
                    line      = "sprint: $key status ${p.status} → ${c.status}",
                )
            }
        }

        return out
    }
}
