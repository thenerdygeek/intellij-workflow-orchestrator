package com.workflow.orchestrator.agent.delegation

sealed class DelegationException(message: String) : RuntimeException(message) {
    object UserCanceledPicker : DelegationException("user_canceled_picker")
    object TargetNotReachable : DelegationException("target_not_reachable")
    object LimitReached : DelegationException("delegation_limit_reached")
    data class Rejected(val rejectReason: String?) :
        DelegationException("rejected: ${rejectReason ?: "no_reason"}")
    /**
     * Reserved scaffolding for CHANNEL_RESUME / continue_with / TTL on handles.
     *
     * TODO Plan 4: thrown when a re-association attempt determines the handle's
     * session has reached a terminal state since the channel was last alive.
     * v1 keeps the type but never throws it — kept here so the rest of the
     * exception hierarchy doesn't change shape when Plan 4 lands.
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
