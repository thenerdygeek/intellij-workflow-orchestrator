package com.workflow.orchestrator.agent.delegation

sealed class DelegationException(message: String) : RuntimeException(message) {
    object UserCanceledPicker : DelegationException("user_canceled_picker")
    object TargetNotReachable : DelegationException("target_not_reachable")
    object LimitReached : DelegationException("delegation_limit_reached")
    /**
     * The target IDE declined the request. **RETRYABLE** — the handle (if any) is still valid; the
     * target is simply unwilling or unable right now. Covers both fresh-send declines and the
     * TRANSIENT continuation case `ide_b_busy` (target alive but its agent tab is occupied — retry
     * shortly) as well as `declined_timeout` and consent declines. Mapped uniformly across the
     * fresh-send and continuation paths so busy is always `Rejected`, never [Expired].
     */
    data class Rejected(val rejectReason: String?) :
        DelegationException("rejected: ${rejectReason ?: "no_reason"}")
    /**
     * The handle is GONE / no longer usable — distinct from a transient [Rejected]. Thrown when a
     * re-association / continuation attempt determines the handle's remote session is genuinely gone:
     * `session_closed` (terminal close), `session_not_found` (pruned / never seen),
     * `ide_b_not_running` (target IDE down), `resume_failed` (session locked/missing on disk), and
     * similar terminal-gone reasons. A BUSY target is NOT expired — it maps to [Rejected] (retryable).
     */
    data class Expired(val expireReason: String?) :
        DelegationException("expired: ${expireReason ?: "no_reason"}")

    /**
     * `delegation_answer` was called with a handle the outbound service does not
     * track (likely closed, never opened, or owned by a different session).
     */
    data class HandleNotFound(val handleId: String) :
        DelegationException("handle_not_found: $handleId")

    /**
     * `delegation_answer` reached the outbound channel but writing the Answer
     * frame failed. Distinct from [HandleNotFound] so the LLM can decide whether
     * to retry the same handle or give up.
     */
    data class WriteFailed(val ioReason: String) :
        DelegationException("write_failed: $ioReason")

    /**
     * Idle timeout fired by [IdleTimer] when no IPC traffic has been received on
     * [handle] for more than `delegationIdleTimeoutMinutes`. Surfaces to Agent-A
     * via [DelegationException.Expired] with `reason="idle_timeout"`; this type
     * is the internal representation.
     *
     * Plan 3 spec §4.3.
     */
    data class IdleTimedOut(
        val handle: DelegationHandle,
        val lastSeenAt: Long,
    ) : DelegationException("idle_timeout (lastSeenAt=$lastSeenAt)")
}
