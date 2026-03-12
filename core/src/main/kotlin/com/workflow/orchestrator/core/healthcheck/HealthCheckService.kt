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

    data class ChangeClassification(
        val hasProductionCode: Boolean,
        val hasTestCode: Boolean,
        val hasResources: Boolean,
        val hasBuildConfig: Boolean
    )

    fun classifyChanges(changedFiles: List<com.intellij.openapi.vfs.VirtualFile>): ChangeClassification {
        val fileIndex = com.intellij.openapi.roots.ProjectFileIndex.getInstance(project)
        var hasProd = false; var hasTest = false; var hasRes = false; var hasBuild = false

        for (file in changedFiles) {
            when {
                file.name == "pom.xml" || file.name.endsWith(".gradle.kts") -> hasBuild = true
                fileIndex.isInTestSourceContent(file) -> hasTest = true
                fileIndex.isInSourceContent(file) && !fileIndex.isInTestSourceContent(file) -> hasProd = true
                fileIndex.isInContent(file) && !fileIndex.isInSourceContent(file) -> hasRes = true
            }
        }
        return ChangeClassification(hasProd, hasTest, hasRes, hasBuild)
    }

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

        val checksToRun = if (context.changedFiles.isNotEmpty()) {
            val classification = classifyChanges(context.changedFiles)
            enabledChecks.filter { check ->
                when (check.id) {
                    "maven-compile" -> classification.hasProductionCode || classification.hasBuildConfig
                    "maven-test" -> classification.hasProductionCode || classification.hasTestCode || classification.hasBuildConfig
                    "copyright" -> classification.hasProductionCode
                    "sonar-gate" -> true
                    else -> true
                }
            }
        } else {
            enabledChecks
        }

        val eventBus = project.getService(EventBus::class.java)
        eventBus.emit(
            WorkflowEvent.HealthCheckStarted(checks = checksToRun.map { it.id })
        )

        val startTime = System.currentTimeMillis()
        val results = mutableMapOf<String, HealthCheck.CheckResult>()

        for (check in checksToRun) {
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
