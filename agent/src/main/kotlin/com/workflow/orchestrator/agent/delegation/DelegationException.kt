package com.workflow.orchestrator.agent.delegation

sealed class DelegationException(message: String) : RuntimeException(message) {
    object UserCanceledPicker : DelegationException("user_canceled_picker")
    object TargetNotReachable : DelegationException("target_not_reachable")
    object LimitReached : DelegationException("delegation_limit_reached")
    data class Rejected(val rejectReason: String?) : DelegationException("rejected: ${rejectReason ?: "no_reason"}")
    data class Expired(val expireReason: String?) : DelegationException("expired: ${expireReason ?: "no_reason"}")
}
