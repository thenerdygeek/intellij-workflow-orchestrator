package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.agent.tools.integration.sonar.IssueSeverity
import com.workflow.orchestrator.core.model.sonar.SonarIssueData
import com.workflow.orchestrator.core.services.SonarService
import kotlinx.coroutines.CoroutineScope

/**
 * [PollingSource] that polls a SonarQube project for open issues, optionally filtered to a
 * minimum severity, and emits [MonitorEvent]s when issues are added or resolved between polls.
 *
 * ## Severity filter
 *
 * When [minSeverity] is non-null, [fetch] filters the returned list client-side (SonarService's
 * `getIssues` has no severity-filter param). Only issues meeting the threshold enter the snapshot,
 * so transitions involving filtered-out issues are never emitted — a MINOR issue cannot appear as
 * "added" or "resolved" when the monitor is set to `minSeverity=MAJOR`.
 *
 * ## Diff strategy
 *
 * [diff] delegates to [SonarIssuesDiff.diff]. The first poll establishes a silent baseline
 * ([SonarIssuesDiff.diff] returns empty when [previous] is null).
 *
 * ## Retained-issue status changes (v1 intentional omission)
 *
 * Status changes on issues present in both [previous] and [current] (retained issues) are
 * intentionally NOT emitted in this version. The primary signal of interest is new high-severity
 * regressions and confirmed fixes (resolved issues), not every transition of a known issue. This
 * keeps monitor noise low; a future version may optionally emit retained-status changes via a
 * `watch_transitions` parameter.
 */
class SonarIssuesSource(
    monitorId: String,
    description: String,
    cs: CoroutineScope,
    private val sonar: SonarService,
    private val projectKey: String,
    private val branch: String?,
    private val minSeverity: String?,
) : PollingSource<List<SonarIssueData>>(monitorId, description, cs) {

    override suspend fun fetch(): List<SonarIssueData>? {
        val res = sonar.getIssues(projectKey, branch = branch)
        if (res.isError) return null
        val all = res.data ?: return null
        return if (minSeverity == null) all
               else all.filter { IssueSeverity.meetsMinSeverity(it.severity, minSeverity) }
    }

    override fun diff(previous: List<SonarIssueData>?, current: List<SonarIssueData>): List<MonitorEvent> =
        SonarIssuesDiff.diff(monitorId, projectKey, previous, current)
}

/**
 * Pure state-transition logic for the Sonar issues monitor. Unit-testable with zero IDE deps.
 *
 * ### Severity mapping
 * - New BLOCKER or CRITICAL → [Severity.ALERT]
 * - New MAJOR / MINOR / INFO → [Severity.NOTABLE]
 * - Any resolved issues (present in previous, absent from current) → one [Severity.NOTABLE] with count
 *
 * ### Retained-issue status changes (v1 intentional omission)
 * Issues present in both [previous] and [current] (the "retained" set from [ListDiff]) are not
 * examined for status transitions. This is an explicit v1 design choice to keep monitor noise low —
 * the key signals are new regressions and confirmed resolutions. Add retained-status diffing in a
 * future version with a dedicated parameter if needed.
 */
object SonarIssuesDiff {

    private val HIGH_SEVERITY = setOf("BLOCKER", "CRITICAL")

    /**
     * Compares [previous] to [current] and returns events for new and resolved issues.
     *
     * First-poll contract: [previous] == null → return emptyList (silent baseline).
     */
    fun diff(
        monitorId: String,
        projectKey: String,
        previous: List<SonarIssueData>?,
        current: List<SonarIssueData>,
    ): List<MonitorEvent> {
        if (previous == null) return emptyList()   // first poll = baseline only

        val changes = ListDiff.byKey(previous, current) { it.key }
        val out = mutableListOf<MonitorEvent>()

        // New issues added since the last snapshot
        for (i in changes.added) {
            val sev = if (i.severity.uppercase() in HIGH_SEVERITY) Severity.ALERT else Severity.NOTABLE
            out += MonitorEvent(
                monitorId = monitorId,
                severity  = sev,
                line      = "Sonar $projectKey: new ${i.severity} ${i.type} ${i.key} — ${i.message.take(80)}",
            )
        }

        // Issues that were in the previous snapshot but are gone now (resolved/closed)
        if (changes.removed.isNotEmpty()) {
            out += MonitorEvent(
                monitorId = monitorId,
                severity  = Severity.NOTABLE,
                line      = "Sonar $projectKey: ${changes.removed.size} issue(s) resolved",
            )
        }

        return out
    }
}
