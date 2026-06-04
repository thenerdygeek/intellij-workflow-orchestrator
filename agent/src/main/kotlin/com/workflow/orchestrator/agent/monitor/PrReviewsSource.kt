package com.workflow.orchestrator.agent.monitor

import com.workflow.orchestrator.core.model.bitbucket.ParticipantData
import com.workflow.orchestrator.core.services.BitbucketService
import kotlinx.coroutines.CoroutineScope

/**
 * Polls Bitbucket for the reviewer participants of a pull request and emits [MonitorEvent]s
 * when a reviewer's approval status changes.  Delegates diff logic to the pure [PrReviewsDiff]
 * object so the logic is independently unit-testable.
 *
 * @param prId      Bitbucket PR id to watch.
 * @param repoName  Optional repo override (null = primary repo from settings).
 */
class PrReviewsSource(
    monitorId: String,
    description: String,
    cs: CoroutineScope,
    private val bitbucket: BitbucketService,
    private val prId: Int,
    private val repoName: String?,
) : PollingSource<List<ParticipantData>>(monitorId, description, cs) {

    override suspend fun fetch(): List<ParticipantData>? {
        val res = bitbucket.getPullRequestParticipants(prId, repoName)
        return if (res.isError) null else res.data
    }

    override fun diff(
        previous: List<ParticipantData>?,
        current: List<ParticipantData>,
    ): List<MonitorEvent> = PrReviewsDiff.diff(monitorId, prId, previous, current)
}

// ─────────────────────────────────────────────────────────────────────────────
// Pure diff logic — no IDE or coroutine dependencies; fully unit-testable.
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Pure state-transition logic for PR-reviewer status changes.
 *
 * Rules:
 * - Index [previous] participants by `username` (case-sensitive; Bitbucket usernames are stable).
 * - For each current participant compare [ParticipantData.status] (normalised to upper-case):
 *   - **Status changed** (including null-prev = new participant): emit ONE event unless the
 *     participant is new/first-poll AND the new status is `UNAPPROVED` (not a meaningful signal).
 * - Severity: `NEEDS_WORK` → [Severity.ALERT]; all other statuses → [Severity.NOTABLE].
 * - [MonitorEvent.line] format: `"PR #<prId>: <displayName> → <STATUS>"`.
 */
object PrReviewsDiff {

    /** Status values that carry a meaningful review signal (emit even on first poll / new entry). */
    private val MEANINGFUL_STATUSES = setOf("APPROVED", "NEEDS_WORK")

    private fun severityFor(status: String): Severity = when (status.uppercase()) {
        "NEEDS_WORK" -> Severity.ALERT
        else -> Severity.NOTABLE
    }

    /**
     * Compare [previous] participant list to [current] and return events for any reviewer whose
     * status changed in a notable way.
     *
     * @param monitorId  Forwarded to every produced [MonitorEvent].
     * @param prId       PR number, used in the event line.
     * @param previous   Snapshot from the prior poll; null on the very first poll.
     * @param current    Latest snapshot from the API.
     */
    fun diff(
        monitorId: String,
        prId: Int,
        previous: List<ParticipantData>?,
        current: List<ParticipantData>,
    ): List<MonitorEvent> {
        val previousByUser: Map<String, ParticipantData> = previous?.associateBy { it.username }
            ?: emptyMap()

        return buildList {
            for (p in current) {
                val prevEntry = previousByUser[p.username]
                val prevStatus = prevEntry?.status?.uppercase()
                val curStatus  = p.status.uppercase()

                val statusChanged = prevStatus != curStatus

                if (!statusChanged) continue  // no change, skip

                // New or first-poll participant: only emit for meaningful statuses.
                if (prevEntry == null && curStatus !in MEANINGFUL_STATUSES) continue

                val line = "PR #$prId: ${p.displayName} → $curStatus"
                add(MonitorEvent(monitorId, severityFor(curStatus), line))
            }
        }
    }
}
