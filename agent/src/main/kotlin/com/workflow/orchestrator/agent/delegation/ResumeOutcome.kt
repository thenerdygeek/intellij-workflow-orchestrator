package com.workflow.orchestrator.agent.delegation

/**
 * Result of `DelegationOutboundService.attemptResume(handleId)`.
 *
 * Plan 4 spec §3.3, §5.3.
 */
sealed class ResumeOutcome {
    /** IDE-B confirmed the session is still alive. [currentState] is authoritative. */
    data class Resumed(val currentState: String) : ResumeOutcome()

    /** IDE-B's session is terminal. [summary] populated for `closeReason == "completed"`. */
    data class Closed(val closeReason: String, val summary: String?) : ResumeOutcome()

    /** IDE-B has no record of this session (pruned or never existed). */
    object NotFound : ResumeOutcome()

    /** Probe failed before the resume protocol could run (PING refused, no PONG). */
    data class ProbeFailed(val reason: String) : ResumeOutcome()
}
