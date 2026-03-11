package com.workflow.orchestrator.core.offline

enum class ServiceStatus {
    UNKNOWN,
    ONLINE,
    OFFLINE
}

enum class OverallState {
    ONLINE,
    DEGRADED,
    OFFLINE
}
