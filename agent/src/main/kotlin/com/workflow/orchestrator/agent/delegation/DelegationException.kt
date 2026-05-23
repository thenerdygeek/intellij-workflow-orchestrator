package com.workflow.orchestrator.agent.delegation

sealed class DelegationException(message: String) : RuntimeException(message) {
    object UserCanceledPicker : DelegationException("user_canceled_picker")
    object TargetNotReachable : DelegationException("target_not_reachable")
    object LimitReached : DelegationException("delegation_limit_reached")
    data class Rejected(val rejectReason: String?) :
        DelegationException("rejected: ${rejectReason ?: "no_reason"}")
    data class Expired(val expireReason: String?) :
        DelegationException("expired: ${expireReason ?: "no_reason"}")

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
