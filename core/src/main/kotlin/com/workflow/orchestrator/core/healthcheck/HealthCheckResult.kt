package com.workflow.orchestrator.core.healthcheck

import com.workflow.orchestrator.core.healthcheck.checks.HealthCheck

data class HealthCheckResult(
    val passed: Boolean,
    val checkResults: Map<String, HealthCheck.CheckResult>,
    val skipped: Boolean = false,
    val skipReason: String? = null,
    val durationMs: Long = 0
) {
    companion object {
        fun skipped(reason: String = "Health check disabled") =
            HealthCheckResult(passed = true, checkResults = emptyMap(), skipped = true, skipReason = reason)
    }
}
