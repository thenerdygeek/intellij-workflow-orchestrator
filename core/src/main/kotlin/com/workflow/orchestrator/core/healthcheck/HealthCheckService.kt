package com.workflow.orchestrator.core.healthcheck

import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project
import com.workflow.orchestrator.core.events.EventBus
import com.workflow.orchestrator.core.events.WorkflowEvent
import com.workflow.orchestrator.core.healthcheck.checks.*
import com.workflow.orchestrator.core.settings.PluginSettings
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.filterIsInstance

@Service(Service.Level.PROJECT)
class HealthCheckService(private val project: Project) {

    private val sonarGateCheck = SonarGateCheck()

    private val checks = listOf(
        MavenCompileCheck(),
        MavenTestCheck(),
        CopyrightCheck(),
        sonarGateCheck
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Subscribe to QualityGateResult events to update SonarGateCheck's cached status
        scope.launch {
            project.getService(EventBus::class.java).events
                .filterIsInstance<WorkflowEvent.QualityGateResult>()
                .collect { event ->
                    sonarGateCheck.setLastKnownGateStatus(event.passed)
                }
        }
    }

    suspend fun runChecks(context: HealthCheckContext): HealthCheckResult {
        val settings = PluginSettings.getInstance(project).state
        if (!settings.healthCheckEnabled) return HealthCheckResult.skipped()

        val branch = context.branch
        val skipPattern = settings.healthCheckSkipBranchPattern
        if (!skipPattern.isNullOrBlank()) {
            try {
                if (Regex(skipPattern).matches(branch)) {
                    return HealthCheckResult.skipped("Branch matches skip pattern")
                }
            } catch (_: java.util.regex.PatternSyntaxException) {
                // Invalid regex in settings — ignore skip pattern, continue with checks
            }
        }

        val enabledChecks = checks.filter { it.isEnabled(settings) }

        val eventBus = project.getService(EventBus::class.java)
        eventBus.emit(
            WorkflowEvent.HealthCheckStarted(checks = enabledChecks.map { it.id })
        )

        val startTime = System.currentTimeMillis()
        val results = mutableMapOf<String, HealthCheck.CheckResult>()

        for (check in enabledChecks) {
            val result = withTimeoutOrNull(
                settings.healthCheckTimeoutSeconds * 1000L
            ) {
                check.execute(context)
            } ?: HealthCheck.CheckResult(
                passed = false,
                message = "${check.displayName} timed out after ${settings.healthCheckTimeoutSeconds}s"
            )
            results[check.id] = result
        }

        val passed = results.values.all { it.passed }
        val durationMs = System.currentTimeMillis() - startTime

        eventBus.emit(
            WorkflowEvent.HealthCheckFinished(
                passed = passed,
                results = results.mapValues { it.value.passed },
                durationMs = durationMs
            )
        )

        return HealthCheckResult(passed, results, durationMs = durationMs)
    }

    companion object {
        fun getInstance(project: Project): HealthCheckService =
            project.getService(HealthCheckService::class.java)
    }
}
