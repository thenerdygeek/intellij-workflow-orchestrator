package com.workflow.orchestrator.core.healthcheck.checks

import com.workflow.orchestrator.core.settings.PluginSettings
import java.util.concurrent.atomic.AtomicReference

class SonarGateCheck : HealthCheck {
    override val id = "sonar-gate"
    override val displayName = "Sonar Quality Gate"
    override val order = 40

    private val lastKnownStatus = AtomicReference<Boolean?>(null)

    fun setLastKnownGateStatus(passed: Boolean?) {
        lastKnownStatus.set(passed)
    }

    override fun isEnabled(settings: PluginSettings.State): Boolean =
        settings.healthCheckSonarGateEnabled

    override suspend fun execute(context: HealthCheckContext): HealthCheck.CheckResult {
        val status = lastKnownStatus.get()

        return when (status) {
            null -> HealthCheck.CheckResult(
                passed = true,
                message = "Sonar quality gate: no cached data (skipped)"
            )
            true -> HealthCheck.CheckResult(
                passed = true,
                message = "Sonar quality gate passed"
            )
            false -> HealthCheck.CheckResult(
                passed = false,
                message = "Sonar quality gate failed"
            )
        }
    }
}
