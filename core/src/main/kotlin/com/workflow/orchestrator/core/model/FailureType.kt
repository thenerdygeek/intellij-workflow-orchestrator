package com.workflow.orchestrator.core.model

enum class FailureType {
    NETWORK,
    TIMEOUT,
    AUTH,
    RATE_LIMIT,
    USER_CANCELED,
    VALIDATION,
    SERVER_ERROR,
    UNKNOWN
}
